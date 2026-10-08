package com.antigravity.ide.agy

import android.content.Context
import com.antigravity.ide.core.Net
import com.antigravity.ide.core.Pins
import com.antigravity.ide.core.StateStore
import com.antigravity.ide.runtime.GuestExec
import com.antigravity.ide.runtime.LinuxRuntime
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.nio.file.Files

/**
 * Modul PRODUK: semua yang spesifik untuk Antigravity Server (https://github.com/AFSlayer/antigravity-server).
 *
 * Arsitektur upstream (dari README/install.sh):
 *   WebView ─► agy-server (reverse proxy + login + patch UI mobile, port 8765)
 *                 └─► language_server --standalone (inti Antigravity resmi, 127.0.0.1)
 *
 * Seluruh path di bawah adalah path DI DALAM guest Debian. Hanya paket ini yang perlu diubah
 * untuk mengganti produk yang dibungkus; runtime (proot/Debian), installer generik, dan UI
 * tidak bergantung pada detail Antigravity.
 */
object AgyServer {

    const val PORT = 8765
    const val URL = "http://127.0.0.1:$PORT/"

    const val GUEST_BIN = "/usr/local/bin/agy-server"
    const val GUEST_LS_DIR = "/opt/agy-server"
    const val GUEST_LS = "$GUEST_LS_DIR/language_server"
    const val GUEST_WORKSPACE = "/root/workspace"
    const val GUEST_START = "/root/.agy/start.sh"

    /** Lokasi token OAuth yang dibaca language_server (sama dengan instruksi `scp` di install.sh). */
    const val GUEST_TOKEN = "/root/.gemini/jetski-standalone-oauth-token"

    /** Config yang ditulis `agy-server config` (HOME/.agy-remote/config.json di install.sh). */
    private const val GUEST_CONFIG = "/root/.agy-remote/config.json"

    /** Log keluaran language_server yang ditulis agy-server (sumber utama penyebab gagal start). */
    const val GUEST_LS_LOG = "/root/.agy-remote/language-server.log"

    fun hostFile(rootfs: File, guestPath: String): File = File(rootfs, guestPath.removePrefix("/"))

    /** Terpasang penuh: proot + Debian + agy-server + language_server. */
    fun isInstalled(ctx: Context): Boolean {
        if (!LinuxRuntime.baseInstalled(ctx)) return false
        val root = LinuxRuntime.rootfsDir(ctx)
        return hostFile(root, GUEST_BIN).isFile && hostFile(root, GUEST_LS).isFile
    }

    // ------------------------------------------------------------------ install helpers

    /**
     * Cari URL bundel Antigravity untuk platform [hubSlug] dengan memindai halaman unduhan resmi —
     * persis seperti `resolve_hub_url` pada install.sh upstream. Null bila tidak ditemukan.
     */
    fun resolveBundleUrl(hubSlug: String): String? {
        val html = try {
            Net.getText(Pins.ANTIGRAVITY_DOWNLOAD_PAGE, 25_000, Pins.DOWNLOAD_PAGE_UA)
        } catch (_: Exception) {
            return null
        }
        // Beberapa halaman menyematkan URL sebagai JSON dengan "\/" — normalkan dulu.
        val text = html.replace("\\/", "/")
        val re = Regex(
            "https://storage\\.googleapis\\.com/antigravity-public/antigravity-hub/[^\"'<> ]+/" +
                Regex.escape(hubSlug) + "/Antigravity\\.tar\\.gz"
        )
        return re.find(text)?.value
    }

    /** Versi IDE dari URL bundel (…/antigravity-hub/1.2.3-4567/…) atau "" bila tidak dikenali. */
    fun versionFromBundleUrl(url: String): String =
        Regex("/antigravity-hub/([0-9][0-9.]*)-[0-9]+/").find(url)?.groupValues?.get(1) ?: ""

    /** Nama entri language_server di dalam Antigravity.tar.gz (…/resources/bin/language_server). */
    fun isLanguageServerEntry(name: String): Boolean =
        Regex("^(?:\\./)?[^/]+/resources/bin/language_server$").matches(name)

