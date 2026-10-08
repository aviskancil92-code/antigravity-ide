package com.antigravity.ide.install

import android.content.Context
import android.net.Uri
import com.antigravity.ide.agy.AgyServer
import com.antigravity.ide.core.AppState
import com.antigravity.ide.core.Archive
import com.antigravity.ide.core.Net
import com.antigravity.ide.core.Pins
import com.antigravity.ide.core.StateStore
import com.antigravity.ide.runtime.LinuxRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files

/**
 * Installer pertama kali (semua langkah ditulis ke direktori staging, lalu di-rename atomik):
 *  1. Unduh & ekstrak proot + libtalloc + libandroid-shmem (dari repo apt Termux).
 *  2. Unduh & ekstrak rootfs Debian (linuxcontainers.org).
 *  3. Unduh agy-server (rilis GitHub) + language_server (bundel resmi Antigravity), validasi ELF.
 *  4. Tulis state lalu pindahkan staging -> files/linux secara atomik.
 *
 * Konfigurasi `agy-server` (config + kata sandi) sengaja TIDAK dilakukan di sini, melainkan
 * saat start pertama oleh [AgyServer.ensureProvisioned] — agar bisa diulang bila gagal.
 *
 * Mendukung impor manual (Uri) untuk rootfs & bundel Antigravity bagi pengguna yang
 * ingin memakai berkas unduhan sendiri.
 */
class Installer(private val ctx: Context) {

    data class Progress(
        val step: String,
        val detail: String = "",
        val read: Long = 0,
        val total: Long = -1,
        val speedBps: Long = 0,
        /** 0 proot, 1 Debian, 2 Antigravity, 3 konfigurasi akhir (untuk UI langkah-langkah). */
        val phase: Int = 0,
        /** Pecahan 0..1 di dalam fase ini bila installer sendiri yang menghitung; -1 = biarkan UI menebak. */
        val phaseFraction: Double = -1.0
    )

    suspend fun install(
        onProgress: suspend (Progress) -> Unit,
        manualRootfs: Uri? = null,
        manualBundle: Uri? = null
    ): AppState = withContext(Dispatchers.IO) {
        val staging = File(ctx.filesDir, "linux-staging")
        StateStore.deleteRecursive(staging)
        staging.mkdirs()
        listOf("bin", "lib", "tmp", "logs", "downloads", "debs").forEach {
            File(staging, it).mkdirs()
        }

        val arch = LinuxRuntime.arch()
            ?: throw IOException("Arsitektur CPU tidak didukung (Antigravity butuh arm64 atau x86_64; armv7 32-bit tidak tersedia).")
        var ideVersion = ""

        try {
            // ---- 1) proot + library pendukung -------------------------------
            installProot(staging, arch.termux) { p -> onProgress(p.copy(phase = 0)) }

            // ---- 2) rootfs Debian -------------------------------------------
            installRootfs(staging, arch.rootfs, manualRootfs) { p -> onProgress(p.copy(phase = 1)) }

            // ---- 3) agy-server + language_server -----------------------------
            ideVersion = installAntigravity(staging, arch, manualBundle) { p -> onProgress(p.copy(phase = 2)) }

            // ---- 4) konfigurasi + finalisasi --------------------------------
            onProgress(Progress("Menulis konfigurasi…", phase = 3))
            finalize(staging, ideVersion)
            StateStore.read(ctx)
        } catch (e: CancellationException) {
            StateStore.deleteRecursive(staging)
            throw e
        } catch (e: Exception) {
            StateStore.deleteRecursive(staging)
            throw e
        }
    }

    // ------------------------------------------------------------------ proot

