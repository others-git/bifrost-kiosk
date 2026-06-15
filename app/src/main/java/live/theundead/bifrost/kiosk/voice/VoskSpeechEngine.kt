package live.theundead.bifrost.kiosk.voice

import android.content.Context
import android.util.Log
import live.theundead.bifrost.kiosk.Prefs
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

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
 *
 * **Vocabulary biasing.** The small model otherwise recognizes against all of
 * English, so command words get misheard ("lights" → "loot"). We fetch the
 * home's vocabulary ([VocabularyClient], `GET /api/voice/vocabulary`) and build
 * a Vosk grammar (a JSON array of allowed words + the `[unk]` out-of-vocabulary
 * token) so the recognizer is constrained to words it can actually act on. If
 * the fetch fails (offline / no key) we fall back to the open recognizer so
 * voice still works, just less accurately. We re-fetch on a slow cadence so
 * newly-added devices/rooms/scenes become recognizable without a restart.
 */
class VoskSpeechEngine(
    private val context: Context,
    private val prefs: Prefs = Prefs(context),
) : SpeechEngine {

    private var model: Model? = null
    private var speechService: SpeechService? = null
    private var listener: SpeechEngine.Listener? = null
    private var started = false

    /** Off-thread work: vocab fetch + recognizer (re)build never touch the audio thread. */
    private val worker = Executors.newSingleThreadExecutor()
    private val refresher = ScheduledThreadPoolExecutor(1)

    /** Last grammar we built the recognizer with; skip a rebuild when unchanged. */
    @Volatile private var currentGrammar: String? = null

    private val recognitionListener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) {
            val text = hypothesis?.let { JSONObject(it).optString("partial", "") }.orEmpty()
            if (text.isNotBlank()) listener?.onPartial(text)
        }

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
                { m -> model = m; startWithVocabulary() },
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
        runCatching { model = Model(path); startWithVocabulary() }
            .onFailure { Log.e(TAG, "model load failed", it) }
    }

    /**
     * Fetch the vocabulary off-thread, build the recognizer with it (grammar
     * mode) or without (open fallback), then arm a periodic re-fetch so the
     * recognizer tracks newly-added devices. Never blocks the audio thread.
     */
    private fun startWithVocabulary() {
        worker.execute {
            val words = VocabularyClient(prefs.serverBase, prefs.apiKey).fetch()
            buildRecognizer(words)
        }
        refresher.scheduleWithFixedDelay(
            { refreshVocabulary() },
            REFRESH_MINUTES, REFRESH_MINUTES, TimeUnit.MINUTES,
        )
    }

    /** Re-fetch the vocabulary and rebuild the recognizer if it changed. */
    private fun refreshVocabulary() {
        worker.execute {
            if (!started) return@execute
            val words = VocabularyClient(prefs.serverBase, prefs.apiKey).fetch() ?: return@execute
            val grammar = buildGrammar(words)
            if (grammar != currentGrammar) {
                Log.i(TAG, "vocabulary changed — rebuilding recognizer (${words.size} words)")
                buildRecognizer(words)
            }
        }
    }

    /**
     * (Re)create the recognizer. With [words] we constrain it to a grammar; with
     * null (fetch failed) we fall back to the open recognizer so voice still
     * works. Tears down any existing service first so a refresh swaps cleanly.
     */
    private fun buildRecognizer(words: List<String>?) {
        val m = model ?: return
        runCatching { speechService?.stop(); speechService?.shutdown() }
        speechService = null
        runCatching {
            val recognizer = if (words != null) {
                val grammar = buildGrammar(words)
                currentGrammar = grammar
                Log.i(TAG, "Vosk grammar: ${words.size} words")
                Recognizer(m, SAMPLE_RATE, grammar)
            } else {
                currentGrammar = null
                Log.i(TAG, "Vosk: open recognizer (vocab fetch failed)")
                Recognizer(m, SAMPLE_RATE)
            }
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
        started = false
        runCatching { refresher.shutdownNow() }
        runCatching { worker.shutdownNow() }
        runCatching { speechService?.stop(); speechService?.shutdown() }
        speechService = null
        runCatching { model?.close() }
        model = null
        currentGrammar = null
    }

    companion object {
        private const val TAG = "VoskSpeechEngine"
        private const val ASSET_MODEL_DIR = "model-en-us"
        private const val UNPACKED_DIR = "vosk-model"
        private const val SAMPLE_RATE = 16000.0f

        /** Re-fetch the vocabulary this often so new devices become recognizable. */
        private const val REFRESH_MINUTES = 5L

        /**
         * Build a Vosk grammar string from [words]: a JSON array of the allowed
         * words plus the `[unk]` out-of-vocabulary token, so non-command audio
         * maps to "unknown" instead of being force-fit to a command word.
         */
        fun buildGrammar(words: List<String>): String {
            val arr = JSONArray()
            // Dedupe defensively; the client already lowercases/trims.
            for (w in words.distinct()) arr.put(w)
            arr.put(UNK_TOKEN)
            return arr.toString()
        }

        const val UNK_TOKEN = "[unk]"
    }
}