    /**
     * Validasi header ELF: 64-bit little-endian dan [machine] sesuai (183 = aarch64, 62 = x86-64).
     * Mencegah biner arsitektur salah / halaman HTML error terpasang diam-diam.
     */
    fun verifyElf(file: File, machine: Int) {
        val head = ByteArray(20)
        try {
            RandomAccessFile(file, "r").use { it.readFully(head) }
        } catch (e: IOException) {
            throw IOException("${file.name}: berkas terlalu kecil / tidak terbaca")
        }
        val isElf = head[0] == 0x7F.toByte() && head[1] == 'E'.code.toByte() &&
            head[2] == 'L'.code.toByte() && head[3] == 'F'.code.toByte()
        if (!isElf) throw IOException("${file.name} bukan biner ELF Linux (unduhan rusak atau berkas salah)")
        if (head[4].toInt() != 2 || head[5].toInt() != 1) {
            throw IOException("${file.name} bukan ELF 64-bit little-endian")
        }
        val m = (head[18].toInt() and 0xFF) or ((head[19].toInt() and 0xFF) shl 8)
        if (m != machine) {
            throw IOException("${file.name} untuk arsitektur CPU lain (e_machine=$m, dibutuhkan $machine)")
        }
    }

    // --------------------------------------------------------------- guest scripts

    /** Skrip start di dalam guest (idempoten). Dijalankan proot sebagai proses server utama. */
    fun writeStartScript(rootfs: File) {
        val f = hostFile(rootfs, GUEST_START)
        f.parentFile?.mkdirs()
        f.writeText(
            LinuxRuntime.GUEST_ENV_PRELUDE +
                "mkdir -p $GUEST_WORKSPACE /root/.gemini\n" +
                "exec $GUEST_BIN serve\n"
        )
        runCatching { android.system.Os.chmod(f.path, 0x1ED) } // 0755
    }

    // ------------------------------------------------------------------ provisioning

    sealed class Provision {
        object Ready : Provision()
        data class Failed(val message: String) : Provision()
    }

    /**
     * Konfigurasi pertama (idempoten): `agy-server config …` lalu `agy-server passwd …`
     * — urutan yang sama dengan install.sh. Dipanggil sebelum server dijalankan; dilewati bila
     * sudah pernah berhasil dan config.json masih ada.
     * Panggil dari thread latar belakang.
     */
    fun ensureProvisioned(ctx: Context): Provision {
        val rootfs = LinuxRuntime.rootfsDir(ctx)
        val state = StateStore.read(ctx)
        val configExists = hostFile(rootfs, GUEST_CONFIG).isFile
        if (state.provisioned && configExists && state.password.isNotEmpty()) {
            enforceLoopback(rootfs)
            return Provision.Ready
        }

        // 1) config (jangan timpa bila config sudah ada tetapi password belum — hanya passwd)
        if (!configExists) {
            val r = GuestExec.run(
                ctx,
                listOf(
                    GUEST_BIN, "config",
                    "--port", PORT.toString(),
                    "--language-server", GUEST_LS,
                    "--workspace-root", GUEST_WORKSPACE
                ),
                extraEnv = mapOf("AGY_IDE_VERSION" to state.ideVersion),
                timeoutMs = 90_000L
            )
            AgyService.logLine("[config] kode=${r.exitCode} timeout=${r.timedOut} ${r.output.trim().takeLast(300)}")
            if (!r.ok) return Provision.Failed("agy-server config gagal (kode ${r.exitCode}): ${r.output.trim().takeLast(400)}")
        }

        enforceLoopback(rootfs)

        // 2) password
        val wanted = state.password.ifEmpty { StateStore.newPassword() }
        val pw = when (val set = setPasswordInternal(ctx, wanted)) {
            is PasswordResult.Ok -> set.password
            is PasswordResult.Failed -> return Provision.Failed(set.message)
        }
        StateStore.update(ctx) { it.copy(password = pw, provisioned = true) }
        return Provision.Ready
    }

