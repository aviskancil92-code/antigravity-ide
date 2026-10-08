package com.antigravity.ide.core

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.security.SecureRandom

/**
 * Status aplikasi yang dipersistenkan di files/linux/.state.json.
 * Berkas ini HANYA ditulis setelah instalasi selesai atomik, sehingga
 * keberadaannya == "instalasi valid".
 */
data class AppState(
    val installed: Boolean = false,
    /** Versi Antigravity (language_server) yang terpasang; kosong bila diimpor manual. */
    val ideVersion: String = "",
    val rootfsSource: String = "",
    /** Kata sandi akses web agy-server (selalu aktif — server diperlakukan seperti akses shell). */
    val password: String = "",
    /** true setelah `agy-server config` + `passwd` berhasil dijalankan di dalam guest. */
    val provisioned: Boolean = false,
    val keepScreenOn: Boolean = false,
    val createdAt: Long = 0L
)

object StateStore {

    private fun file(ctx: Context): File = File(linuxDir(ctx), ".state.json")

    fun linuxDir(ctx: Context): File = File(ctx.filesDir, "linux")

    fun read(ctx: Context): AppState {
        val f = file(ctx)
        if (!f.exists()) return AppState()
        return try {
            val o = JSONObject(f.readText())
            AppState(
                installed = o.optBoolean("installed", false),
                ideVersion = o.optString("ideVersion", ""),
                rootfsSource = o.optString("rootfsSource", ""),
                password = o.optString("password", ""),
                provisioned = o.optBoolean("provisioned", false),
                keepScreenOn = o.optBoolean("keepScreenOn", false),
                createdAt = o.optLong("createdAt", 0L)
            )
        } catch (_: Exception) {
            AppState()
        }
    }

    /** Tulis state ke direktori linux (atau staging saat instalasi). Atomik via rename. */
    fun writeTo(dir: File, state: AppState) {
        val f = File(dir, ".state.json")
        f.parentFile?.mkdirs()
        val o = JSONObject()
        o.put("installed", state.installed)
        o.put("ideVersion", state.ideVersion)
        o.put("rootfsSource", state.rootfsSource)
        o.put("password", state.password)
        o.put("provisioned", state.provisioned)
        o.put("keepScreenOn", state.keepScreenOn)
        o.put("createdAt", state.createdAt)
        val tmp = File(dir, ".state.json.tmp")
        tmp.writeText(o.toString())
        if (!tmp.renameTo(f)) {
            // rename gagal (jarang): tulis langsung.
            f.writeText(o.toString())
            tmp.delete()
        }
    }

    @Synchronized
    fun write(ctx: Context, state: AppState) = writeTo(linuxDir(ctx), state)

    /** Ubah state secara atomik terhadap penulis lain (UI & service). */
    @Synchronized
    fun update(ctx: Context, change: (AppState) -> AppState): AppState {
        val next = change(read(ctx))
        writeTo(linuxDir(ctx), next)
        return next
    }

    /** Hapus seluruh direktori linux (uninstall data). */
    fun wipe(ctx: Context) {
        deleteRecursive(linuxDir(ctx))
    }

    /** Kata sandi acak yang mudah diketik di ponsel (tanpa karakter mirip). */
    fun newPassword(): String {
        val alphabet = "abcdefghjkmnpqrstuvwxyz23456789".toCharArray()
        val rnd = SecureRandom()
        return buildString { repeat(14) { append(alphabet[rnd.nextInt(alphabet.size)]) } }
    }

    /** Hapus rekursif yang aman terhadap symlink (tidak mengikuti tautan). */
    fun deleteRecursive(f: File) {
        try {
            if (f.isDirectory && !Files.isSymbolicLink(f.toPath())) {
                f.listFiles()?.forEach { deleteRecursive(it) }
            }
            f.delete()
        } catch (_: Exception) {
            // abaikan — best effort
        }
    }
}
