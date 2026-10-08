package com.antigravity.ide

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Spanned
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.inputmethod.InputMethodManager
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebSettings
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.materialswitch.MaterialSwitch
import android.content.ClipData
import android.content.ClipboardManager
import android.text.InputType
import android.widget.EditText
import com.antigravity.ide.agy.AgyServer
import com.antigravity.ide.agy.AgyService
import com.antigravity.ide.agy.ServerState
import com.antigravity.ide.core.Pins
import com.antigravity.ide.core.StateStore
import com.antigravity.ide.databinding.ActivityMainBinding
import com.antigravity.ide.install.Installer
import com.antigravity.ide.ui.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private enum class Mode { Setup, SetupFailed, Installing, Loading, Web }

    private lateinit var binding: ActivityMainBinding
    private var webView: WebView? = null
    private var mode = Mode.Setup
    private var installJob: Job? = null
    private var loadedOnce = false
    private var lastServerState: ServerState = ServerState.Idle

    // ---------------------------------------------------------- permission & picker

    private val requestNotifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Lanjutkan instalasi apa pun hasilnya (notifikasi hanya untuk status).
            checkDiskThenInstall()
        }

    private val pickRootfs =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) confirmManualInstall(rootfs = uri, bundle = null)
        }

    private val pickBundle =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) confirmManualInstall(rootfs = null, bundle = uri)
        }

    private val pickToken =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) importTokenFrom(uri)
        }

    private val fileChooser =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            filePathCallback?.onReceiveValue(uri?.let { arrayOf(it) })
            filePathCallback = null
        }

    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private var hintShown = false
    private var lastGestureAt = 0L
    private val gestureHandler = Handler(Looper.getMainLooper())
    private var pendingMenuRunnable: Runnable? = null

    // Popup in-app (login OAuth Google, window.open, link eksternal).
    private var popupRoot: LinearLayout? = null
    private var popupWeb: WebView? = null
    private var popupVisitedExternal = false

    // Input: keyboard virtual (4 jari), menu layar penuh (3 jari), tombol volume.
    private lateinit var keyPad: KeyPad
    private var macKeyboard: MacKeyboard? = null
    private var menuScreen: MenuScreen? = null

    // Layar instalasi.
    private class StepView(
        val title: TextView, val subtitle: TextView,
        val check: View, val spinner: View, val pending: View
    )
    private val stepViews = ArrayList<StepView>()
    private var maxPercent = 0
    private var installStarted = false
    private var storageGrantedAtStart = false

    private val storagePerms = arrayOf(
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE
    )

    private val requestStorage =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (!hasStoragePermission()) {
                Toast.makeText(this, R.string.storage_denied_short, Toast.LENGTH_LONG).show()
            } else if (!storageGrantedAtStart && AgyService.state.value is ServerState.Running) {
                // Grup penyimpanan diwarisi proot saat start -> mulai ulang agar /sdcard terbaca.
                AgyService.requestRestart(this)
            }
            afterStorageResolved()
        }

    private val allFilesAccess =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            afterStorageResolved()
        }

    // ------------------------------------------------------------------ onCreate

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences("ui", MODE_PRIVATE)
        keyPad = KeyPad({ popupWeb ?: webView }, { setKeyboardOpen(!keyboardVisible()) })
        keyPad.volumeKeys = prefs.getBoolean("volkeys", true)

        binding.textBrand.text = brandText()
        binding.textBrandLoading.text = brandText()
        binding.textVersion.text = "— v${versionName()} —"
        buildStepViews()
        binding.textInstallError.movementMethod = ScrollingMovementMethod()

        binding.buttonRetry.setOnClickListener { onInstallClicked() }
        binding.buttonLoadingAction.setOnClickListener { onLoadingAction() }

        applyKeepScreenOn(StateStore.read(this).keepScreenOn)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (menuScreen != null) { hideMenu(); return }
                val pw = popupWeb
                if (pw != null) {
                    if (pw.canGoBack()) pw.goBack() else closePopup()
                    return
                }
                if (keyboardVisible()) { setKeyboardOpen(false); return }
                val wv = webView
                if (mode == Mode.Web && wv != null && wv.canGoBack()) wv.goBack()
                else moveTaskToBack(false)
            }
        })

        storageGrantedAtStart = hasStoragePermission()
        observeServerState()
        observeLoadingDetails()
        binding.buttonLoadingLogs.setOnClickListener { showLogs() }
        initUi()
        requestStorageAtLaunch()
    }

    private fun initUi() {
        if (AgyServer.isInstalled(this)) {
            setMode(Mode.Loading)
            val st = AgyService.state.value
            if (st is ServerState.Idle || st is ServerState.Stopped) AgyService.start(this)
        } else {
            // Belum terpasang: langsung tampilkan layar instalasi (tanpa tombol konfirmasi).
            resetInstallUi()
            setMode(Mode.Installing)
        }
    }

    private fun setMode(m: Mode) {
        mode = m
        render()
    }

    private fun render() {
        binding.installContainer.visibility =
            if (mode == Mode.Setup || mode == Mode.SetupFailed || mode == Mode.Installing) View.VISIBLE else View.GONE
        binding.loadingContainer.visibility =
            if (mode == Mode.Loading) View.VISIBLE else View.GONE
        binding.webArea.visibility =
            if (mode == Mode.Web) View.VISIBLE else View.GONE
        binding.buttonRetry.visibility =
            if (mode == Mode.SetupFailed) View.VISIBLE else View.GONE
        binding.textInstallError.visibility =
            if (mode == Mode.SetupFailed && binding.textInstallError.text.isNotBlank()) View.VISIBLE else View.GONE
        applyChrome()
        if (mode == Mode.Web && !hintShown) {
            hintShown = true
            Toast.makeText(this, R.string.hint_gestures, Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------- layar penuh & tema

    /** Mode Web: layar penuh imersif (tanpa bar). Mode lain: bar sistem normal. */
    private fun applyChrome() {
        val ctrl = WindowCompat.getInsetsController(window, window.decorView)
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        ctrl.isAppearanceLightStatusBars = !night
        ctrl.isAppearanceLightNavigationBars = !night
        if (mode == Mode.Web) {
            ctrl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctrl.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            ctrl.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyChrome() // bar sistem bisa muncul lagi setelah dialog/keyboard
    }

    private fun brandText(): CharSequence {
        val t = SpannableString("Antigravity IDE")
        t.setSpan(StyleSpan(Typeface.BOLD), 0, 11, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        t.setSpan(
            ForegroundColorSpan(ContextCompat.getColor(this, R.color.text_secondary)),
            11, t.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return t
    }

    private fun versionName(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "1.0"

    // ------------------------------------------------- gestur multi-jari (3 / 4)

    /** 3 jari = menu layar penuh, 4 jari = buka/tutup keyboard. */
    private fun onMultiTouch(count: Int) {
        val now = SystemClock.uptimeMillis()
        if (now - lastGestureAt < 600) return
        when (count) {
            3 -> {
                val r = Runnable {
                    pendingMenuRunnable = null
                    lastGestureAt = SystemClock.uptimeMillis()
                    showMenu()
                }
                pendingMenuRunnable = r
                gestureHandler.postDelayed(r, 220) // beri waktu jari ke-4 (agar tak terbaca 3 jari)
            }
            4 -> {
                pendingMenuRunnable?.let { gestureHandler.removeCallbacks(it) }
                pendingMenuRunnable = null
                lastGestureAt = now
                setKeyboardOpen(!keyboardVisible())
            }
        }
    }

    // -------------------------------------------------------------- server state

    private fun observeServerState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AgyService.state.collect { st -> onServerState(st) }
            }
        }
    }

    /** Selama layar loading tampil: perbarui tahap + ekor log tiap detik. */
    private fun observeLoadingDetails() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    if (mode == Mode.Loading) {
                        binding.textLoadingStage.text = AgyService.stage.value
                        binding.textLoadingLog.text = AgyService.recentLogs().takeLast(9).joinToString("\n")
                    }
                    delay(1000)
                }
            }
        }
    }

    private fun onServerState(st: ServerState) {
        if (st is ServerState.Running) {
            enterWebIfNeeded()
        } else if (AgyServer.isInstalled(this) && mode != Mode.Installing) {
            if (mode == Mode.Web || mode == Mode.Loading) {
                setMode(Mode.Loading)
                updateLoadingText(st)
            }
        }
        lastServerState = st
    }

    private fun enterWebIfNeeded() {
        if (mode != Mode.Web) setMode(Mode.Web)
        val wv = webView ?: createWebView()
        when {
            !loadedOnce -> {
                loadedOnce = true
                wv.loadUrl(AgyServer.URL)
            }
            wv.url == null -> wv.loadUrl(AgyServer.URL)
            lastServerState !is ServerState.Running -> wv.reload()
        }
    }

    private fun updateLoadingText(st: ServerState) {
        when (st) {
            is ServerState.Starting -> {
                binding.textLoadingStatus.setText(R.string.status_starting)
                binding.buttonLoadingAction.visibility = View.GONE
            }
            is ServerState.Restarting -> {
                binding.textLoadingStatus.text =
                    getString(R.string.status_restarting_fmt, st.attempt)
                binding.buttonLoadingAction.visibility = View.GONE
            }
            is ServerState.Error -> {
                binding.textLoadingStatus.text = getString(R.string.status_error_fmt, st.message)
                binding.buttonLoadingAction.setText(R.string.action_retry_start)
                binding.buttonLoadingAction.visibility = View.VISIBLE
            }
            is ServerState.Stopped -> {
                binding.textLoadingStatus.setText(R.string.status_stopped)
                binding.buttonLoadingAction.setText(R.string.action_start)
                binding.buttonLoadingAction.visibility = View.VISIBLE
            }
            else -> {}
        }
    }

    private fun onLoadingAction() {
        when (AgyService.state.value) {
            is ServerState.Error, is ServerState.Restarting -> AgyService.requestRestart(this)
            is ServerState.Stopped -> AgyService.start(this)
            else -> {}
        }
    }

    // ------------------------------------------------------------------ install

    private fun onInstallClicked() {
        if (installJob?.isActive == true) return
        if (Build.VERSION.SDK_INT >= 33) {
            val perm = Manifest.permission.POST_NOTIFICATIONS
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                requestNotifPermission.launch(perm)
                return
            }
        }
        checkDiskThenInstall()
    }

    private fun checkDiskThenInstall() {
        val avail = StatFs(filesDir.path).availableBytes
        if (avail < 1_000_000_000L) {
            AlertDialog.Builder(this)
                .setTitle(R.string.low_disk_title)
                .setMessage(getString(R.string.low_disk_msg_fmt, fmtSize(avail)))
                .setPositiveButton(R.string.continue_anyway) { _, _ -> beginInstall(null, null) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            beginInstall(null, null)
        }
    }

    private fun confirmManualInstall(rootfs: Uri?, bundle: Uri?) {
        AlertDialog.Builder(this)
            .setTitle(R.string.manual_install_title)
            .setMessage(R.string.manual_install_msg)
            .setPositiveButton(R.string.install_now) { _, _ -> beginInstall(rootfs, bundle) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun beginInstall(rootfs: Uri?, bundle: Uri?) {
        if (installJob?.isActive == true) return
        resetInstallUi()
        setMode(Mode.Installing)
        showInstallProgress(Installer.Progress(getString(R.string.preparing_install), phase = 0))

        installJob = lifecycleScope.launch {
            try {
                val result = Installer(this@MainActivity).install(
                    onProgress = { p ->
                        withContext(Dispatchers.Main) { showInstallProgress(p) }
                    },
                    manualRootfs = rootfs,
                    manualBundle = bundle
                )
                withContext(Dispatchers.Main) {
                    finishInstallUi()
                    applyKeepScreenOn(result.keepScreenOn)
                    setMode(Mode.Loading)
                    AgyService.start(this@MainActivity)
                }
            } catch (e: CancellationException) {
                withContext(Dispatchers.Main) { setMode(Mode.Setup) }
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.textInstallError.text = getString(
                        R.string.install_failed_fmt,
                        e.message ?: e.javaClass.simpleName
                    )
                    setMode(Mode.SetupFailed)
                }
            }
        }
    }

    private fun buildStepViews() {
        stepViews.clear()
        binding.stepsList.removeAllViews()
        val defs = listOf(
            Pair(R.string.step_proot, R.drawable.ic_step_proot),
            Pair(R.string.step_debian, R.drawable.ic_step_debian),
            Pair(R.string.step_server, R.drawable.ic_step_code),
            Pair(R.string.step_final, R.drawable.ic_step_final)
        )
        for ((i, d) in defs.withIndex()) {
            if (i > 0) {
                binding.stepsList.addView(View(this).apply {
                    setBackgroundColor(ContextCompat.getColor(context, R.color.card_stroke))
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
                    marginStart = dp(80)
                    marginEnd = dp(16)
                })
            }
            val item = layoutInflater.inflate(R.layout.item_step, binding.stepsList, false)
            item.findViewById<android.widget.ImageView>(R.id.stepIcon).setImageResource(d.second)
            val title = item.findViewById<TextView>(R.id.stepTitle).also { it.setText(d.first) }
            stepViews.add(
                StepView(
                    title,
                    item.findViewById(R.id.stepSubtitle),
                    item.findViewById(R.id.stepCheck),
                    item.findViewById(R.id.stepSpinner),
                    item.findViewById(R.id.stepPending)
                )
            )
            binding.stepsList.addView(item)
        }
        resetInstallUi()
    }

    private fun setStep(i: Int, state: Int, subtitle: String) { // 0 menunggu, 1 aktif, 2 selesai
        val v = stepViews.getOrNull(i) ?: return
        v.subtitle.text = subtitle
        v.check.visibility = if (state == 2) View.VISIBLE else View.GONE
        v.spinner.visibility = if (state == 1) View.VISIBLE else View.GONE
        v.pending.visibility = if (state == 0) View.VISIBLE else View.GONE
    }

    private fun doneLabel(i: Int): String = when (i) {
        0 -> "proot (Termux)"
        1 -> "Debian ${Pins.DEBIAN_RELEASE}"
        2 -> "Antigravity" + StateStore.read(this).ideVersion.let { if (it.isEmpty()) "" else " $it" }
        else -> getString(R.string.step_done)
    }

    private fun resetInstallUi() {
        maxPercent = 0
        if (stepViews.isEmpty()) return
        for (i in stepViews.indices) setStep(i, 0, getString(R.string.step_waiting))
        binding.progressInstall.isIndeterminate = false
        binding.progressInstall.progress = 0
        binding.textPercent.text = "0%"
        binding.textInstallStatus.setText(R.string.install_waiting)
    }

    private fun finishInstallUi() {
        for (i in stepViews.indices) setStep(i, 2, doneLabel(i))
        binding.progressInstall.setProgressCompat(100, true)
        binding.textPercent.text = "100%"
    }

    private fun showInstallProgress(p: Installer.Progress) {
        val phase = p.phase.coerceIn(0, stepViews.size - 1)
        val f = if (p.total > 0) (p.read.toDouble() / p.total).coerceIn(0.0, 1.0) else 0.0
        val sub = when {
            p.phaseFraction >= 0.0 -> p.phaseFraction.coerceIn(0.0, 1.0)
            p.step.startsWith("Mengekstrak") -> 0.5 + 0.5 * f
            p.total > 0 -> 0.5 * f
            else -> 0.0
        }
        val (lo, hi) = when (phase) {
            0 -> 0 to 5
            1 -> 5 to 45
            2 -> 45 to 95
            else -> 95 to 100
        }
        val pct = maxOf((lo + (hi - lo) * sub).toInt(), maxPercent)
        maxPercent = pct
        binding.progressInstall.isIndeterminate = false
        binding.progressInstall.setProgressCompat(pct, true)
        binding.textPercent.text = "$pct%"
        binding.textInstallStatus.text = p.step

        val detail = when {
            p.total > 0 -> buildString {
                append("${fmtSize(p.read)} / ${fmtSize(p.total)}")
                if (p.speedBps > 0) append("  ·  ${fmtSize(p.speedBps)}/dtk")
            }
            p.read > 0 -> fmtSize(p.read)
            p.detail.isNotBlank() -> p.detail
            else -> p.step
        }
        for (i in stepViews.indices) {
            when {
                i < phase -> setStep(i, 2, doneLabel(i))
                i == phase -> setStep(i, 1, detail)
                else -> setStep(i, 0, getString(R.string.step_waiting))
            }
        }
    }

    // ------------------------------------------------------------------- WebView

    private fun createWebView(): WebView {
        destroyWebView()
        val wv = AgyWebView(this).also { it.keyPad = keyPad }
        wv.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        wv.setBackgroundColor(0xFF0F1115.toInt())
        configureWebView(wv)
        wv.suppressIme = keyboardVisible()
        wv.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_POINTER_DOWN &&
                (ev.pointerCount == 3 || ev.pointerCount == 4)
            ) onMultiTouch(ev.pointerCount)
            false // tetap teruskan sentuhan ke WebView
        }
        binding.webContainer.addView(wv, 0)
        webView = wv
        return wv
    }

    private fun destroyWebView() {
        closePopup()
        val wv = webView ?: return
        runCatching { (wv.parent as? ViewGroup)?.removeView(wv) }
        runCatching { wv.destroy() }
        webView = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(wv: WebView) {
        val s = wv.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.textZoom = 100
        s.useWideViewPort = true
        s.loadWithOverviewMode = false
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        // Login OAuth Google: butuh window.open/popup + cookie pihak ketiga.
        s.setSupportMultipleWindows(true)
        s.javaScriptCanOpenWindowsAutomatically = true
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(wv, true)
        }

        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                applyUiScale(view)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val u = request.url
                val scheme = u.scheme ?: ""
                return when {
                    scheme != "http" && scheme != "https" -> { handleExternalScheme(u); true }
                    isLocalHost(u.host) -> false
                    else -> { openInPopup(u.toString()); true } // tetap di dalam aplikasi
                }
            }

            override fun onRenderProcessGone(
                view: WebView,
                detail: RenderProcessGoneDetail
            ): Boolean {
                Log.w("MainActivity", "proses render WebView mati — membangun ulang")
                runOnUiThread {
                    loadedOnce = false
                    createWebView()
                    if (AgyService.state.value is ServerState.Running) enterWebIfNeeded()
                }
                return true
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message
            ): Boolean {
                val popup = createPopup()
                val transport = resultMsg.obj as WebView.WebViewTransport
                transport.webView = popup
                resultMsg.sendToTarget()
                return true
            }

            override fun onCloseWindow(window: WebView) { window.post { closePopup() } }

            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                val accept = params.acceptTypes.firstOrNull { it.isNotBlank() } ?: "*/*"
                runCatching { fileChooser.launch(accept) }
                    .onFailure {
                        filePathCallback?.onReceiveValue(null)
                        filePathCallback = null
                    }
                return true
            }
        }

        wv.setDownloadListener { url, _, contentDisposition, mimetype, _ ->
            handleDownload(url, contentDisposition, mimetype)
        }
    }

    /**
     * Unduh berkas dari server lokal. Sengaja TIDAK memakai DownloadManager
     * sistem karena layanan itu berjalan di proses lain yang tidak bisa
     * mengakses 127.0.0.1 milik aplikasi ini.
     */
    private fun handleDownload(url: String, contentDisposition: String?, mimetype: String?) {
        val name = URLUtil.guessFileName(url, contentDisposition, mimetype)
        Toast.makeText(this, getString(R.string.downloading_fmt, name), Toast.LENGTH_SHORT).show()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val dir = downloadDir()
                dir.mkdirs()
                val f = File(dir, name)
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 60_000
                try {
                    if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
                    FileOutputStream(f).use { out ->
                        conn.inputStream.use { it.copyTo(out) }
                    }
                } finally {
                    conn.disconnect()
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.saved_fmt, f.absolutePath),
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.download_failed_fmt, e.message),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }


    // ----------------------------------------------- keyboard virtual & menu layar penuh

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val c = event.keyCode
        if ((c == KeyEvent.KEYCODE_VOLUME_DOWN || c == KeyEvent.KEYCODE_VOLUME_UP) &&
            mode == Mode.Web && ::keyPad.isInitialized && menuScreen == null &&
            keyPad.onVolume(c, event.action == KeyEvent.ACTION_DOWN, event.repeatCount)
        ) return true
        return super.dispatchKeyEvent(event)
    }

    private fun keyboardVisible() = macKeyboard != null

    private fun setKeyboardOpen(open: Boolean) {
        if (open == keyboardVisible() || mode != Mode.Web) return
        val cont = binding.keyboardContainer
        if (open) {
            val dm = resources.displayMetrics
            val kb = MacKeyboard(this, keyPad, dm.widthPixels, (dm.heightPixels * 0.5f).toInt())
            val h = kb.targetHeight()
            cont.removeAllViews()
            cont.addView(kb, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h))
            cont.visibility = View.VISIBLE
            macKeyboard = kb
            kb.translationY = h.toFloat()
            kb.animate().translationY(0f).setDuration(200).start()
            (webView as? AgyWebView)?.let { w ->
                w.suppressIme = true
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(w.windowToken, 0)
            }
        } else {
            val kb = macKeyboard ?: return
            macKeyboard = null
            (webView as? AgyWebView)?.suppressIme = false
            kb.animate().translationY(kb.height.toFloat()).setDuration(160).withEndAction {
                cont.removeAllViews()
                cont.visibility = View.GONE
            }.start()
        }
    }

    private fun uiPrefs() = getSharedPreferences("ui", MODE_PRIVATE)

    /**
     * Ukuran tampilan: textZoom WebView. Sengaja TIDAK menulis ulang meta viewport — UI mobile
     * hasil patch agy-server mengatur viewport/safe-area sendiri.
     */
    private fun applyUiScale(wv: WebView? = webView) {
        val v = wv ?: return
        v.settings.textZoom = uiPrefs().getInt("scale", 100).coerceIn(50, 200)
    }

    private val menuHost = object : MenuHost {
        override fun restartServer() {
            AgyService.requestRestart(this@MainActivity)
            Toast.makeText(this@MainActivity, R.string.restarting_server, Toast.LENGTH_SHORT).show()
        }

        override fun stopServer() { AgyService.requestStop(this@MainActivity) }

        override var keyboardOpen: Boolean
            get() = keyboardVisible()
            set(v) { setKeyboardOpen(v) }

        override var volumeKeysOn: Boolean
            get() = keyPad.volumeKeys
            set(v) {
                keyPad.volumeKeys = v
                uiPrefs().edit().putBoolean("volkeys", v).apply()
            }

        override var uiScalePercent: Int
            get() = uiPrefs().getInt("scale", 100)
            set(v) {
                uiPrefs().edit().putInt("scale", v).apply()
                applyUiScale()
            }

        override var keepScreenOn: Boolean
            get() = StateStore.read(this@MainActivity).keepScreenOn
            set(v) {
                StateStore.write(this@MainActivity, StateStore.read(this@MainActivity).copy(keepScreenOn = v))
                applyKeepScreenOn(v)
            }

        override fun openSettings() { showSettings() }
        override fun openLogs() { showLogs() }
        override fun openAbout() { showAbout() }
    }

    private fun showMenu() {
        if (menuScreen != null || mode != Mode.Web) return
        val m = MenuScreen(this, menuHost) { hideMenu() }
        binding.root.addView(m.view, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        menuScreen = m
        (webView as? AgyWebView)?.let {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(it.windowToken, 0)
        }
    }

    private fun hideMenu() {
        val m = menuScreen ?: return
        menuScreen = null
        runCatching { binding.root.removeView(m.view) }
    }

    // -------------------------------------------------------- popup / login OAuth

    private fun isLocalHost(host: String?) = host == "127.0.0.1" || host == "localhost"

    /** UA mirip Chrome: Google menolak login pada UA WebView ("; wv" / "Version/4.0"). */
    private fun chromeUserAgent(): String =
        WebSettings.getDefaultUserAgent(this)
            .replace("; wv)", ")")
            .replace(Regex("Version/\\d+\\.\\d+\\s"), "")

    private fun handleExternalScheme(u: Uri) {
        when (u.scheme) {
            "intent" -> runCatching {
                val i = Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    component = null
                    selector = null
                }
                startActivity(i)
            }
            "about", "javascript", "data", "blob" -> { /* tidak didukung */ }
            else -> runCatching { startActivity(Intent(Intent.ACTION_VIEW, u)) }
        }
    }

    private fun openInPopup(url: String) {
        createPopup().loadUrl(url)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createPopup(): WebView {
        closePopup()
        popupVisitedExternal = false
        val dp = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0F1115.toInt())
            isClickable = true
            elevation = 16 * dp
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val title = TextView(this).apply {
            setTextColor(0xFFE6E6E6.toInt())
            textSize = 12f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            text = "…"
        }
        val pw = WebView(this)
        val btnBrowser = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            setText(R.string.popup_browser)
            setOnClickListener {
                val u = pw.url
                if (!u.isNullOrBlank()) runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)))
                }
            }
        }
        val btnClose = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            setText(R.string.popup_close)
            setOnClickListener { closePopup() }
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF1B1E26.toInt())
            setPadding((12 * dp).toInt(), 0, (4 * dp).toInt(), 0)
            addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(btnBrowser)
            addView(btnClose)
        }
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (44 * dp).toInt()))

        pw.setBackgroundColor(0xFF0F1115.toInt())
        pw.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            userAgentString = chromeUserAgent()
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(pw, true)
        pw.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val sch = request.url.scheme ?: ""
                if (sch == "http" || sch == "https") return false
                handleExternalScheme(request.url)
                return true
            }

            override fun onPageFinished(view: WebView, url: String?) {
                title.text = url ?: ""
                val host = runCatching { Uri.parse(url).host }.getOrNull()
                if (host == null) return
                if (!isLocalHost(host)) {
                    popupVisitedExternal = true
                } else if (popupVisitedExternal) {
                    // Callback OAuth sudah mendarat di server lokal -> tutup popup.
                    view.postDelayed({ if (popupWeb === view) closePopup() }, 1500)
                }
            }
        }
        pw.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message
            ): Boolean {
                val next = createPopup()
                (resultMsg.obj as WebView.WebViewTransport).webView = next
                resultMsg.sendToTarget()
                return true
            }

            override fun onCloseWindow(window: WebView) { window.post { closePopup() } }
        }
        pw.setDownloadListener { url, _, cd, mime, _ -> handleDownload(url, cd, mime) }
        root.addView(pw, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        binding.webContainer.addView(root)
        popupRoot = root
        popupWeb = pw
        return pw
    }

    private fun closePopup() {
        val root = popupRoot
        val pw = popupWeb
        popupRoot = null
        popupWeb = null
        runCatching { (root?.parent as? ViewGroup)?.removeView(root) }
        runCatching { pw?.stopLoading(); pw?.destroy() }
        // Beri tahu halaman utama bahwa login mungkin selesai (cookie/localStorage dibagi).
        runCatching { CookieManager.getInstance().flush() }
    }

    // ---------------------------------------------------------- akses penyimpanan

    private fun hasStoragePermission() = storagePerms.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    /** Benar-benar bisa membaca /sdcard/Download (cek nyata, bukan sekadar izin). */
    private fun storageReadable(): Boolean = runCatching {
        File("/sdcard/Download").list() != null
    }.getOrDefault(false)

    /** Saat aplikasi dibuka: langsung minta izin penyimpanan (tanpa dialog penjelasan). */
    private fun requestStorageAtLaunch() {
        if (hasStoragePermission()) afterStorageResolved() else requestStorage.launch(storagePerms)
    }

    private fun afterStorageResolved() {
        // Android 11+: bila /sdcard masih tertutup, tawarkan "Akses semua berkas" (sekali saja).
        if (hasStoragePermission() && Build.VERSION.SDK_INT >= 30 &&
            !storageReadable() && !Environment.isExternalStorageManager() &&
            !uiPrefs().getBoolean("allfiles_asked", false)
        ) {
            uiPrefs().edit().putBoolean("allfiles_asked", true).apply()
            AlertDialog.Builder(this)
                .setTitle(R.string.storage_all_files_title)
                .setMessage(R.string.storage_all_files_msg)
                .setPositiveButton(R.string.open_settings) { _, _ ->
                    val i = Intent(
                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    runCatching { allFilesAccess.launch(i) }.onFailure { continueAfterStorage() }
                }
                .setNegativeButton(android.R.string.cancel) { _, _ -> continueAfterStorage() }
                .setOnCancelListener { continueAfterStorage() }
                .show()
            return
        }
        continueAfterStorage()
    }

    /** Instalasi berjalan otomatis setelah urusan izin selesai. */
    private fun continueAfterStorage() {
        if (!AgyServer.isInstalled(this) && !installStarted && installJob?.isActive != true) {
            installStarted = true
            onInstallClicked()
        }
    }

    private fun downloadDir(): File {
        val publicDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "AntigravityIDE"
        )
        return if (publicDir.canWrite()) publicDir
        else File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir, "unduhan")
    }

    // ------------------------------------------------------------------ dialogs

    private fun showSettings() {
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)
        val switchKeep = view.findViewById<MaterialSwitch>(R.id.switchKeepOn)
        val textPassword = view.findViewById<TextView>(R.id.textPassword)
        val textLan = view.findViewById<TextView>(R.id.textLanWarning)
        val btnCopy = view.findViewById<Button>(R.id.buttonCopyPassword)
        val btnChange = view.findViewById<Button>(R.id.buttonChangePassword)
        val btnToken = view.findViewById<Button>(R.id.buttonImportToken)
        val btnBundle = view.findViewById<Button>(R.id.buttonImportBundle)
        val btnRootfs = view.findViewById<Button>(R.id.buttonImportRootfs)
        val btnDoctor = view.findViewById<Button>(R.id.buttonDoctor)
        val btnWipe = view.findViewById<Button>(R.id.buttonWipe)
        val textVersions = view.findViewById<TextView>(R.id.textVersions)

        val state = StateStore.read(this)
        switchKeep.isChecked = state.keepScreenOn
        textPassword.text =
            if (state.password.isEmpty()) getString(R.string.password_pending)
            else getString(R.string.password_fmt, state.password)
        textLan.visibility = if (AgyService.lanExposed.value) View.VISIBLE else View.GONE
        textVersions.text = getString(
            R.string.versions_fmt,
            state.ideVersion.ifEmpty { "—" },
            state.rootfsSource.ifEmpty { "debian/${Pins.DEBIAN_RELEASE}" }
        )

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.settings)
            .setView(view)
            .setPositiveButton(android.R.string.ok, null)
            .show()

        switchKeep.setOnCheckedChangeListener { _, checked ->
            StateStore.update(this) { it.copy(keepScreenOn = checked) }
            applyKeepScreenOn(checked)
        }

        btnCopy.setOnClickListener {
            val pw = StateStore.read(this).password
            if (pw.isEmpty()) {
                Toast.makeText(this, R.string.password_pending, Toast.LENGTH_SHORT).show()
            } else {
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("password", pw))
                Toast.makeText(this, R.string.password_copied, Toast.LENGTH_SHORT).show()
            }
        }
        btnChange.setOnClickListener {
            dialog.dismiss()
            showChangePassword()
        }
        btnToken.setOnClickListener {
            dialog.dismiss()
            pickToken.launch("*/*")
        }
        btnBundle.setOnClickListener {
            dialog.dismiss()
            pickBundle.launch("*/*")
        }
        btnRootfs.setOnClickListener {
            dialog.dismiss()
            pickRootfs.launch("*/*")
        }
        btnDoctor.setOnClickListener {
            dialog.dismiss()
            showDoctor()
        }
        btnWipe.setOnClickListener {
            dialog.dismiss()
            confirmWipe()
        }
    }

    private fun showChangePassword() {
        if (!AgyServer.isInstalled(this)) return
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setText(StateStore.newPassword())
            setSelectAllOnFocus(true)
            typeface = Typeface.MONOSPACE
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.change_password)
            .setMessage(R.string.change_password_msg)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val pw = input.text.toString().trim()
                if (pw.length < 8 || pw.any { it.isWhitespace() }) {
                    Toast.makeText(this, R.string.password_invalid, Toast.LENGTH_LONG).show()
                } else {
                    applyNewPassword(pw)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun applyNewPassword(pw: String) {
        Toast.makeText(this, R.string.working, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch(Dispatchers.IO) {
            val res = AgyServer.changePassword(this@MainActivity, pw)
            withContext(Dispatchers.Main) {
                when (res) {
                    is AgyServer.PasswordResult.Ok -> {
                        Toast.makeText(this@MainActivity, R.string.password_changed, Toast.LENGTH_LONG).show()
                        AgyService.requestRestart(this@MainActivity)
                    }
                    is AgyServer.PasswordResult.Failed ->
                        Toast.makeText(this@MainActivity, res.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun importTokenFrom(uri: Uri) {
        if (!AgyServer.isInstalled(this)) return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IOException(getString(R.string.token_unreadable))
                AgyServer.importToken(this@MainActivity, bytes)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, R.string.token_imported, Toast.LENGTH_LONG).show()
                    AgyService.requestRestart(this@MainActivity)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.token_failed_fmt, e.message ?: e.javaClass.simpleName),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun showDoctor() {
        if (!AgyServer.isInstalled(this)) return
        Toast.makeText(this, R.string.working, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch(Dispatchers.IO) {
            val r = AgyServer.runDoctor(this@MainActivity)
            withContext(Dispatchers.Main) {
                val content = when {
                    r.timedOut -> getString(R.string.doctor_timeout)
                    r.output.isBlank() -> getString(R.string.doctor_empty_fmt, r.exitCode)
                    else -> r.output.trim()
                }
                val tv = TextView(this@MainActivity).apply {
                    typeface = Typeface.MONOSPACE
                    textSize = 11f
                    setTextIsSelectable(true)
                    this.text = content
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(R.string.doctor)
                    .setView(ScrollView(this@MainActivity).apply { addView(tv) })
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    private fun confirmWipe() {
        AlertDialog.Builder(this)
            .setTitle(R.string.wipe_title)
            .setMessage(R.string.wipe_msg)
            .setPositiveButton(R.string.wipe_yes) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    AgyService.requestStop(this@MainActivity)
                    delay(800)
                    StateStore.wipe(this@MainActivity)
                    withContext(Dispatchers.Main) { recreate() }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showLogs() {
        val logs = AgyService.recentLogs()
        val content = if (logs.isEmpty()) getString(R.string.no_logs)
        else logs.takeLast(200).joinToString("\n")
        val tv = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
            text = content
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val scroll = ScrollView(this).apply { addView(tv) }
        AlertDialog.Builder(this)
            .setTitle(R.string.logs)
            .setView(scroll)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showAbout() {
        AlertDialog.Builder(this)
            .setTitle(R.string.about)
            .setMessage(
                getString(
                    R.string.about_msg_fmt,
                    StateStore.read(this).ideVersion.ifEmpty { "—" },
                    Pins.DEBIAN_RELEASE
                )
            )
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // -------------------------------------------------------------------- util

    private fun applyKeepScreenOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun fmtSize(b: Long): String = when {
        b < 0 -> "—"
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", b / 1024.0)
        b < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", b / 1024.0 / 1024.0)
        else -> String.format(Locale.US, "%.2f GB", b / 1024.0 / 1024.0 / 1024.0)
    }

    override fun onPause() {
        // Pastikan cookie sesi login agy-server tersimpan ke disk.
        runCatching { CookieManager.getInstance().flush() }
        super.onPause()
    }

    override fun onDestroy() {
        destroyWebView()
        super.onDestroy()
    }
}