    /**
     * config.json bawaan agy-server memakai "bind_addr": "0.0.0.0" (terbuka ke Wi-Fi/LAN). Karena
     * hanya WebView di perangkat ini yang perlu mengakses, paksa ke 127.0.0.1. Dipanggil sebelum
     * SETIAP start agar tetap berlaku bila config ditulis ulang.
     */
    fun enforceLoopback(rootfs: File) {
        try {
            val f = hostFile(rootfs, GUEST_CONFIG)
            if (!f.isFile) return
            val o = org.json.JSONObject(f.readText())
            if (o.optString("bind_addr") != "127.0.0.1") {
                o.put("bind_addr", "127.0.0.1")
                f.writeText(o.toString(2) + "\n")
                AgyService.logLine("[app] bind_addr diubah ke 127.0.0.1")
            }
        } catch (e: Exception) {
            AgyService.logLine("[app] gagal mengatur bind_addr: ${e.message}")
        }
    }

    /**
     * Kumpulkan info untuk menelusuri kegagalan start: arsitektur guest, pustaka yang hilang,
     * apakah language_server bisa dieksekusi sama sekali, dan hasil `agy-server doctor`.
     * Panggil dari thread latar belakang, setelah proses server dihentikan.
     */
    fun collectDiagnostics(ctx: Context): String {
        val steps = listOf(
            "uname -m" to listOf("/usr/bin/uname", "-m"),
            "ls -l $GUEST_LS_DIR" to listOf("/usr/bin/ls", "-l", GUEST_LS_DIR),
            "ldd language_server" to listOf("/usr/bin/ldd", GUEST_LS),
            "language_server --help" to listOf(GUEST_LS, "--help"),
            "agy-server doctor" to listOf(GUEST_BIN, "doctor"),
            "language-server.log (akhir)" to listOf("/usr/bin/tail", "-n", "40", GUEST_LS_LOG)
        )
        val sb = StringBuilder()
        for ((title, argv) in steps) {
            val r = GuestExec.run(ctx, argv, timeoutMs = 25_000L)
            val out = r.output.trim().lines().takeLast(40).joinToString("\n").takeLast(3000)
            sb.append("[diag] $title → kode=${r.exitCode}${if (r.timedOut) " (TIMEOUT)" else ""}\n$out\n")
        }
        return sb.toString()
    }

    /**
     * Diagnostik SAAT language_server masih hidup (jalankan sebelum server dihentikan):
     * apakah /proc/net/tcp terbaca (dipakai banyak alat untuk menemukan port acak), alat
     * lsof/ss ada atau tidak, dan apakah port yang dilaporkan language_server bisa dihubungi dari guest.
     */
    fun collectLiveDiagnostics(ctx: Context, ports: Collection<Int>): String {
        val steps = mutableListOf(
            "baca /proc/net/tcp" to listOf("/usr/bin/head", "-n", "6", "/proc/net/tcp"),
            "lsof/ss/netstat ada?" to listOf("/bin/bash", "-c", "command -v lsof ss netstat || echo tidak-ada")
        )
        // Petunjuk statis dari biner agy-server: bagaimana ia mencari port language_server?
        steps += "agy-server: rujukan /proc & alat jaringan" to listOf(
            "/bin/bash", "-c",
            "grep -a -o -E '/proc/[A-Za-z0-9_%/.-]+|lsof|netstat|sock_diag|NETLINK|ss -[a-z]+' \$0 | sort | uniq -c | sort -rn | head -25",
            GUEST_BIN
        )
        steps += "agy-server: string terkait language server" to listOf(
            "/bin/bash", "-c",
            "grep -a -o -E '[ -~]{0,70}(https_server_port|standalone|language_server_port|LanguageServer port|discover)[ -~]{0,70}' \$0 | sort -u | head -30 | cut -c1-200",
            GUEST_BIN
        )
        for (port in ports.sorted()) {
            steps += "konek 127.0.0.1:$port" to
                listOf("/bin/bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/\$0 && echo TERBUKA", port.toString())
        }
        val sb = StringBuilder()
        for ((title, argv) in steps) {
            val r = GuestExec.run(ctx, argv, timeoutMs = 30_000L)
            val out = r.output.trim().lines().take(30).joinToString("\n").take(2500)
            sb.append("[live] $title → kode=${r.exitCode}${if (r.timedOut) " (TIMEOUT)" else ""}\n$out\n")
        }
        return sb.toString()
    }

