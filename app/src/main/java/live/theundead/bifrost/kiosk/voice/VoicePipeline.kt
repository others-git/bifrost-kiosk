package live.theundead.bifrost.kiosk.voice

import android.content.Context
import android.util.Log
import live.theundead.bifrost.kiosk.Prefs
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Orchestrates the satellite as a **two-phase** voice pipeline: wake-word
 * gating → an open command window → command POST → spoken reply.
 *
 * Phase 1 — **wake listening.** The [SpeechEngine] streams partial and finalized
 * transcripts; each is run through [WakeWord]. The instant the wake word shows up
 * in a partial we flash LISTENING (zero latency) and enter CAPTURING.
 *
 * Phase 2 — **capture window.** Once woken, the command is whatever follows the
 * wake word. The user may speak it in the same breath ("bifrost turn off the
 * lights") or pause and speak it after ("bifrost" … "turn off the lights"). We
 * keep streaming partials to the overlay and hold a ~[WINDOW_MS] window that is
 * **reset on every fresh partial** so a pause mid-command doesn't cut the user
 * off. A finalized transcript with content (after stripping the wake word) is the
 * command → THINKING → POST. The window expiring with no command → back to IDLE.
 *
 * Half-duplex: while the command POSTs and the reply is spoken ([TtsPlayer]) the
 * mic is paused so the recognizer never hears the TTS, then it resumes.
 */
class VoicePipeline(
    context: Context,
    private val prefs: Prefs,
    private val engine: SpeechEngine = VoskSpeechEngine(context),
    private val tts: TtsPlayer = TtsPlayer(context),
    private val scheduler: ScheduledExecutorService = ScheduledThreadPoolExecutor(1),
) : SpeechEngine.Listener {

    private enum class Phase { WAKE, CAPTURING }

    private val worker = Executors.newSingleThreadExecutor()
    private val lock = Any()

    /** True while a command is in flight (POST + TTS) — ignore all input until done. */
    @Volatile private var busy = false

    /** WAKE = scanning for the wake word; CAPTURING = window open, collecting the command. */
    @Volatile private var phase = Phase.WAKE

    /** Pending window-expiry task; cancelled/rescheduled on each fresh partial. */
    private var windowTask: ScheduledFuture<*>? = null

    fun start() {
        engine.start(this)
    }

    override fun onPartial(text: String) {
        synchronized(lock) {
            if (busy) return
            when (phase) {
                Phase.WAKE -> {
                    // Flash LISTENING and open the window the moment the wake word
                    // shows up in the live hypothesis — well before the final lands.
                    if (WakeWord.parse(text, prefs.wakeWord) == null) return
                    enterCapturing()
                    VoiceFeedback.partial(strippedForDisplay(text))
                }
                Phase.CAPTURING -> {
                    // Live transcript during the window; any content resets the timer
                    // so a long/paused command isn't truncated.
                    if (text.isNotBlank()) resetWindow()
                    VoiceFeedback.partial(strippedForDisplay(text))
                }
            }
        }
    }

    override fun onTranscript(text: String) {
        synchronized(lock) {
            if (busy) return
            when (phase) {
                Phase.WAKE -> {
                    val match = WakeWord.parse(text, prefs.wakeWord) ?: return
                    // Woke from a final. If a command rode along, dispatch it now;
                    // otherwise open the window and wait for the following speech.
                    if (match.command.isNotBlank()) {
                        dispatch(match.command)
                    } else {
                        Log.i(TAG, "wake word heard, awaiting command")
                        if (phase != Phase.CAPTURING) enterCapturing()
                    }
                }
                Phase.CAPTURING -> {
                    // Inside the window: the final IS the command (it may or may not
                    // repeat the wake word, e.g. partial scanned "bifrost" then the
                    // final is the whole "bifrost turn off the lights").
                    val command = WakeWord.stripWake(text, prefs.wakeWord)
                    if (command.isNotBlank()) {
                        dispatch(command)
                    } else {
                        // Empty final (just the bare wake again) — keep the window open.
                        resetWindow()
                    }
                }
            }
        }
    }

    /** Caller must hold [lock]. */
    private fun enterCapturing() {
        phase = Phase.CAPTURING
        VoiceFeedback.setState(VoiceFeedback.State.LISTENING)
        resetWindow()
    }

    /** (Re)arm the silence window. Caller must hold [lock]. */
    private fun resetWindow() {
        windowTask?.cancel(false)
        windowTask = scheduler.schedule({ onWindowExpired() }, WINDOW_MS, TimeUnit.MILLISECONDS)
    }

    private fun onWindowExpired() {
        synchronized(lock) {
            if (busy || phase != Phase.CAPTURING) return
            Log.i(TAG, "command window expired with no command")
            resetToWake()
            VoiceFeedback.setState(VoiceFeedback.State.IDLE)
        }
    }

    /** Caller must hold [lock]. */
    private fun resetToWake() {
        windowTask?.cancel(false)
        windowTask = null
        phase = Phase.WAKE
    }

    /** Hand a captured command to the hub. Caller must hold [lock]. */
    private fun dispatch(command: String) {
        busy = true
        resetToWake()
        engine.pause()
        VoiceFeedback.setState(VoiceFeedback.State.THINKING)
        Log.i(TAG, "command: '$command'")
        worker.execute { handle(command) }
    }

    private fun handle(command: String) {
        val client = BifrostVoiceClient(prefs.serverBase, prefs.voiceEndpoint, prefs.apiKey)
        val reply = client.command(command, prefs.roomContext.ifBlank { null })
        // Error when the hub is unreachable (null), or it returned an HTTP error /
        // "didn't understand" with nothing to say (BifrostVoiceClient maps non-2xx
        // to Reply(false, "")).
        val authError = reply?.authError == true
        val isError = reply == null || (!reply.ok && reply.said.isBlank())
        if (authError) {
            VoiceFeedback.setState(VoiceFeedback.State.ERROR, "Voice not authorized — pair this device")
        } else if (isError) {
            VoiceFeedback.setState(VoiceFeedback.State.ERROR)
        }
        val say = when {
            authError -> "This device isn't authorized for voice. Pair it in settings."
            reply == null -> "Sorry, I couldn't reach the hub."
            reply.said.isNotBlank() -> reply.said
            reply.ok -> "Done."
            else -> "Sorry, I didn't understand."
        }
        if (!isError) VoiceFeedback.setState(VoiceFeedback.State.SPEAKING)
        tts.speak(say) {
            // Resume listening only after the reply finishes playing. Re-arm wake.
            VoiceFeedback.setState(VoiceFeedback.State.IDLE)
            synchronized(lock) {
                busy = false
                phase = Phase.WAKE
            }
            engine.resume()
        }
    }

    /** Show the post-wake remainder while capturing (strip the wake word prefix). */
    private fun strippedForDisplay(text: String): String {
        val cmd = WakeWord.stripWake(text, prefs.wakeWord)
        return cmd.ifBlank { text }
    }

    fun shutdown() {
        synchronized(lock) {
            windowTask?.cancel(false)
            windowTask = null
        }
        engine.shutdown()
        tts.shutdown()
        worker.shutdownNow()
        scheduler.shutdownNow()
    }

    companion object {
        private const val TAG = "VoicePipeline"

        /** Silence window after wake/last speech before we give up and re-arm wake. */
        private const val WINDOW_MS = 7_000L
    }
}
