package live.theundead.bifrost.kiosk.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.whispercpp.whisper.WhisperContext
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
 * Watch `logcat -s Replay HybridSpeechEngine`:
 *   - the Vosk wake path (partials, `[WAKE]`, the final + vosk-command) — this is
 *     what regressed when the hybrid owned the mic, so it's the thing to verify;
 *   - the whisper transcription of the same clip (accuracy comparison).
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

        // (1) Vosk wake path through the real engine + file source. Skip the
        // server vocab fetch (open recognizer, instant) so the harness is fast +
        // server-independent. (Whisper-on-clip comparison is done separately below.)
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

        // (2) Whisper transcription of the same clip (off the engine thread).
        thread(name = "replay-whisper") {
            val model = File(app.filesDir, "whisper.bin")
            if (!model.exists()) {
                Log.w(TAG, "no whisper model at $model — skipping whisper compare"); return@thread
            }
            val audio = wavToFloat(wav) ?: run { Log.w(TAG, "couldn't read WAV"); return@thread }
            val ctx = runCatching { WhisperContext.fromFile(model.path) }
                .getOrElse { Log.e(TAG, "whisper load failed", it); return@thread }
            val text = runCatching { ctx.transcribe(audio) }.getOrElse { "" }
            Log.i(TAG, ">>> WHISPER full-clip: '$text'")
            ctx.release()
        }
    }

    /** Decode a 16 kHz mono PCM WAV to float samples (reuses the file source + converter). */
    private fun wavToFloat(wav: File): FloatArray? {
        val src = FileAudioSource(wav, realTimePaced = false)
        if (!src.open()) return null
        val out = ArrayList<Float>()
        val buf = ByteArray(FRAME_BYTES)
        val fl = FloatArray(FRAME_BYTES / 2)
        while (true) {
            val n = src.read(buf)
            if (n < 0) break
            if (n == 0) continue
            val s = HybridSpeechEngine.pcm16ToFloat(buf, n, fl)
            for (i in 0 until s) out.add(fl[i])
        }
        src.close()
        return out.toFloatArray()
    }

    companion object {
        private const val TAG = "Replay"
    }
}
