package com.antigravity.ide.core

import kotlinx.coroutines.delay
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

class DownloadException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Utilitas jaringan sederhana: GET teks, unduh dengan resume + retry,
 * dan parsing indeks paket apt (format "Packages").
 */
object Net {

    private const val UA = "AntigravityIDE/1.0 (Android; installer)"

    /** GET teks dengan retry 3x. Panggil dari Dispatchers.IO. */
    fun getText(url: String, timeoutMs: Int = 20_000, userAgent: String = UA): String {
        var last: IOException? = null
        for (attempt in 1..3) {
            try {
                val conn = open(url, timeoutMs, userAgent)
                try {
                    val code = conn.responseCode
                    if (code !in 200..299) throw IOException("HTTP $code")
                    return conn.inputStream.bufferedReader().use { it.readText() }
                } finally {
                    conn.disconnect()
                }
            } catch (e: IOException) {
                last = e
            }
            if (attempt < 3) Thread.sleep(500L * attempt)
        }
        throw DownloadException("Gagal mengambil: ${last?.message}", last)
    }

    private fun open(url: String, timeoutMs: Int, userAgent: String = UA): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", userAgent)
        return conn
    }

    /**
     * Unduh berkas dengan dukungan resume (HTTP Range) dan retry dengan backoff.
     * [onProgress] dipanggil (terbatasi ±4x/detik) dengan
     * (byteTerunduh, total(-1 jika tidak diketahui), kecepatan Bps).
     */
    suspend fun download(
        url: String,
        dest: File,
        onProgress: suspend (read: Long, total: Long, speedBps: Long) -> Unit = { _, _, _ -> }
    ) {
        dest.parentFile?.mkdirs()
        var lastError: IOException? = null
        for (attempt in 1..5) {
            try {
                downloadOnce(url, dest, onProgress)
                if (dest.exists() && dest.length() > 0L) return
                throw IOException("berkas unduhan kosong")
            } catch (e: IOException) {
                lastError = e
            }
            if (attempt < 5) delay(1500L * attempt)
        }
        File(dest.path + ".part").delete()
        dest.delete()
        throw DownloadException("Gagal mengunduh setelah 5 percobaan: ${lastError?.message}", lastError)
    }

    private suspend fun downloadOnce(
        url: String,
        dest: File,
        onProgress: suspend (read: Long, total: Long, speedBps: Long) -> Unit
    ) {
        val tmp = File(dest.path + ".part")
        val existing = if (tmp.exists()) tmp.length() else 0L

        val conn = open(url, 30_000)
        try {
            if (existing > 0L) conn.setRequestProperty("Range", "bytes=$existing-")
            val code = conn.responseCode

            when {
                code == 416 -> {
                    // Range tidak terpenuhi — mulai ulang dari nol.
                    tmp.delete()
                    throw IOException("HTTP 416 (rentang tidak valid)")
                }
                code !in 200..299 -> throw IOException("HTTP $code untuk $url")
            }

            val appending = existing > 0L && code == 206
            if (!appending && tmp.exists()) tmp.delete()

            val contentLen = conn.contentLengthLong // -1 bila tidak diketahui
            val start = if (appending) existing else 0L
            val total = when {
                appending && contentLen >= 0 -> start + contentLen
                else -> contentLen
            }

            var read = start
            var lastTick = System.currentTimeMillis()
            var lastBytes = read
            var speed = 0L

            FileOutputStream(tmp, appending).use { out ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        val now = System.currentTimeMillis()
                        if (now - lastTick >= 250) {
                            speed = ((read - lastBytes) * 1000L) / (now - lastTick).coerceAtLeast(1)
                            lastTick = now
                            lastBytes = read
                            onProgress(read, total, speed)
                            coroutineContext.ensureActive()
                        }
                    }
                }
                out.fd.sync()
            }

            if (total >= 0 && read < total) throw IOException("koneksi terputus ($read/$total byte)")
            onProgress(read, total, speed)

            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) throw IOException("gagal memindahkan berkas unduhan")
        } finally {
            conn.disconnect()
        }
    }

    /** Parse indeks "Packages" apt: nama paket -> path berkas deb. */
    fun parsePackagesIndex(text: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        text.split("\n\n").forEach { rawStanza ->
            val stanza = rawStanza.trim()
            if (stanza.isEmpty()) return@forEach
            var pkg: String? = null
            var file: String? = null
            stanza.lines().forEach { line ->
                when {
                    line.startsWith("Package: ") -> pkg = line.removePrefix("Package: ").trim()
                    line.startsWith("Filename: ") -> file = line.removePrefix("Filename: ").trim()
                }
            }
            if (pkg != null && file != null) out[pkg!!] = file!!
        }
        return out
    }
}
