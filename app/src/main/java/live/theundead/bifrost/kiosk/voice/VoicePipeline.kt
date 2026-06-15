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

    fun start() {
        engine.start(this)
    }

    override fun onTranscript(text: String) {
        if (busy) return
        val match = WakeWord.parse(text, prefs.wakeWord) ?: return
        if (match.command.isBlank()) {
            // Bare wake word with no command — acknowledge, keep listening.
            Log.i(TAG, "wake word heard, no command")
            return
        }
        busy = true
        engine.pause()
        Log.i(TAG, "command: '${match.command}'")
        worker.execute { handle(match.command) }
    }

    private fun handle(command: String) {
        val client = BifrostVoiceClient(prefs.serverBase, prefs.voiceEndpoint, prefs.apiKey)
        val reply = client.command(command, prefs.roomContext.ifBlank { null })
        val say = when {
            reply == null -> "Sorry, I couldn't reach the hub."
            reply.said.isNotBlank() -> reply.said
            reply.ok -> "Done."
            else -> "Sorry, I didn't understand."
        }
        tts.speak(say) {
            // Resume listening only after the reply finishes playing.
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
