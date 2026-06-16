package live.theundead.bifrost.kiosk.voice

import android.util.Log
import android.webkit.WebView
import java.lang.ref.WeakReference

/**
 * Process-wide bridge from the voice pipeline to the dashboard's voice overlay.
 *
 * The Bifrost web frontend exposes `window.bifrostVoice.setState(...)` /
 * `window.bifrostVoice.partial(...)` in the WebView; this object drives them so a
 * wake word, the streamed partial, "thinking", the spoken reply, and errors are
 * reflected on screen. The [VoiceService] (a foreground service) and
 * [live.theundead.bifrost.kiosk.MainActivity] live in the **same process**, so a
 * single [WeakReference] to the activity's WebView is enough to share the hook.
 *
 * Every call marshals `evaluateJavascript` onto the WebView's (UI) thread via
 * [WebView.post] and is a no-op when nothing is attached (WebView gone, or the
 * dashboard hasn't loaded the hook yet). The JS is always guarded with
 * `window.bifrostVoice &&` so it is harmless on the login page or while loading.
 */
object VoiceFeedback {

    enum class State(val js: String) {
        IDLE("idle"),
        LISTENING("listening"),
        THINKING("thinking"),
        SPEAKING("speaking"),
        ERROR("error"),
    }

    @Volatile
    private var webViewRef: WeakReference<WebView>? = null

    fun attach(webView: WebView) {
        Log.i(TAG, "attach")
        webViewRef = WeakReference(webView)
    }

    /**
     * Clear the bridge. Pass the detaching WebView so a *recreated* activity's
     * fresh [attach] isn't clobbered: on recreation the old instance's onDestroy
     * can run after the new instance's onCreate, so a bare detach would null the
     * ref the new activity just set.
     */
    fun detach(webView: WebView? = null) {
        if (webView == null || webViewRef?.get() === webView) {
            Log.i(TAG, "detach")
            webViewRef = null
        }
    }

    fun setState(state: State, detail: String? = null) {
        val arg = detail?.let { ", '${escape(it)}'" } ?: ""
        eval("window.bifrostVoice && window.bifrostVoice.setState('${state.js}'$arg)")
    }

    fun partial(text: String) {
        eval("window.bifrostVoice && window.bifrostVoice.partial('${escape(text)}')")
    }

    private fun eval(js: String) {
        val webView = webViewRef?.get()
        if (webView == null) {
            Log.w(TAG, "eval skipped — no WebView attached")
            return
        }
        webView.post { webView.evaluateJavascript(js) { r -> Log.i(TAG, "eval → $r") } }
    }

    private const val TAG = "VoiceFeedback"

    /** Escape for a single-quoted JS string literal. */
    private fun escape(s: String): String =
        s.replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\r", "\\r")
            .replace("\n", "\\n")
}
