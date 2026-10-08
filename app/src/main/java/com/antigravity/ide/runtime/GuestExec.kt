package com.antigravity.ide.runtime

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Menjalankan perintah SEKALI JALAN di dalam Debian (via proot) dan menangkap keluarannya.
 * Dipakai untuk provisioning (`agy-server config/passwd`), diagnostik, dan perintah lain.
 *
 * Argumen dikirim apa adanya (argv) — TIDAK lewat shell — sehingga aman dari masalah
 * quoting/injeksi, mis. kata sandi yang berisi karakter khusus.
 */
object GuestExec {

    data class Result(val exitCode: Int, val output: String, val timedOut: Boolean) {
        val ok: Boolean get() = !timedOut && exitCode == 0
    }

    private const val MAX_OUTPUT = 64 * 1024

    /** Panggil dari thread latar belakang (Dispatchers.IO). */
    fun run(
        ctx: Context,
        argv: List<String>,
        extraEnv: Map<String, String> = emptyMap(),
        timeoutMs: Long = 60_000L
    ): Result {
        require(argv.isNotEmpty()) { "argv kosong" }
        if (!LinuxRuntime.baseInstalled(ctx)) {
            return Result(-1, "Linux belum terpasang.", false)
        }
        LinuxRuntime.prepareForRun(ctx)

        // bash -c '<prelude> exec "$@"' guest [env K=V ...] <argv...>
        // -> $0 = "guest", "$@" = (env K=V ...) argv. Variabel tambahan lewat `env` di dalam guest
        // supaya tidak tertimpa oleh unset/export pada prelude.
        val guestCmd = buildList {
            add("/bin/bash")
            add("-c")
            add(LinuxRuntime.GUEST_ENV_PRELUDE + "exec \"\$@\"")
            add("guest")
            if (extraEnv.isNotEmpty()) {
                add("/usr/bin/env")
                for ((k, v) in extraEnv) add("$k=$v")
            }
            addAll(argv)
        }

        val pb = ProcessBuilder(LinuxRuntime.prootCommand(ctx, guestCmd)).apply {
            environment().clear()
            environment().putAll(LinuxRuntime.prootEnv(ctx))
            redirectErrorStream(true)
            directory(File(ctx.filesDir.path))
        }

        val proc = try {
            pb.start()
        } catch (e: Exception) {
            return Result(-1, "Gagal menjalankan proot: ${e.message}", false)
        }

        val sb = StringBuilder()
        val reader = Thread {
            try {
                proc.inputStream.bufferedReader().use { r ->
                    val buf = CharArray(4096)
                    while (true) {
                        val n = r.read(buf)
                        if (n < 0) break
                        synchronized(sb) {
                            if (sb.length < MAX_OUTPUT) sb.append(buf, 0, n)
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        reader.isDaemon = true
        reader.start()

        val finished = try {
            proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            false
        }
        if (!finished) {
            runCatching { proc.destroyForcibly() }
        }
        runCatching { reader.join(2_000) }

        val text = synchronized(sb) { sb.toString() }
        val code = if (finished) proc.exitValue() else -1
        return Result(code, text, !finished)
    }
}
