package live.theundead.bifrost.kiosk.voice

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.io.File

/**
 * Vosk-backed [SpeechEngine]: FOSS, CPU-only, fully offline. One continuous
 * recognizer streams finalized (silence-bounded) transcripts; the pipeline does
 * the wake-word gating in [WakeWord], so the same recognizer serves both
 * "listen for 'bifrost'" and "capture the command".
 *
 * The acoustic model is **not** bundled in the APK by default (it is tens of MB).
 * Drop it in `app/src/main/assets/$ASSET_MODEL_DIR` (see
 * scripts/fetch-vosk-model.sh); without it the engine stays idle and the rest of
 * the app is unaffected — graceful degradation, per the M23 voice constraints.
 */
class VoskSpeechEngine(private val context: Context) : SpeechEngine {

    private var model: Model? = null
    private var speechService: SpeechService? = null
    private var listener: SpeechEngine.Listener? = null
    private var started = false

    private val recognitionListener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) {}

        override fun onResult(hypothesis: String?) {
            val text = hypothesis?.let { JSONObject(it).optString("text", "") }.orEmpty()
            if (text.isNotBlank()) listener?.onTranscript(text)
        }

        override fun onFinalResult(hypothesis: String?) {}

        override fun onError(exception: Exception?) {
            Log.e(TAG, "recognition error", exception)
        }

        override fun onTimeout() {}
    }

    override fun start(listener: SpeechEngine.Listener) {
        this.listener = listener
        if (started) return
        started = true

        val ready = File(context.filesDir, UNPACKED_DIR)
        if (ready.exists() && (ready.list()?.isNotEmpty() == true)) {
            initFrom(ready.absolutePath)
            return
        }
        // Unpack the bundled asset model (if any) into internal storage, then run.
        if (!hasAssetModel()) {
            Log.w(TAG, "no Vosk model in assets/$ASSET_MODEL_DIR — voice idle")
            return
        }
        StorageService.unpack(
            context, ASSET_MODEL_DIR, UNPACKED_DIR,
            { m -> model = m; runRecognizer() },
            { e -> Log.e(TAG, "model unpack failed", e) },
        )
    }

    private fun initFrom(path: String) {
        runCatching { model = Model(path); runRecognizer() }
            .onFailure { Log.e(TAG, "model load failed", it) }
    }

    private fun runRecognizer() {
        val m = model ?: return
        runCatching {
            val recognizer = Recognizer(m, SAMPLE_RATE)
            speechService = SpeechService(recognizer, SAMPLE_RATE).also {
                it.startListening(recognitionListener)
            }
            Log.i(TAG, "Vosk listening @ ${SAMPLE_RATE}Hz")
        }.onFailure { Log.e(TAG, "failed to start recognizer", it) }
    }

    private fun hasAssetModel(): Boolean =
        runCatching { context.assets.list(ASSET_MODEL_DIR)?.isNotEmpty() == true }
            .getOrDefault(false)

    override fun pause() {
        runCatching { speechService?.setPause(true) }
    }

    override fun resume() {
        runCatching { speechService?.setPause(false) }
    }

    override fun shutdown() {
        runCatching { speechService?.stop(); speechService?.shutdown() }
        speechService = null
        runCatching { model?.close() }
        model = null
        started = false
    }

    companion object {
        private const val TAG = "VoskSpeechEngine"
        private const val ASSET_MODEL_DIR = "model-en-us"
        private const val UNPACKED_DIR = "vosk-model"
        private const val SAMPLE_RATE = 16000.0f
    }
}
