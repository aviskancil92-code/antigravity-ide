package com.antigravity.ide.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.system.Os
import com.antigravity.ide.core.StateStore
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths

/**
 * Lapisan runtime GENERIK: pemetaan arsitektur, perintah proot, environment, dan berkas
 * konfigurasi guest Debian. Tidak tahu apa-apa soal produk yang dijalankan di dalamnya
 * (itu urusan paket `agy`), sehingga mudah dipakai ulang untuk aplikasi guest lain.
 */
object LinuxRuntime {

    /** Pemetaan ABI perangkat ke nama arsitektur tiap sumber unduhan. */
    data class Arch(
        /** Nama arsitektur repo Termux (proot). */
        val termux: String,
        /** Nama arsitektur rootfs LXC (Debian). */
        val rootfs: String,
        /** Akhiran nama aset rilis agy-server. */
        val agy: String,
        /** Slug platform pada URL bundel Antigravity. */
        val hub: String,
        /** Nilai e_machine ELF yang diharapkan untuk biner guest (untuk validasi). */
        val elfMachine: Int
    )

    fun rootfsDir(ctx: Context): File = File(StateStore.linuxDir(ctx), "debian")
    fun prootBin(ctx: Context): File = File(StateStore.linuxDir(ctx), "bin/proot")
    fun libDir(ctx: Context): File = File(StateStore.linuxDir(ctx), "lib")
    fun tmpDir(ctx: Context): File = File(StateStore.linuxDir(ctx), "tmp")
    fun logsDir(ctx: Context): File = File(StateStore.linuxDir(ctx), "logs")
    fun shmDir(ctx: Context): File = File(StateStore.linuxDir(ctx), "shm")
    fun fakeProcDir(ctx: Context): File = File(StateStore.linuxDir(ctx), "fakeproc")

    /** true bila proot + rootfs Debian (dengan bash) sudah terpasang. */
    fun baseInstalled(ctx: Context): Boolean {
        val st = StateStore.read(ctx)
        val root = rootfsDir(ctx)
        return st.installed &&
            prootBin(ctx).exists() &&
            (File(root, "bin/bash").exists() || File(root, "usr/bin/bash").exists())
    }

    /** ABI perangkat -> pemetaan arsitektur; null bila tidak didukung (mis. armv7). */
    fun arch(): Arch? = when (Build.SUPPORTED_ABIS.firstOrNull()) {
        "arm64-v8a" -> Arch(termux = "aarch64", rootfs = "arm64", agy = "arm64", hub = "linux-arm", elfMachine = 183)
        "x86_64" -> Arch(termux = "x86_64", rootfs = "amd64", agy = "amd64", hub = "linux-x64", elfMachine = 62)
        else -> null
    }

    /**
     * Baris shell yang menyiapkan environment guest. Dipakai start.sh (server) dan
     * [GuestExec] (perintah sekali jalan) supaya keduanya identik.
     * LD_LIBRARY_PATH host dibutuhkan proot untuk libtalloc, tetapi tidak boleh bocor ke guest.
     */
    val GUEST_ENV_PRELUDE: String = listOf(
        "unset LD_LIBRARY_PATH PROOT_TMP_DIR PROOT_NO_SECCOMP TMPDIR",
        "export HOME=/root USER=root LOGNAME=root SHELL=/bin/bash LANG=C.UTF-8 TERM=xterm-256color TMPDIR=/tmp",
        "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        // Biner Go (language_server) membaca bundel CA dari sini bila ada.
        "if [ -f /etc/ssl/certs/ca-certificates.crt ]; then export SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt; fi"
    ).joinToString("\n") + "\n"

    /** Perintah proot lengkap yang menjalankan [guestCommand] di dalam Debian. */
    fun prootCommand(ctx: Context, guestCommand: List<String>): List<String> {
        val cmd = mutableListOf(
            prootBin(ctx).path,
            "-r", rootfsDir(ctx).path,
            "-0",
            // Android melarang hard link (link() -> EACCES) di penyimpanan aplikasi.
            // dpkg membuat var/lib/dpkg/status-old lewat link() => "error creating new backup
            // file ... Permission denied". --link2symlink meniru hard link dengan symlink.
            "--link2symlink",
            "--kill-on-exit", // jangan tinggalkan proses yatim (port tetap terpakai)
            "-w", "/root",
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "/proc/self/fd:/dev/fd",
            "-b", "/dev/urandom:/dev/random"
        )
        // /dev/shm (dibutuhkan Python multiprocessing, Chromium, dll.; tidak ada di Android).
        val shm = shmDir(ctx)
        shm.mkdirs()
        try { Os.chmod(shm.path, 0x3FF) } catch (_: Exception) { }
        cmd += listOf("-b", "${shm.path}:/dev/shm")
        // Berkas /proc yang diblokir Android (stat, loadavg, ...) diganti versi palsu.
        for ((guest, host) in fakeProcBinds(ctx)) cmd += listOf("-b", "${host.path}:$guest")
        // Ikat penyimpanan bersama bila ada (opsional, tanpa crash bila izin belum diberikan).
        // Cukup exists(): canRead() bernilai false sebelum izin diberikan sehingga bind
        // terlewat selamanya. Bind direktori yang belum bisa dibaca aman bagi proot.
        if (File("/sdcard").exists()) {
            cmd += listOf("-b", "/sdcard:/sdcard")
        }
        if (File("/storage").exists()) {
            cmd += listOf("-b", "/storage:/storage")
        }
        cmd += guestCommand
        return cmd
    }

