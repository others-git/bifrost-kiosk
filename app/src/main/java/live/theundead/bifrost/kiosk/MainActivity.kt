package live.theundead.bifrost.kiosk

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
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
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        binding.webview.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
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
        VoiceFeedback.detach()
        binding.webview.destroy()
        super.onDestroy()
    }

    companion object {
        /** Auto-retry cadence: first retry ~5s, doubling up to ~20s, hands-free. */
        private const val RETRY_MIN_MS = 5_000L
        private const val RETRY_MAX_MS = 20_000L
    }
}