    private suspend fun installProot(
        staging: File,
        termuxArch: String,
        onProgress: suspend (Progress) -> Unit
    ) {
        onProgress(Progress("Mengambil indeks paket Termux…"))
        val packages = fetchPackagesIndex(termuxArch)
        val prootPath = packages["proot"]
            ?: throw IOException("paket proot tidak ditemukan pada indeks repo Termux")
        val tallocPath = packages["libtalloc"]
            ?: throw IOException("paket libtalloc tidak ditemukan pada indeks repo Termux")
        val shmemPath = packages["libandroid-shmem"]
            ?: throw IOException("paket libandroid-shmem tidak ditemukan pada indeks repo Termux")

        val debsDir = File(staging, "debs")
        val prootDeb = File(debsDir, "proot.deb")
        val tallocDeb = File(debsDir, "libtalloc.deb")
        val shmemDeb = File(debsDir, "libandroid-shmem.deb")

        Net.download(Pins.TERMUX_REPO + prootPath, prootDeb) { r, t, s ->
            onProgress(Progress("Mengunduh proot…", "(${termuxArch})", r, t, s))
        }
        Net.download(Pins.TERMUX_REPO + tallocPath, tallocDeb) { r, t, s ->
            onProgress(Progress("Mengunduh libtalloc…", "", r, t, s))
        }
        Net.download(Pins.TERMUX_REPO + shmemPath, shmemDeb) { r, t, s ->
            onProgress(Progress("Mengunduh libandroid-shmem…", "", r, t, s))
        }

        onProgress(Progress("Mengekstrak proot…"))
        val debOut = File(staging, "deb-out")
        debOut.mkdirs()
        Archive.extractDebToDir(prootDeb, debOut)
        Archive.extractDebToDir(tallocDeb, debOut)
        Archive.extractDebToDir(shmemDeb, debOut)

        val proot = findFile(debOut) { f ->
            !Files.isSymbolicLink(f.toPath()) && f.isFile && f.name == "proot"
        } ?: throw IOException("biner proot tidak ditemukan di dalam paket .deb")

        val talloc = findFile(debOut) { f ->
            !Files.isSymbolicLink(f.toPath()) && f.isFile &&
                Regex("^libtalloc\\.so\\.2").containsMatchIn(f.name)
        } ?: throw IOException("libtalloc.so.2 tidak ditemukan di dalam paket .deb")

        val shmem = findFile(debOut) { f ->
            !Files.isSymbolicLink(f.toPath()) && f.isFile && f.name == "libandroid-shmem.so"
        } ?: throw IOException("libandroid-shmem tidak ditemukan di dalam paket .deb")

        copyTo(proot, File(staging, "bin/proot"), 0x1ED)      // 0755
        copyTo(talloc, File(staging, "lib/libtalloc.so.2"), 0x1A4) // 0644
        copyTo(shmem, File(staging, "lib/libandroid-shmem.so"), 0x1A4) // 0644

        // Loader proot (bila build Termux memisahkannya dari biner). Tanpa ini execve
        // ke dalam rootfs gagal. Opsional: build yang meng-embed loader tak punya berkasnya.
        findFile(debOut) { f ->
            !Files.isSymbolicLink(f.toPath()) && f.isFile && f.name == "loader" &&
                f.parentFile?.name == "proot"
        }?.let { copyTo(it, File(staging, "lib/proot-loader"), 0x1ED) }
        findFile(debOut) { f ->
            !Files.isSymbolicLink(f.toPath()) && f.isFile && f.name == "loader32" &&
                f.parentFile?.name == "proot"
        }?.let { copyTo(it, File(staging, "lib/proot-loader32"), 0x1ED) }

        StateStore.deleteRecursive(debOut)
        StateStore.deleteRecursive(debsDir)
    }

    private fun fetchPackagesIndex(termuxArch: String): Map<String, String> {
        val path = "dists/stable/main/binary-$termuxArch/Packages"
        val text = try {
            Net.getText(Pins.TERMUX_REPO + path)
        } catch (e: Exception) {
            Net.getText(Pins.TERMUX_REPO_MIRROR + path)
        }
        return Net.parsePackagesIndex(text)
    }

    // ----------------------------------------------------------------- rootfs

    private suspend fun installRootfs(
        staging: File,
        rootfsArch: String,
        manual: Uri?,
        onProgress: suspend (Progress) -> Unit
    ) {
        val rootfsFile: File
        if (manual != null) {
            rootfsFile = File(File(staging, "downloads"), "rootfs-manual.tar")
            copyUri(manual, rootfsFile) { r, t ->
                onProgress(Progress("Menyalin rootfs…", "", r, t, 0))
            }
        } else {
            val urls = resolveRootfsUrls(rootfsArch)
            if (urls.isEmpty()) {
                throw IOException(
                    "Daftar build Debian ($rootfsArch) tidak dapat dibaca dari linuxcontainers.org. " +
                        "Coba lagi nanti atau gunakan Impor manual rootfs dari Pengaturan."
                )
            }
            rootfsFile = File(File(staging, "downloads"), "rootfs.tar.xz")
            var lastError: IOException? = null
            var ok = false
            for (url in urls) {
                try {
                    Net.download(url, rootfsFile) { r, t, s ->
                        onProgress(Progress("Mengunduh rootfs Debian $rootfsArch…", "Debian ${Pins.DEBIAN_RELEASE}", r, t, s))
                    }
                    ok = true
                    break
                } catch (e: IOException) {
                    lastError = e
                    rootfsFile.delete()
                }
            }
            if (!ok) {
                throw IOException(
                    "Gagal mengunduh rootfs: ${lastError?.message}. " +
                        "Coba lagi atau gunakan Impor manual rootfs dari Pengaturan."
                )
            }
        }

        onProgress(Progress("Mengekstrak rootfs Debian…", "ini bisa memakan beberapa menit"))
        val rootfsDir = File(staging, "debian")
        rootfsDir.mkdirs()
        val (counting, stream) = Archive.openTarFile(rootfsFile)
        val total = rootfsFile.length()
        try {
            Archive.extractTar(stream, rootfsDir) {
                onProgress(Progress("Mengekstrak rootfs Debian…", "", counting.count, total, 0))
            }
        } finally {
            runCatching { stream.close() }
            runCatching { counting.close() }
        }

        val bashOk = File(rootfsDir, "bin/bash").exists() || File(rootfsDir, "usr/bin/bash").exists()
        if (!bashOk) throw IOException("rootfs tidak valid: bash tidak ditemukan di dalam arsip")
        rootfsFile.delete()
    }

