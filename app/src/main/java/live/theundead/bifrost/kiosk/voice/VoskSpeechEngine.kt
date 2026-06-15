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
 * The model can come from three places (precedence in [ModelResolver]): a model
 * **pushed** to the app's external dir (`getExternalFilesDir/$ASSET_MODEL_DIR`,
 * "bring your own model" — no rebuild), the **internal** unpacked cache, or the
 * model **bundled** in the APK assets (see scripts/fetch-vosk-model.sh). With
 * none of them the engine stays idle and the rest of the app is unaffected —
 * graceful degradation, per the M23 voice constraints.
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

        val internal = File(context.filesDir, UNPACKED_DIR)
        val external = context.getExternalFilesDir(null)?.let { File(it, ASSET_MODEL_DIR) }

        when (
            ModelResolver.resolve(
                externalPresent = external.isNonEmptyDir(),
                externalMtime = external?.lastModified() ?: 0L,
                internalPresent = internal.isNonEmptyDir(),
                internalMtime = internal.lastModified(),
                assetPresent = hasAssetModel(),
            )
        ) {
            // BYO: mirror the pushed model onto internal storage (so Vosk can mmap
            // a real filesystem, not FUSE-backed external) and load it.
            ModelSource.EXTERNAL -> {
                runCatching { mirror(external!!, internal) }
                    .onFailure { Log.e(TAG, "mirroring pushed model failed", it) }
                initFrom(internal.absolutePath)
            }
            ModelSource.INTERNAL -> initFrom(internal.absolutePath)
            // Unpack the bundled asset model into internal storage, then run (async).
            ModelSource.ASSET -> StorageService.unpack(
                context, ASSET_MODEL_DIR, UNPACKED_DIR,
                { m -> model = m; runRecognizer() },
                { e -> Log.e(TAG, "model unpack failed", e) },
            )
            ModelSource.NONE ->
                Log.w(TAG, "no Vosk model (pushed, cached, or bundled) — voice idle")
        }
    }

    /** Replace [dst] with a fresh copy of [src] (used to mirror a pushed model). */
    private fun mirror(src: File, dst: File) {
        if (dst.exists()) dst.deleteRecursively()
        src.copyRecursively(dst, overwrite = true)
    }

    private fun File?.isNonEmptyDir(): Boolean =
        this != null && isDirectory && (list()?.isNotEmpty() == true)

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