    /**
     * Environment untuk proses proot.
     * CATATAN: LD_LIBRARY_PATH (host) dibutuhkan proot menemukan libtalloc;
     * skrip guest langsung meng-unset-nya agar tidak bocor ke proses guest.
     */
    fun prootEnv(ctx: Context): Map<String, String> {
        ensureRuntimeDirs(ctx)
        return buildEnv(ctx)
    }

    /**
     * proot mengekstrak loader-nya ke PROOT_TMP_DIR; bila direktori ini tidak ada,
     * muncul "can't chmod .../tmp/proot-XXXX: No such file" lalu execve gagal
     * ("Permission denied"). Selalu pastikan ada sebelum start.
     */
    fun ensureRuntimeDirs(ctx: Context) {
        for (d in listOf(tmpDir(ctx), logsDir(ctx))) {
            d.mkdirs()
            try { Os.chmod(d.path, 0x1C0) } catch (_: Exception) { } // 0700
        }
        for (rel in listOf("usr/bin/bash", "bin/bash")) {
            val b = File(rootfsDir(ctx), rel)
            if (b.isFile && !Files.isSymbolicLink(b.toPath())) {
                try { Os.chmod(b.path, 0x1ED) } catch (_: Exception) { } // 0755
            }
        }
        // /tmp di dalam rootfs juga harus ada & writable (1777).
        val gtmp = File(rootfsDir(ctx), "tmp")
        gtmp.mkdirs()
        try { Os.chmod(gtmp.path, 0x3FF) } catch (_: Exception) { } // 1777
    }

    /** Siapkan guest sebelum SETIAP proses proot (server maupun perintah sekali jalan). Idempoten. */
    fun prepareForRun(ctx: Context) {
        val root = rootfsDir(ctx)
        prepareGuest(ctx, root)
        syncNetworkFiles(ctx, root)
        ensureCaBundle(root)
        ensureRuntimeDirs(ctx)
    }

    private fun buildEnv(ctx: Context): Map<String, String> {
        val env = baseEnv(ctx).toMutableMap()
        val l = File(libDir(ctx), "proot-loader")
        if (l.exists()) env["PROOT_LOADER"] = l.path
        val l32 = File(libDir(ctx), "proot-loader32")
        if (l32.exists()) env["PROOT_LOADER_32"] = l32.path
        return env
    }

    private fun baseEnv(ctx: Context): Map<String, String> = mapOf(
        "HOME" to "/root",
        "TERM" to "xterm-256color",
        "LANG" to "C.UTF-8",
        "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "LD_LIBRARY_PATH" to libDir(ctx).path,
        "PROOT_TMP_DIR" to tmpDir(ctx).path,
        "TMPDIR" to tmpDir(ctx).path,
        "PROOT_NO_SECCOMP" to "1"
    )

    /** Hapus path apa adanya (symlink dihapus, bukan targetnya), lalu tulis ulang sebagai berkas biasa. */
    private fun replaceFile(f: File, text: String): Boolean = runCatching {
        f.parentFile?.mkdirs()
        Files.deleteIfExists(f.toPath())
        f.writeText(text)
        true
    }.getOrDefault(false)

    /** Tulis berkas hanya bila belum ada (jangan timpa perubahan pengguna). */
    private fun writeIfMissing(f: File, text: String) {
        runCatching {
            if (Files.exists(f.toPath(), LinkOption.NOFOLLOW_LINKS) && f.exists()) return
            replaceFile(f, text)
        }
    }