    /**
     * Cari URL rootfs terbaru: scrape daftar tanggal build di
     * images.linuxcontainers.org (diurutkan menurun, dicoba satu per satu).
     */
    private fun resolveRootfsUrls(rootfsArch: String): List<String> {
        val base = Pins.LXC_BASE + Pins.DEBIAN_RELEASE + "/" + rootfsArch + "/default/"
        val html = try {
            Net.getText(base, 25_000)
        } catch (_: Exception) {
            return emptyList()
        }
        val dates = Regex("href=\"(\\d{8}_\\d{2}(?:%3A|:)\\d{2})/\"")
            .findAll(html)
            .map { it.groupValues[1] }
            .distinct()
            .sortedDescending()
            .toList()
        return dates.take(3).map { base + it + "/rootfs.tar.xz" }
    }

    // ------------------------------------------------------------- Antigravity

    private fun isElf(f: File): Boolean = runCatching {
        FileInputStream(f).use { i ->
            val h = ByteArray(4)
            i.read(h) == 4 && h[0] == 0x7F.toByte() && h[1] == 'E'.code.toByte() &&
                h[2] == 'L'.code.toByte() && h[3] == 'F'.code.toByte()
        }
    }.getOrDefault(false)

    private fun frac(read: Long, total: Long): Double =
        if (total > 0) (read.toDouble() / total).coerceIn(0.0, 1.0) else 0.0

