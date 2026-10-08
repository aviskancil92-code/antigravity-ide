package com.antigravity.ide.agy

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.antigravity.ide.MainActivity
import com.antigravity.ide.R
import com.antigravity.ide.runtime.LinuxRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

sealed class ServerState {
    object Idle : ServerState()
    object Starting : ServerState()
    data class Running(val url: String) : ServerState()
    data class Restarting(val attempt: Int) : ServerState()
    data class Error(val message: String) : ServerState()
    object Stopped : ServerState()
}

/**
 * Foreground service yang menjalankan Debian (proot) + agy-server (+ language_server) di latar
 * belakang. Kebijakan restart otomatis dengan pembatasan badai-restart.
 *
 * Urutan tiap putaran: siapkan guest → provisioning (config + kata sandi, hanya bila belum)
 * → proot menjalankan start.sh → tunggu HTTP siap → pantau sampai proses berakhir.
 */
class AgyService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var serverProcess: Process? = null
    @Volatile private var stopRequested = false
    @Volatile private var loopActive = false
    @Volatile private var manualRestart = false
    @Volatile private var urlWatcherActive = false
    private var lsLogOffset = 0L
    private val lsPorts = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    private var diagDone = false
    private var liveDiagDone = false
    private var restartCount = 0
    private var lastRestartAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // Wajib panggil startForeground dulu bila masuk via startForegroundService.
                startForegroundCompat()
                stopRequested = true
                _state.value = ServerState.Stopped
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RESTART -> {
                startForegroundCompat()
                stopRequested = false
                manualRestart = true
                _state.value = ServerState.Starting
                scope.launch(Dispatchers.IO) {
                    killTree()
                    if (!loopActive) {
                        manualRestart = false
                        launchLoop()
                    }
                }
                return START_STICKY
            }
        }

        startForegroundCompat()

        if (!AgyServer.isInstalled(this)) {
            _state.value = ServerState.Error("Antigravity belum terpasang — buka aplikasi untuk memasang.")
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }

        startUrlWatcher()
        if (!loopActive) {
            launchLoop()
        }
        return START_STICKY
    }

    /**
     * Pantau URL yang dititipkan guest lewat shim xdg-open (/root/.agy/openurl/*.url) dan
     * teruskan ke UI untuk dibuka di peramban Android (login Google).
     */
    private fun startUrlWatcher() {
        if (urlWatcherActive) return
        urlWatcherActive = true
        scope.launch(Dispatchers.IO) {
            val dir = AgyServer.hostFile(LinuxRuntime.rootfsDir(this@AgyService), "/root/.agy/openurl")
            while (true) {
                delay(500)
                try {
                    val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".url") }
                        ?.sortedBy { it.name } ?: continue
                    for (f in files) {
                        val url = runCatching { f.readText().trim() }.getOrDefault("")
                        f.delete()
                        // Hanya http(s) — jangan teruskan skema lain ke sistem.
                        if (url.length in 8..4096 && Regex("^https?://\\S+$").matches(url)) {
                            logLine("[app] membuka URL login di peramban: " + url.substringBefore('?').take(120))
                            _openUrl.tryEmit(url)
                            if (_openUrl.subscriptionCount.value == 0) notifyOpenUrl(url)
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    /** Aplikasi tidak di layar: tawarkan lewat notifikasi agar login tetap bisa dilanjutkan. */
    private fun notifyOpenUrl(url: String) {
        val pi = PendingIntent.getActivity(
            this, 2, Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = baseBuilder()
            .setContentText("Ketuk untuk melanjutkan login Google")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setOngoing(false)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID + 1, n)
    }

    private fun launchLoop() {
        loopActive = true
        scope.launch(Dispatchers.IO) {
            try {
                runLoop()
            } finally {
                loopActive = false
            }
        }
    }

    private suspend fun runLoop() {
        val logFile = File(LinuxRuntime.logsDir(this), "server.log")
        logFile.parentFile?.mkdirs()

        while (!stopRequested) {
            _state.value = ServerState.Starting
            notify("Menyiapkan Linux & Antigravity…")

            try {
                // ---- 1) siapkan guest + provisioning (config & kata sandi) ----
                _stage.value = "1/3 Menyiapkan sistem Debian…"
                LinuxRuntime.prepareForRun(this)
                AgyServer.writeStartScript(this, LinuxRuntime.rootfsDir(this))
                _stage.value = "2/3 Konfigurasi awal agy-server…"
                logLine("[app] konfigurasi awal (agy-server config/passwd)")
                when (val prov = AgyServer.ensureProvisioned(this)) {
                    is AgyServer.Provision.Ready -> logLine("[app] konfigurasi siap")
                    is AgyServer.Provision.Failed -> {
                        logLine("[app] konfigurasi GAGAL: ${prov.message}")
                        _state.value = ServerState.Error(prov.message)
                        notify("Konfigurasi gagal — buka aplikasi untuk detail")
                        break
                    }
                }
                if (stopRequested) break

                // ---- 2) jalankan agy-server di dalam proot ----
                _stage.value = "3/3 Menjalankan Antigravity (bisa beberapa menit)…"
                logLine("[app] menjalankan proot → ${AgyServer.GUEST_START}")
                val cmd = LinuxRuntime.prootCommand(this, listOf("/bin/bash", AgyServer.GUEST_START))
                val pb = ProcessBuilder(cmd).apply {
                    environment().clear()
                    environment().putAll(LinuxRuntime.prootEnv(this@AgyService))
                    redirectErrorStream(true)
                    directory(filesDir)
                }
                // Mulai membaca log language_server dari ±4 KB terakhir (isi lama tidak relevan).
                lsPorts.clear()
                liveDiagDone = false
                lsLogOffset = runCatching {
                    maxOf(0L, AgyServer.hostFile(LinuxRuntime.rootfsDir(this), AgyServer.GUEST_LS_LOG).length() - 4096L)
                }.getOrDefault(0L)
                val proc = pb.start()
                serverProcess = proc

                // Selalu drain stdout/stderr -> mencegah deadlock buffer pipe.
                val drainer = Thread {
                    try {
                        BufferedReader(InputStreamReader(proc.inputStream)).useLines { lines ->
                            for (line in lines) {
                                logLine(line)
                                appendToFile(logFile, line)
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
                drainer.isDaemon = true
                drainer.start()

                val ready = waitForServer(proc)
                if (!ready && !stopRequested) {
                    val alive = proc.isAlive
                    _stage.value = "Mengumpulkan diagnostik…"
                    if (alive) {
                        logLine("[app] server tidak merespons di ${AgyServer.URL} setelah 300 dtk")
                        // Uji sambungan SELAGI language_server masih hidup.
                        if (!liveDiagDone) AgyServer.collectLiveDiagnostics(this, lsPorts.toList()).lines().forEach { logLine(it) }
                        _state.value = ServerState.Error(
                            "Server tidak merespons dalam 5 menit. Lihat log (tombol di bawah) untuk penyebabnya."
                        )
                        notify("Server tidak merespons — buka aplikasi untuk detail")
                        killTree()
                    }
                    if (!diagDone) {
                        diagDone = true
                        AgyServer.collectDiagnostics(this).lines().forEach { logLine(it) }
                    }
                    _stage.value = ""
                    if (alive) break
                }
                if (ready) {
                    _stage.value = ""
                    restartCount = 0
                    _state.value = ServerState.Running(AgyServer.URL)
                    notify("Antigravity aktif · 127.0.0.1:${AgyServer.PORT}")
                    scope.launch(Dispatchers.IO) { _lanExposed.value = AgyServer.isReachableFromLan() }
                }

                val exitCode = proc.waitFor()
                logLine("[app] proses server berhenti, kode keluar $exitCode")
                if (stopRequested) break

                if (manualRestart) {
                    // Permintaan restart dari pengguna: bukan "crash", jangan dihitung.
                    manualRestart = false
                    restartCount = 0
                    continue
                }

                val now = System.currentTimeMillis()
                if (now - lastRestartAt > 60_000) restartCount = 0
                lastRestartAt = now
                restartCount++

                if (restartCount > 5) {
                    _state.value = ServerState.Error(
                        "Server berhenti berulang kali (kode keluar $exitCode). Buka menu → Lihat log."
                    )
                    notify("Kesalahan server — buka aplikasi untuk detail")
                    break
                }

                _state.value = ServerState.Restarting(restartCount)
                notify("Server mati (kode $exitCode) — memulai ulang…")
                delay(2_000L)
            } catch (e: Exception) {
                if (stopRequested) break
                Log.e(TAG, "gagal menjalankan proot", e)
                _state.value = ServerState.Error("Gagal menjalankan proot: ${e.message}")
                notify("Kesalahan: ${e.message}")
                break
            }
        }

        killTree()
    }

    /**
     * Tunggu hingga HTTP 127.0.0.1:8765 merespons (maks 300 detik — language_server di bawah
     * proot bisa lambat saat start dingin). Bila batas lewat tetapi proses masih hidup dan port
     * sudah menerima koneksi, anggap siap: WebView akan mencoba lagi sendiri.
     */
    private fun waitForServer(proc: Process): Boolean {
        val startAt = System.currentTimeMillis()
        val deadline = startAt + 300_000
        while (System.currentTimeMillis() < deadline && !stopRequested) {
            pumpLsLog()
            // agy-server bisa menyerah sendiri (~100 dtk) sebelum batas kita: ambil diagnostik langsung
            // pada detik ke-40 selagi language_server masih hidup.
            if (!liveDiagDone && System.currentTimeMillis() - startAt > 40_000 && proc.isAlive) {
                liveDiagDone = true
                _stage.value = "Diagnostik jaringan…"
                AgyServer.collectLiveDiagnostics(this, lsPorts.toList()).lines().forEach { logLine(it) }
                _stage.value = "3/3 Menjalankan Antigravity (bisa beberapa menit)…"
            }
            if (!proc.isAlive) { pumpLsLog(); return false }
            if (probeHttp()) return true
            Thread.sleep(400)
        }
        return !stopRequested && proc.isAlive && probeTcp()
    }

    /** Salin baris baru dari language-server.log (di dalam guest) ke buffer log aplikasi. */
    private fun pumpLsLog() {
        try {
            val f = AgyServer.hostFile(LinuxRuntime.rootfsDir(this), AgyServer.GUEST_LS_LOG)
            if (!f.isFile) return
            val len = f.length()
            if (len < lsLogOffset) lsLogOffset = 0L
            if (len == lsLogOffset) return
            RandomAccessFile(f, "r").use { raf ->
                raf.seek(lsLogOffset)
                val n = minOf(len - lsLogOffset, 32_768L).toInt()
                val buf = ByteArray(n)
                raf.readFully(buf)
                lsLogOffset += n
                String(buf, Charsets.UTF_8).lines().filter { it.isNotBlank() }
                    .forEach { line ->
                        logLine("[ls] " + line.take(300))
                        Regex("listening on random port at (\\d+)").find(line)
                            ?.groupValues?.get(1)?.toIntOrNull()?.let { lsPorts.add(it) }
                    }
            }
        } catch (_: Exception) {
        }
    }

    private fun probeHttp(): Boolean {
        return try {
            val conn = URL(AgyServer.URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 800
            conn.readTimeout = 3_000
            conn.instanceFollowRedirects = false
            try {
                conn.responseCode in 100..599
            } catch (_: Exception) {
                false
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun probeTcp(): Boolean = try {
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", AgyServer.PORT), 800)
            true
        }
    } catch (_: Exception) {
        false
    }

    /**
     * Bunuh proot beserta seluruh proses guest (agy-server, language_server, terminal) —
     * dipilih lewat /proc berdasarkan path exe/cmdline di bawah rootfs, aman tanpa API pid().
     */
    private fun killTree() {
        val proc = serverProcess
        serverProcess = null
        val victims = LinuxRuntime.guestPids(this)
        proc?.let { runCatching { it.destroy() } }
        try { Thread.sleep(150) } catch (_: InterruptedException) { }
        victims.forEach { pid -> runCatching { android.system.Os.kill(pid, 9) } }
        LinuxRuntime.killGuest(this)
    }

    @Synchronized
    private fun appendToFile(f: File, line: String) {
        try {
            if (f.length() > 2_000_000) {
                val old = File(f.path + ".1")
                old.delete()
                f.renameTo(old)
            }
            f.appendText(line + "\n")
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------- notifikasi

    private fun baseBuilder(): NotificationCompat.Builder {
        val openPi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_terminal)
            .setContentTitle("Antigravity IDE")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openPi)
    }

    private fun startForegroundCompat() {
        val n = baseBuilder()
            .setContentText("Menyiapkan Linux…")
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (e: Exception) {
            // Android 12+ dapat menolak FGS dari latar belakang; lanjut sebagai service biasa.
            Log.w(TAG, "startForeground ditolak: ${e.message}")
        }
    }

    private fun notify(text: String) {
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, AgyService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n: Notification = baseBuilder()
            .setContentText(text)
            .addAction(NotificationCompat.Action.Builder(0, "Hentikan", stopPi).build())
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
    }

    private fun stopForegroundCompat() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
    }

    override fun onDestroy() {
        stopRequested = true
        Thread { killTree() }.start()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AgyService"
        const val CHANNEL_ID = "agy_server"
        private const val NOTIF_ID = 42

        const val ACTION_STOP = "com.antigravity.ide.action.STOP"
        const val ACTION_RESTART = "com.antigravity.ide.action.RESTART"

        private val _state = MutableStateFlow<ServerState>(ServerState.Idle)
        val state: StateFlow<ServerState> = _state

        /** true bila port server terjangkau dari jaringan (bukan hanya 127.0.0.1). */
        private val _lanExposed = MutableStateFlow(false)
        val lanExposed: StateFlow<Boolean> = _lanExposed

        private val logBuffer = ArrayDeque<String>()

        /** URL yang diminta guest untuk dibuka di peramban Android (login Google). */
        private val _openUrl = MutableSharedFlow<String>(extraBufferCapacity = 8)
        val openUrl: SharedFlow<String> = _openUrl

        /** Tahap yang sedang berjalan (untuk layar loading), mis. "Konfigurasi awal…". */
        private val _stage = MutableStateFlow("")
        val stage: StateFlow<String> = _stage

        /** Tambah baris ke buffer log (tampil di layar loading dan "Lihat log"). */
        fun logLine(line: String) {
            Log.i(TAG, line)
            synchronized(logBuffer) {
                logBuffer.addLast(line)
                if (logBuffer.size > 400) logBuffer.removeFirst()
            }
        }

        fun recentLogs(): List<String> = synchronized(logBuffer) { logBuffer.toList() }

        fun start(ctx: Context) {
            val i = Intent(ctx, AgyService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun requestRestart(ctx: Context) {
            val i = Intent(ctx, AgyService::class.java).setAction(ACTION_RESTART)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun requestStop(ctx: Context) {
            val i = Intent(ctx, AgyService::class.java).setAction(ACTION_STOP)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }
}