    /**
     * Sinkronkan jaringan Android -> rootfs. Dipanggil sebelum tiap start.
     *
     * BUG LAMA: /etc/resolv.conf di image Debian adalah symlink (dangling ke
     * /run/systemd/resolve/...). File.exists() false untuk symlink rusak -> tidak dihapus,
     * writeText() menulis lewat symlink ke direktori yang tidak ada -> gagal diam-diam
     * (runCatching) -> resolv.conf "hilang". Sekarang symlink dihapus dulu.
     */
    fun syncNetworkFiles(ctx: Context, rootfs: File) {
        val etc = File(rootfs, "etc")
        etc.mkdirs()

        val host = "antigravity-ide"
        replaceFile(File(etc, "hostname"), "$host\n")
        replaceFile(
            File(etc, "hosts"),
            "127.0.0.1 localhost localhost.localdomain $host\n" +
                "::1 localhost ip6-localhost ip6-loopback\n"
        )

        val dns = mutableListOf<String>()
        runCatching {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            // Jaringan aktif dulu, lalu sisanya.
            val nets = listOfNotNull(cm.activeNetwork) + cm.allNetworks.toList()
            for (network in nets) {
                val lp = cm.getLinkProperties(network) ?: continue
                for (addr in lp.dnsServers) {
                    val a = addr.hostAddress ?: continue
                    if (a.contains('%')) continue // IPv6 link-local ber-scope tak valid di resolv.conf
                    if (a !in dns) dns.add(a)
                }
            }
        }
        // Selalu sertakan DNS publik sebagai cadangan (DNS Android bisa tak terjangkau dari proot).
        for (fb in listOf("1.1.1.1", "8.8.8.8")) if (fb !in dns) dns.add(fb)
        val resolvText = dns.take(5).joinToString("") { "nameserver $it\n" } +
            "options timeout:2 attempts:2\n"
        if (!replaceFile(File(etc, "resolv.conf"), resolvText)) {
            // Jalur cadangan: tulis lewat shell-less fallback ke /etc/resolv.conf.agytmp lalu rename.
            runCatching {
                val tmp = File(etc, "resolv.conf.agytmp")
                tmp.writeText(resolvText)
                Files.move(tmp.toPath(), File(etc, "resolv.conf").toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        }

        runCatching {
            val mtab = File(etc, "mtab")
            if (!Files.exists(mtab.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                Files.createSymbolicLink(mtab.toPath(), Paths.get("/proc/mounts"))
            }
        }
    }

    /**
     * Siapkan isi guest agar apt/dpkg/terminal berjalan tanpa tweak manual (idempoten).
     */
    fun prepareGuest(ctx: Context, rootfs: File) {
        // Titik mount & direktori standar yang kadang tidak ada di tarball.
        for (d in listOf("dev", "dev/shm", "proc", "sys", "run", "run/lock", "var/tmp", "var/lib/dpkg",
            "var/cache/apt/archives/partial", "var/lib/apt/lists/partial", "root", "sdcard", "storage",
            "etc/apt/apt.conf.d", "etc/dpkg/dpkg.cfg.d", "etc/profile.d", "opt", "usr/local/bin",
            "root/workspace", "root/.gemini", "root/.agy")) {
            runCatching { File(rootfs, d).mkdirs() }
        }
        runCatching { Os.chmod(File(rootfs, "var/tmp").path, 0x3FF) } // 1777
        runCatching { Os.chmod(File(rootfs, "dev/shm").path, 0x3FF) }

        // apt: sandbox user _apt butuh setgroups/seteuid yang tidak ada di proot -> jalan sebagai root.
        replaceFile(
            File(rootfs, "etc/apt/apt.conf.d/99agy"),
            "APT::Sandbox::User \"root\";\nAcquire::Retries \"3\";\n"
        )
        // dpkg: tanpa fsync berlebihan (lebih cepat & aman di filesystem Android).
        replaceFile(
            File(rootfs, "etc/dpkg/dpkg.cfg.d/99agy"),
            "force-unsafe-io\nno-debsig\n"
        )
        // Login shell (terminal): variabel dasar.
        replaceFile(
            File(rootfs, "etc/profile.d/99-agy.sh"),
            "export SHELL=/bin/bash\n"
        )
        // Pastikan akun root ada (proot -0 butuh entri untuk nama pengguna & HOME).
        writeIfMissing(File(rootfs, "etc/passwd"),
            "root:x:0:0:root:/root:/bin/bash\nnobody:x:65534:65534:nobody:/nonexistent:/usr/sbin/nologin\n")
        writeIfMissing(File(rootfs, "etc/group"), "root:x:0:\nnogroup:x:65534:\n")
        writeIfMissing(File(rootfs, "root/.bashrc"),
            "# Antigravity IDE\nalias ll='ls -alF'\n")
    }

    /**
     * Biner Go (language_server, agy-server) butuh bundel CA untuk TLS ke Google.
     * Image Debian minimal tidak selalu membawa paket ca-certificates, jadi bila
     * /etc/ssl/certs/ca-certificates.crt belum ada, dirakit dari penyimpanan CA sistem Android.
     * Tidak menimpa bundel yang sudah ada (mis. dipasang lewat apt).
     */
    fun ensureCaBundle(rootfs: File) {
        try {
            val target = File(rootfs, "etc/ssl/certs/ca-certificates.crt")
            if (target.isFile && target.length() > 1024) return

            val dirs = listOf("/apex/com.android.conscrypt/cacerts", "/system/etc/security/cacerts")
            val files = dirs.asSequence()
                .map { File(it).listFiles()?.filter { f -> f.isFile }.orEmpty() }
                .firstOrNull { it.isNotEmpty() }
                ?.sortedBy { it.name } ?: return

            val begin = "-----BEGIN CERTIFICATE-----"
            val end = "-----END CERTIFICATE-----"
            val sb = StringBuilder()
            for (f in files) {
                val t = runCatching { f.readText() }.getOrNull() ?: continue
                val b = t.indexOf(begin)
                val e = t.indexOf(end)
                if (b >= 0 && e > b) sb.append(t, b, e + end.length).append('\n')
            }
            if (sb.length > 1024) replaceFile(target, sb.toString())
        } catch (_: Exception) {
            // best effort: tanpa bundel, TLS di guest gagal tetapi aplikasi tetap jalan.
        }
    }

    /** PID semua proses milik aplikasi ini yang terkait proot/guest (dipilih lewat /proc). */
    fun guestPids(ctx: Context): List<Int> {
        val out = mutableListOf<Int>()
        val procs = File("/proc").listFiles { f -> Regex("^\\d+$").matches(f.name) } ?: return out
        val rootfs = rootfsDir(ctx).path
        val prootBin = prootBin(ctx).path
        val tmp = tmpDir(ctx).path
        val lib = libDir(ctx).path
        val me = android.os.Process.myPid()
        for (p in procs) {
            val pid = p.name.toIntOrNull() ?: continue
            if (pid == me) continue
            val exe = runCatching {
                Files.readSymbolicLink(Paths.get("/proc/$pid/exe")).toString()
            }.getOrNull()
            // proot memuat loader dari tmp/ atau lib/, jadi exe tracee bisa menunjuk ke sana.
            val exeMatch = exe != null &&
                (exe.startsWith(rootfs) || exe == prootBin || exe.startsWith(tmp) || exe.startsWith(lib))
            val cmdMatch = runCatching {
                String(File(p, "cmdline").readBytes()).replace('\u0000', ' ')
            }.getOrNull()?.contains(rootfs) == true
            if (exeMatch || cmdMatch) out.add(pid)
        }
        return out
    }

    /** Bunuh (SIGKILL) seluruh proses proot & guest milik aplikasi. */
    fun killGuest(ctx: Context) {
        guestPids(ctx).forEach { pid -> runCatching { Os.kill(pid, 9) } }
    }

    /**
     * Android memblokir sebagian /proc (stat, loadavg, vmstat, ... -> EACCES) sehingga
     * free/top/uptime/ps dan beberapa alat Node gagal. Ganti dengan berkas palsu hanya bila
     * aslinya tidak bisa dibaca. Mengembalikan pasangan (path guest, berkas host).
     */
    fun fakeProcBinds(ctx: Context): List<Pair<String, File>> {
        val dir = fakeProcDir(ctx)
        dir.mkdirs()
        fun readable(p: String) = runCatching { File(p).inputStream().use { it.read() }; true }.getOrDefault(false)
        val up = android.os.SystemClock.elapsedRealtime() / 1000
        val boot = System.currentTimeMillis() / 1000 - up
        val cpus = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val statText = buildString {
            append("cpu  ${up * 50} 0 ${up * 20} ${up * 400} 0 0 0 0 0 0\n")
            for (i in 0 until cpus) append("cpu$i ${up * 50 / cpus} 0 ${up * 20 / cpus} ${up * 400 / cpus} 0 0 0 0 0 0\n")
            append("intr 0\nctxt 0\nbtime $boot\nprocesses 1\nprocs_running 1\nprocs_blocked 0\n")
        }
        val defs = listOf(
            Triple("/proc/stat", "stat", statText),
            Triple("/proc/loadavg", "loadavg", "0.10 0.10 0.10 1/100 1\n"),
            Triple("/proc/uptime", "uptime", "$up.00 ${up * cpus}.00\n"),
            Triple("/proc/version", "version",
                "Linux version 6.2.1-agy (proot@android) (gcc 12.2.0) #1 SMP PREEMPT\n"),
            Triple("/proc/vmstat", "vmstat", "nr_free_pages 100000\nnr_inactive_anon 0\nnr_active_anon 0\n"),
            Triple("/proc/sys/kernel/cap_last_cap", "cap_last_cap", "40\n")
        )
        val out = mutableListOf<Pair<String, File>>()
        for ((guest, name, text) in defs) {
            if (readable(guest)) continue
            val f = File(dir, name)
            runCatching { f.writeText(text) }
            if (f.exists()) out.add(guest to f)
        }
        return out
    }
}