    sealed class PasswordResult {
        data class Ok(val password: String) : PasswordResult()
        data class Failed(val message: String) : PasswordResult()
    }

    /** Ubah kata sandi akses web. Memperbarui state bila berhasil. Panggil dari thread latar belakang. */
    fun changePassword(ctx: Context, newPassword: String): PasswordResult {
        val res = setPasswordInternal(ctx, newPassword)
        if (res is PasswordResult.Ok) StateStore.update(ctx) { it.copy(password = res.password, provisioned = true) }
        return res
    }

    private fun setPasswordInternal(ctx: Context, wanted: String): PasswordResult {
        val r = GuestExec.run(ctx, listOf(GUEST_BIN, "passwd", wanted), timeoutMs = 60_000L)
        AgyService.logLine("[passwd] kode=${r.exitCode} timeout=${r.timedOut}")
        if (r.ok) return PasswordResult.Ok(wanted)

        // Cadangan: biarkan agy-server membuat sandi acak (`passwd ''` mencetak sandi ke stdout).
        val g = GuestExec.run(ctx, listOf(GUEST_BIN, "passwd", ""), timeoutMs = 60_000L)
        val generated = g.output.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }
        if (g.ok && generated != null && !generated.contains(' ')) return PasswordResult.Ok(generated)

        return PasswordResult.Failed(
            "agy-server passwd gagal (kode ${r.exitCode}): ${r.output.trim().takeLast(400)}"
        )
    }

    /** Jalankan `agy-server doctor` dan kembalikan keluarannya (untuk layar Diagnostik). */
    fun runDoctor(ctx: Context): GuestExec.Result =
        GuestExec.run(ctx, listOf(GUEST_BIN, "doctor"), timeoutMs = 60_000L)

    // -------------------------------------------------------------------- token OAuth

    /**
     * Pasang token OAuth Google (berkas ~/.gemini/jetski-standalone-oauth-token dari desktop).
     * Jalur resmi alternatif bila login Google lewat WebView ditolak Google.
     */
    fun importToken(ctx: Context, bytes: ByteArray) {
        if (bytes.isEmpty()) throw IOException("berkas token kosong")
        if (bytes.size > 256 * 1024) throw IOException("berkas terlalu besar untuk sebuah token")
        val dst = hostFile(LinuxRuntime.rootfsDir(ctx), GUEST_TOKEN)
        dst.parentFile?.mkdirs()
        runCatching { Files.deleteIfExists(dst.toPath()) }
        dst.writeBytes(bytes)
        runCatching { android.system.Os.chmod(dst.path, 0x180) } // 0600
    }

    // ------------------------------------------------------------------ keamanan jaringan

    /**
     * proot tidak mengisolasi jaringan: bila agy-server mengikat 0.0.0.0, port 8765 terjangkau dari
     * Wi-Fi. Probe sederhana: coba konek ke alamat IPv4 non-loopback milik perangkat sendiri.
     * Kata sandi (PBKDF2) + rate limit tetap melindungi, tetapi pengguna layak diberi tahu.
     */
    fun isReachableFromLan(): Boolean {
        return try {
            val addrs = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
                .flatMap { it.inetAddresses.toList() }
                .filter { !it.isLoopbackAddress && it is java.net.Inet4Address }
            addrs.any { a ->
                try {
                    Socket().use { s ->
                        s.connect(InetSocketAddress(a, PORT), 400)
                        true
                    }
                } catch (_: Exception) {
                    false
                }
            }
        } catch (_: Exception) {
            false
        }
    }
}
