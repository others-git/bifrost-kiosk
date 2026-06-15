package live.theundead.bifrost.kiosk.voice

import android.content.Context
import android.util.Log
import live.theundead.bifrost.kiosk.Prefs
import java.util.concurrent.Executors

/**
 * Orchestrates the satellite: wake-word gating → command POST → spoken reply.
 *
 * Pipeline (half-duplex): the [SpeechEngine] streams finalized transcripts; each
 * is run through [WakeWord]. On a hit with a command, the mic is paused, the
 * command text is POSTed to Bifrost ([BifrostVoiceClient]), the `said` reply is
 * spoken ([TtsPlayer]), and only then does the mic resume — so the recognizer
 * never hears the TTS (cheap, robust echo handling without AEC tuning).
 */
class VoicePipeline(
    context: Context,
    private val prefs: Prefs,
    private val engine: SpeechEngine = VoskSpeechEngine(context),
    private val tts: TtsPlayer = TtsPlayer(context),
) : SpeechEngine.Listener {

    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var busy = false

    /**
     * Latched once per utterance the instant the wake word appears in the live
     * (partial) hypothesis, so the overlay flashes "listening" with zero latency
     * — well before the silence-bounded final transcript lands. Reset in
     * [onTranscript]. Partials are only forwarded to the overlay once latched, so
     * pre-wake chatter never streams to the screen.
     */
    @Volatile private var wakeFlashed = false

    fun start() {
        engine.start(this)
    }

    override fun onPartial(text: String) {
        if (busy) return
        if (!wakeFlashed) {
            // Flash "listening" the moment the wake word shows up in the hypothesis.
            if (WakeWord.parse(text, prefs.wakeWord) == null) return
            wakeFlashed = true
            VoiceFeedback.setState(VoiceFeedback.State.LISTENING)
        }
        // Only stream partials after the wake latch — don't leak pre-wake chatter.
        VoiceFeedback.partial(text)
    }

    override fun onTranscript(text: String) {
        if (busy) return
        wakeFlashed = false
        val match = WakeWord.parse(text, prefs.wakeWord)
        if (match == null || match.command.isBlank()) {
            // No wake match, or a bare wake word with no command — clear the overlay.
            if (match != null) Log.i(TAG, "wake word heard, no command")
            VoiceFeedback.setState(VoiceFeedback.State.IDLE)
            return
        }
        busy = true
        engine.pause()
        VoiceFeedback.setState(VoiceFeedback.State.THINKING)
        Log.i(TAG, "command: '${match.command}'")
        worker.execute { handle(match.command) }
    }

    private fun handle(command: String) {
        val client = BifrostVoiceClient(prefs.serverBase, prefs.voiceEndpoint, prefs.apiKey)
        val reply = client.command(command, prefs.roomContext.ifBlank { null })
        // Error when the hub is unreachable (null), or it returned an HTTP error /
        // "didn't understand" with nothing to say (BifrostVoiceClient maps non-2xx
        // to Reply(false, "")).
        val isError = reply == null || (!reply.ok && reply.said.isBlank())
        if (isError) VoiceFeedback.setState(VoiceFeedback.State.ERROR)
        val say = when {
            reply == null -> "Sorry, I couldn't reach the hub."
            reply.said.isNotBlank() -> reply.said
            reply.ok -> "Done."
            else -> "Sorry, I didn't understand."
        }
        if (!isError) VoiceFeedback.setState(VoiceFeedback.State.SPEAKING)
        tts.speak(say) {
            // Resume listening only after the reply finishes playing.
            VoiceFeedback.setState(VoiceFeedback.State.IDLE)
            busy = false
            engine.resume()
        }
    }

    fun shutdown() {
        engine.shutdown()
        tts.shutdown()
        worker.shutdownNow()
    }

    companion object {
        private const val TAG = "VoicePipeline"
    }
}
