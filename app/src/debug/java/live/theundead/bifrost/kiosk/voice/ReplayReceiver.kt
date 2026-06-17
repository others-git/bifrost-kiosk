package live.theundead.bifrost.kiosk.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import live.theundead.bifrost.kiosk.Prefs
import java.io.File
import kotlin.concurrent.thread

/**
 * DEBUG-ONLY replay harness: feed a WAV through the *real* [HybridSpeechEngine]
 * so the voice path is exercised reproducibly without talking to the tablet.
 *
 *   adb shell am broadcast -a live.theundead.bifrost.kiosk.REPLAY \
 *     --es wav /sdcard/Download/cmd.wav [--ez paced true]
 *
 * Watch `logcat -s Replay HybridSpeechEngine` for the Vosk wake path (partials,
 * `[WAKE]`, the final + vosk-command) — what regressed when the hybrid owned the
 * mic, so it's the thing to verify.
 *
 * Lives in `src/debug` and is only registered in the debug manifest — never ships
 * in a release build.
 */
class ReplayReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val path = intent.getStringExtra("wav")
        if (path.isNullOrBlank()) {
            Log.w(TAG, "no 'wav' extra"); return
        }
        val paced = intent.getBooleanExtra("paced", false)
        val wav = File(path)
        if (!wav.exists()) {
            Log.w(TAG, "wav not found: $path"); return
        }
        val app = context.applicationContext
        val prefs = Prefs(app)
        val wake = prefs.wakeWord
        Log.i(TAG, "=== replay '$path' (paced=$paced, wake='$wake') ===")

        // Vosk wake path through the real engine + file source. Skip the server
        // vocab fetch (open recognizer, instant) so the harness is fast +
        // server-independent.
        val engine = HybridSpeechEngine(
            app, prefs, FileAudioSource(wav, paced),
            useVocabulary = false,
        )
        engine.start(object : SpeechEngine.Listener {
            override fun onPartial(text: String) {
                val hit = WakeWord.parse(text, wake) != null
                Log.i(TAG, "partial: '$text'${if (hit) "  [WAKE]" else ""}")
            }

            override fun onTranscript(text: String) {
                val m = WakeWord.parse(text, wake)
                val cmd = m?.command?.ifBlank { null } ?: WakeWord.stripWake(text, wake)
                Log.i(TAG, "FINAL: '$text'  wake=${m != null}  vosk-command='$cmd'")
            }
        })
        // The file source ends the loop at EOF on its own; this just releases the
        // recognizer/model afterwards. Generous so a real-time-paced clip isn't cut.
        thread { runCatching { Thread.sleep(45_000); engine.shutdown() } }
    }

    companion object {
        private const val TAG = "Replay"
    }
}