    /**
     * Pasang agy-server (kecil) dan language_server (±170 MB, diambil dari bundel resmi Google).
     * Pecahan progres fase ini: agy-server 0–5%, unduh bundel 5–80%, ekstraksi 80–98%.
     * @return versi IDE ("" bila tidak diketahui, mis. impor manual).
     */
    private suspend fun installAntigravity(
        staging: File,
        arch: LinuxRuntime.Arch,
        manualBundle: Uri?,
        onProgress: suspend (Progress) -> Unit
    ): String {
        val rootfs = File(staging, "debian")
        val downloads = File(staging, "downloads")

        // ---- a) agy-server ----------------------------------------------------
        val srvTar = File(downloads, "agy-server.tar.gz")
        val srvUrl = Pins.agyServerUrl(arch.agy)
        onProgress(Progress("Mengambil agy-server…", "(${arch.agy})", phaseFraction = 0.0))
        Net.download(srvUrl, srvTar) { r, t, s ->
            onProgress(Progress("Mengunduh agy-server…", "(${arch.agy})", r, t, s, phaseFraction = 0.05 * frac(r, t)))
        }

        onProgress(Progress("Memasang agy-server…", phaseFraction = 0.05))
        val srvOut = File(staging, "agy-extract")
        srvOut.mkdirs()
        val (c1, st1) = Archive.openTarFile(srvTar)
        try {
            Archive.extractTar(st1, srvOut)
        } finally {
            runCatching { st1.close() }
            runCatching { c1.close() }
        }
        val srvBin = findFile(srvOut) { f ->
            !Files.isSymbolicLink(f.toPath()) && f.isFile && f.name == "agy-server"
        } ?: throw IOException("biner agy-server tidak ditemukan di dalam arsip rilis ($srvUrl)")
        AgyServer.verifyElf(srvBin, arch.elfMachine)
        copyTo(srvBin, AgyServer.hostFile(rootfs, AgyServer.GUEST_BIN), 0x1ED) // 0755
        StateStore.deleteRecursive(srvOut)
        srvTar.delete()

        // ---- b) bundel Antigravity -> language_server ----------------------
        val bundle = File(downloads, "antigravity-bundle")
        var version = ""
        if (manualBundle != null) {
            copyUri(manualBundle, bundle) { r, t ->
                onProgress(Progress("Menyalin berkas Antigravity…", "", r, t, 0, phaseFraction = 0.05 + 0.75 * frac(r, t)))
            }
        } else {
            onProgress(Progress("Mencari bundel Antigravity…", phaseFraction = 0.05))
            val url = AgyServer.resolveBundleUrl(arch.hub)
                ?: throw IOException(
                    "URL bundel Antigravity (${arch.hub}) tidak ditemukan di ${Pins.ANTIGRAVITY_DOWNLOAD_PAGE}. " +
                        "Coba lagi nanti atau gunakan Impor Antigravity dari Pengaturan."
                )
            version = AgyServer.versionFromBundleUrl(url)
            Net.download(url, bundle) { r, t, s ->
                onProgress(
                    Progress(
                        "Mengunduh Antigravity" + (if (version.isEmpty()) "" else " $version") + "…",
                        "(${arch.hub})", r, t, s,
                        phaseFraction = 0.05 + 0.75 * frac(r, t)
                    )
                )
            }
        }

        val lsTmp = File(downloads, "language_server")
        if (isElf(bundle)) {
            // Pengguna mengimpor biner language_server langsung.
            copyTo(bundle, lsTmp, 0x1ED)
        } else {
            onProgress(Progress("Mengekstrak language_server…", "dari bundel Antigravity", phaseFraction = 0.80))
            val total = bundle.length()
            val (c2, st2) = Archive.openTarFile(bundle)
            val found = try {
                Archive.extractSingle(st2, lsTmp, { name -> AgyServer.isLanguageServerEntry(name) }) {
                    onProgress(
                        Progress(
                            "Mengekstrak language_server…", "", c2.count, total, 0,
                            phaseFraction = 0.80 + 0.18 * frac(c2.count, total)
                        )
                    )
                }
            } finally {
                runCatching { st2.close() }
                runCatching { c2.close() }
            }
            if (!found) {
                throw IOException(
                    "language_server tidak ditemukan di dalam arsip Antigravity " +
                        "(diharapkan <folder>/resources/bin/language_server)."
                )
            }
        }

        AgyServer.verifyElf(lsTmp, arch.elfMachine)
        copyTo(lsTmp, AgyServer.hostFile(rootfs, AgyServer.GUEST_LS), 0x1ED) // 0755
        lsTmp.delete()
        bundle.delete()
        onProgress(Progress("Antigravity terpasang", phaseFraction = 1.0))
        return version
    }

    // --------------------------------------------------------------- finalisasi

    private fun finalize(staging: File, ideVersion: String) {
        val rootfs = File(staging, "debian")
        AgyServer.writeStartScript(rootfs)
        val state = AppState(
            installed = true,
            ideVersion = ideVersion,
            rootfsSource = "debian/${Pins.DEBIAN_RELEASE} (linuxcontainers.org)",
            password = "",
            provisioned = false,
            keepScreenOn = false,
            createdAt = System.currentTimeMillis()
        )
        StateStore.writeTo(staging, state)

        val final = StateStore.linuxDir(ctx)
        if (final.exists()) StateStore.deleteRecursive(final)
        if (!staging.renameTo(final)) {
            throw IOException("gagal memfinalisasi instalasi (rename staging)")
        }
    }

    // ---------------------------------------------------------------- util

    private fun copyTo(src: File, dst: File, mode: Int) {
        dst.parentFile?.mkdirs()
        FileInputStream(src).use { i ->
            FileOutputStream(dst).use { o -> i.copyTo(o) }
        }
        try { android.system.Os.chmod(dst.path, mode) } catch (_: Exception) { }
    }

    private fun findFile(root: File, pred: (File) -> Boolean): File? {
        if (root.isDirectory && !Files.isSymbolicLink(root.toPath())) {
            val children = root.listFiles() ?: return null
            for (c in children) {
                findFile(c, pred)?.let { return it }
            }
            return null
        }
        return if (pred(root)) root else null
    }

    private suspend fun copyUri(uri: Uri, dest: File, onProgress: suspend (read: Long, total: Long) -> Unit) {
        val input = ctx.contentResolver.openInputStream(uri)
            ?: throw IOException("tidak dapat membaca berkas yang dipilih")
        var total = -1L
        try {
            ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                total = afd.length
            }
        } catch (_: Exception) { }

        input.use { i ->
            FileOutputStream(dest).use { out ->
                val buf = ByteArray(64 * 1024)
                var read = 0L
                while (true) {
                    val n = i.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    read += n
                    onProgress(read, total)
                }
                out.fd.sync()
            }
        }
    }
}
