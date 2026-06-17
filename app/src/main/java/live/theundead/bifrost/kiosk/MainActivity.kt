package live.theundead.bifrost.kiosk

import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import androidx.core.content.ContextCompat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.View
import android.webkit.CookieManager
import java.util.concurrent.Executors
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import live.theundead.bifrost.kiosk.databinding.ActivityMainBinding
import live.theundead.bifrost.kiosk.voice.VoiceFeedback
import live.theundead.bifrost.kiosk.voice.VoiceService

/**
 * The kiosk surface: a full-screen immersive WebView pinned via lock-task.
 *
 * Lock-task can only be entered by the foreground app itself — that single fact
 * is why this app exists rather than reusing WallPanel (see README). The WebView
 * is the dashboard; everything else here is about staying pinned and immersive.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs

    private val handler = Handler(Looper.getMainLooper())

    /** Heartbeat (kiosk check-in) runs off the main thread; commands dispatch back on it. */
    private val checkinExecutor = Executors.newSingleThreadExecutor()
    private val heartbeatRunnable = Runnable { doHeartbeat() }

    /** Live server→kiosk command stream (instant commands; heartbeat is the fallback). */
    private var commandStream: KioskCommandStream? = null

    /** De-dup: a command arrives via the stream AND lingers as the heartbeat fallback. */
    private var lastCommand: String? = null
    private var lastCommandAt = 0L

    /** Logs the outcome of a self-update install (on success the process is replaced,
     * so this mostly surfaces failures / a missing device-owner privilege). */
    private val installResultReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
            val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            when (status) {
                PackageInstaller.STATUS_SUCCESS ->
                    android.util.Log.i("MainActivity", "update install succeeded")
                PackageInstaller.STATUS_PENDING_USER_ACTION ->
                    android.util.Log.w("MainActivity", "update install needs user action — not device owner?")
                else ->
                    android.util.Log.e("MainActivity", "update install failed: status=$status msg=$msg")
            }
        }
    }

    /** Set when a main-frame load fails; cleared on a successful main-frame load. */
    private var loadError = false

    /** Current auto-retry backoff (ms): starts at MIN, doubles up to MAX. */
    private var retryDelayMs = RETRY_MIN_MS
    private val retryRunnable = Runnable { attemptReload() }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        LockTask.configurePolicies(this)
        configureWebView()
        binding.reloadButton.setOnClickListener {
            // Human-driven recovery: reset backoff and reload immediately.
            retryDelayMs = RETRY_MIN_MS
            attemptReload()
        }
        binding.webview.loadUrl(prefs.dashboardUrl)
        // Share the WebView with the voice pipeline so it can drive the on-screen
        // voice overlay (window.bifrostVoice). Same process as VoiceService.
        VoiceFeedback.attach(binding.webview)

        // Maintenance hatch: long-press the corner → PIN → setup screen.
        binding.maintenanceHandle.setOnLongClickListener {
            PinGate.prompt(this, prefs) {
                startActivity(Intent(this, SettingsActivity::class.java))
            }
            true
        }

        // Back navigates WebView history but can never escape the dashboard.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webview.canGoBack()) binding.webview.goBack()
                // else: intentionally swallowed — no exit via Back.
            }
        })

        maybeStartVoice()
        startHeartbeat()
        startCommandStream()
        ContextCompat.registerReceiver(
            this,
            installResultReceiver,
            IntentFilter(KioskUpdater.ACTION_INSTALL_RESULT),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    // ---- kiosk check-in (heartbeat + controller commands) -------------------

    /** Begin (or restart) the periodic check-in that registers this tablet with the hub. */
    private fun startHeartbeat() {
        handler.removeCallbacks(heartbeatRunnable)
        handler.post(heartbeatRunnable)
    }

    /** Open the live command stream so controller commands arrive instantly. */
    private fun startCommandStream() {
        commandStream?.stop()
        commandStream = KioskCommandStream(prefs.serverBase, prefs.apiKey) { cmd ->
            handler.post { handleCommand(cmd) }
        }.also { it.start() }
    }

    private fun doHeartbeat() {
        val screenOn = (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        val server = prefs.serverBase
        val key = prefs.apiKey
        checkinExecutor.execute {
            val res = KioskCheckin(server, key).checkin(BuildConfig.VERSION_NAME, screenOn) ?: return@execute
            handler.post {
                // Adopt the hub-assigned room as the voice context (location).
                res.room?.let { if (it != prefs.roomContext) prefs.roomContext = it }
                res.command?.let { handleCommand(it) }
            }
        }
        handler.postDelayed(heartbeatRunnable, CHECKIN_MS)
    }

    /** Act on a controller command (from the live stream or the heartbeat fallback). */
    private fun handleCommand(cmd: String) {
        // The same command can arrive twice — pushed live, then again as the
        // heartbeat's pending fallback. Ignore an identical repeat within the window.
        val now = android.os.SystemClock.elapsedRealtime()
        if (cmd == lastCommand && now - lastCommandAt < COMMAND_DEDUP_MS) return
        lastCommand = cmd
        lastCommandAt = now
        when (cmd) {
            "lock" -> signOut()
            "sleep" -> sleepScreen()
            "wake" -> wakeScreen()
            "update" -> startUpdate()
            else -> android.util.Log.w("MainActivity", "unknown kiosk command: $cmd")
        }
    }

    /** Pull + install the latest APK the hub has cached (device-owner silent install). */
    private fun startUpdate() {
        val server = prefs.serverBase
        val key = prefs.apiKey
        checkinExecutor.execute {
            val result = KioskUpdater(applicationContext, server, key).update()
            android.util.Log.i("MainActivity", "update: $result")
        }
    }

    /** Force sign-out: drop the dashboard session and reload (lands on login). */
    private fun signOut() {
        CookieManager.getInstance().removeAllCookies(null)
        binding.webview.clearHistory()
        retryDelayMs = RETRY_MIN_MS
        binding.webview.loadUrl(prefs.dashboardUrl)
    }

    /** Sleep the display. Device-owner can lock now; the kiosk has no keyguard so it just sleeps. */
    private fun sleepScreen() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        runCatching { dpm.lockNow() }
    }

    /** Best-effort wake: a brief wake lock that turns the screen back on. */
    private fun wakeScreen() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        val wl = pm.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "bifrost:wake",
        )
        runCatching { wl.acquire(3_000L) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        binding.webview.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            // Mark this WebView as the kiosk so the Bifrost web app can hide
            // controls that don't belong on a wall fixture (e.g. Sign out — a
            // kiosk is deauthed remotely by the controller, never by a passerby).
            settings.userAgentString = "${settings.userAgentString} BifrostKiosk/${BuildConfig.VERSION_NAME}"
            settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                // Keep all navigation inside the kiosk WebView.
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean = false

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError,
                ) {
                    // Only main-frame failures brick the kiosk; ignore subresources.
                    if (request.isForMainFrame) onMainFrameError(view)
                }

                override fun onReceivedHttpError(
                    view: WebView,
                    request: WebResourceRequest,
                    errorResponse: WebResourceResponse,
                ) {
                    // Treat 5xx on the main frame as "server still booting".
                    if (request.isForMainFrame && errorResponse.statusCode >= 500) {
                        onMainFrameError(view)
                    }
                }

                override fun onPageCommitVisible(view: WebView, url: String) {
                    // Real content painted with no pending error → recovered.
                    if (!loadError) onMainFrameSuccess()
                }
            }
        }
    }

    /** A main-frame load failed: cover Chrome's error page and start auto-retry. */
    private fun onMainFrameError(view: WebView) {
        loadError = true
        // Clear the generic Chrome error page underneath, then show our overlay.
        view.loadUrl("about:blank")
        binding.errorUrl.text = prefs.dashboardUrl
        binding.errorOverlay.visibility = View.VISIBLE
        scheduleRetry()
    }

    /** A main-frame load succeeded: hide the overlay and stop retrying. */
    private fun onMainFrameSuccess() {
        loadError = false
        retryDelayMs = RETRY_MIN_MS
        handler.removeCallbacks(retryRunnable)
        binding.errorOverlay.visibility = View.GONE
    }

    private fun scheduleRetry() {
        handler.removeCallbacks(retryRunnable)
        handler.postDelayed(retryRunnable, retryDelayMs)
    }

    /** Reload the dashboard; if it fails again, onReceivedError re-arms the loop. */
    private fun attemptReload() {
        handler.removeCallbacks(retryRunnable)
        // Optimistically clear the error so a success can hide the overlay; if the
        // load fails, onReceivedError sets it again and re-schedules.
        loadError = false
        binding.webview.loadUrl(prefs.dashboardUrl)
        // Gentle backoff for the *next* auto-retry, capped.
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(RETRY_MAX_MS)
        if (binding.errorOverlay.visibility == View.VISIBLE) scheduleRetry()
    }

    private fun maybeStartVoice() {
        if (prefs.voiceEnabled && LockTask.hasMicPermission(this)) {
            VoiceService.start(this)
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-pin + re-hide system UI every time we regain focus — defends against
        // anything that briefly surfaces over the kiosk.
        LockTask.start(this)
        applyImmersive()
        maybeStartVoice()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersive()
    }

    private fun applyImmersive() {
        val controller = WindowInsetsControllerCompat(window, binding.root)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(retryRunnable)
        handler.removeCallbacks(heartbeatRunnable)
        commandStream?.stop()
        runCatching { unregisterReceiver(installResultReceiver) }
        checkinExecutor.shutdownNow()
        VoiceFeedback.detach(binding.webview)
        binding.webview.destroy()
        super.onDestroy()
    }

    companion object {
        /** Auto-retry cadence: first retry ~5s, doubling up to ~20s, hands-free. */
        private const val RETRY_MIN_MS = 5_000L
        private const val RETRY_MAX_MS = 20_000L

        /** Heartbeat cadence — now a slow liveness ping; commands push over the stream. */
        private const val CHECKIN_MS = 60_000L

        /** Ignore an identical command repeated within this window (stream + heartbeat overlap). */
        private const val COMMAND_DEDUP_MS = 90_000L
    }
}
