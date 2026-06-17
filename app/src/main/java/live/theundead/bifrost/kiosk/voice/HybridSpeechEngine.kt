package live.theundead.bifrost.kiosk.voice

import android.content.Context
import android.util.Log
import live.theundead.bifrost.kiosk.Prefs
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Mic-owning speech engine: a **light Vosk model for always-on wake spotting**,
 * plus capture of the **command audio** for server-side transcription.
 *
 * We own the [AudioSource] (mic) directly rather than Vosk's `SpeechService`, so
 * the raw PCM is in hand: every frame is fed to the Vosk [Recognizer] for
 * wake/end detection **and** appended to a rolling ring buffer. When the wake
 * word fires the pipeline calls [noteWake]; when the command ends it calls
 * [commandAudioWav] to grab the buffered utterance and POST it to the server
 * (`/api/voice/listen` → whisper). On-device transcription of the command was
 * dropped — too slow on the tablet; the server (Speaches) is fast + accurate, and
 * the pipeline falls back to the Vosk transcript when the server STT is absent.
 */
class HybridSpeechEngine(
    private val context: Context,
    private val prefs: Prefs = Prefs(context),
    // Pluggable so the replay harness can drive the exact same pipeline from a
    // WAV file instead of the live mic — debugging without talking to the tablet.
    private val audioSource: AudioSource = MicAudioSource(),
    // Harness knob: skip the (server-dependent) vocabulary fetch so the open
    // recognizer starts immediately. Defaults to production behaviour.
    private val useVocabulary: Boolean = true,
) : SpeechEngine, CommandTranscriber {

    private var listener: SpeechEngine.Listener? = null
    @Volatile private var running = false
    @Volatile private var paused = false

    private var voskModel: Model? = null
    @Volatile private var recognizer: Recognizer? = null
    private var audioThread: Thread? = null

    private val worker = Executors.newSingleThreadExecutor()
    private val refresher = ScheduledThreadPoolExecutor(1)
    @Volatile private var currentGrammar: String? = null

    // Rolling ring of recent mono float samples (-1..1). Sized for the longest
    // plausible command; commands are short so the wake point is never overwritten.
    private val ring = FloatArray(SAMPLE_RATE * RING_SECONDS)
    private val ringLock = Any()
    @Volatile private var writeIndex = 0L
    @Volatile private var wakeIndex = 0L

    override fun start(listener: SpeechEngine.Listener) {
        this.listener = listener
        if (running) return
        running = true
        loadVoskThenRun()
    }

    // ---- model loading ------------------------------------------------------

    private fun loadVoskThenRun() {
        val internal = File(context.filesDir, VOSK_DIR)
        val external = context.getExternalFilesDir(null)?.let { File(it, VOSK_ASSET_DIR) }
        when (
            ModelResolver.resolve(
                externalPresent = external.isNonEmptyDir(),
                externalMtime = external?.lastModified() ?: 0L,
                internalPresent = internal.isNonEmptyDir(),
                internalMtime = internal.lastModified(),
                assetPresent = hasAsset(VOSK_ASSET_DIR),
            )
        ) {
            ModelSource.EXTERNAL -> {
                runCatching { mirror(external!!, internal) }
                    .onFailure { Log.e(TAG, "mirror vosk model failed", it) }
                initVosk(internal.absolutePath)
            }
            ModelSource.INTERNAL -> initVosk(internal.absolutePath)
            ModelSource.ASSET -> StorageService.unpack(
                context, VOSK_ASSET_DIR, VOSK_DIR,
                { m -> voskModel = m; startVocabularyAndLoop() },
                { e -> Log.e(TAG, "vosk unpack failed", e) },
            )
            ModelSource.NONE -> Log.w(TAG, "no Vosk model — voice idle")
        }
    }

    private fun initVosk(path: String) {
        runCatching { voskModel = Model(path); startVocabularyAndLoop() }
            .onFailure { Log.e(TAG, "vosk model load failed", it) }
    }

    // ---- recognizer + vocabulary -------------------------------------------

    private fun startVocabularyAndLoop() {
        if (!useVocabulary) {
            // Harness path: open recognizer now, no server round-trip, no refresh.
            worker.execute {
                buildRecognizer(null)
                startAudioLoop()
            }
            return
        }
        worker.execute {
            val words = VocabularyClient(prefs.serverBase, prefs.apiKey).fetch()
            buildRecognizer(words)
            startAudioLoop()
        }
        refresher.scheduleWithFixedDelay(
            { refreshVocabulary() }, REFRESH_MINUTES, REFRESH_MINUTES, TimeUnit.MINUTES,
        )
    }

    private fun refreshVocabulary() {
        worker.execute {
            if (!running) return@execute
            val words = VocabularyClient(prefs.serverBase, prefs.apiKey).fetch() ?: return@execute
            if (VoskSpeechEngine.buildGrammar(words, prefs.wakeWord) != currentGrammar) {
                Log.i(TAG, "vocabulary changed — rebuilding wake recognizer")
                buildRecognizer(words)
            }
        }
    }

    private fun buildRecognizer(words: List<String>?) {
        val m = voskModel ?: return
        runCatching {
            val rec = if (words != null) {
                val g = VoskSpeechEngine.buildGrammar(words, prefs.wakeWord)
                currentGrammar = g
                Log.i(TAG, "wake recognizer: ${words.size}-word grammar")
                Recognizer(m, SAMPLE_RATE.toFloat(), g)
            } else {
                currentGrammar = null
                Log.i(TAG, "wake recognizer: open (vocab fetch failed)")
                Recognizer(m, SAMPLE_RATE.toFloat())
            }
            recognizer = rec
        }.onFailure { Log.e(TAG, "recognizer build failed", it) }
    }

    // ---- audio loop ---------------------------------------------------------

    private fun startAudioLoop() {
        if (audioThread != null) return
        audioThread = thread(name = "hybrid-audio", isDaemon = true) {
            if (!audioSource.open()) {
                Log.e(TAG, "audio source unavailable: ${audioSource.label}")
                return@thread
            }
            val bytes = ByteArray(FRAME_BYTES)
            val floats = FloatArray(FRAME_BYTES / 2)
            Log.i(TAG, "hybrid listening @ ${SAMPLE_RATE}Hz (${audioSource.label})")
            while (running) {
                val n = audioSource.read(bytes)
                if (n < 0) {
                    Log.i(TAG, "audio source ended"); break // file replay finished
                }
                if (n == 0 || paused) continue
                val samples = pcm16ToFloat(bytes, n, floats)
                appendToRing(floats, samples)
                feedVosk(bytes, n)
            }
            finalizeVosk() // flush the trailing utterance (matters for file replay)
            audioSource.close()
        }
    }

    /** Emit the recognizer's final result — used when a finite source (file) ends. */
    private fun finalizeVosk() {
        val rec = recognizer ?: return
        runCatching {
            val text = JSONObject(rec.finalResult).optString("text", "")
            if (text.isNotBlank()) listener?.onTranscript(text)
        }
    }

    /** Feed the Vosk wake recognizer; emit partial/final transcripts to the pipeline. */
    private fun feedVosk(bytes: ByteArray, n: Int) {
        val rec = recognizer ?: return
        runCatching {
            if (rec.acceptWaveForm(bytes, n)) {
                val text = JSONObject(rec.result).optString("text", "")
                if (text.isNotBlank()) listener?.onTranscript(text)
            } else {
                val text = JSONObject(rec.partialResult).optString("partial", "")
                if (text.isNotBlank()) listener?.onPartial(text)
            }
        }.onFailure { Log.e(TAG, "vosk feed error", it) }
    }

    private fun appendToRing(src: FloatArray, count: Int) {
        synchronized(ringLock) {
            for (i in 0 until count) {
                ring[((writeIndex + i) % ring.size).toInt()] = src[i]
            }
            writeIndex += count
        }
    }

    // ---- CommandTranscriber -------------------------------------------------

    override fun noteWake() {
        // Back up a little so a same-breath command isn't clipped by wake latency.
        wakeIndex = maxOf(0L, writeIndex - PREROLL_SAMPLES)
    }

    override fun commandAudioWav(): ByteArray? {
        val audio = synchronized(ringLock) {
            val to = writeIndex
            var from = wakeIndex
            if (to - from > ring.size) from = to - ring.size
            val size = (to - from).toInt().coerceAtLeast(0)
            FloatArray(size) { ring[((from + it) % ring.size).toInt()] }
        }
        if (audio.size < SAMPLE_RATE / 4) return null // <250ms — nothing useful
        return floatPcmToWav(audio)
    }

    /** Wrap mono float samples (-1..1) as a 16 kHz 16-bit PCM WAV byte array. */
    private fun floatPcmToWav(samples: FloatArray): ByteArray {
        val dataLen = samples.size * 2
        val out = java.io.ByteArrayOutputStream(44 + dataLen)
        fun le32(v: Int) { out.write(v); out.write(v shr 8); out.write(v shr 16); out.write(v shr 24) }
        fun le16(v: Int) { out.write(v); out.write(v shr 8) }
        out.write("RIFF".toByteArray()); le32(36 + dataLen); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); le32(16); le16(1); le16(1)        // PCM, mono
        le32(SAMPLE_RATE); le32(SAMPLE_RATE * 2); le16(2); le16(16)        // rate, byterate, align, bits
        out.write("data".toByteArray()); le32(dataLen)
        for (s in samples) {
            val v = (s.coerceIn(-1f, 1f) * 32767f).toInt()
            le16(v)
        }
        return out.toByteArray()
    }

    // ---- lifecycle ----------------------------------------------------------

    override fun pause() { paused = true }
    override fun resume() { paused = false }

    override fun shutdown() {
        running = false
        runCatching { refresher.shutdownNow() }
        runCatching { worker.shutdownNow() }
        runCatching { audioThread?.join(500) }
        audioThread = null
        runCatching { recognizer?.close() }; recognizer = null
        runCatching { voskModel?.close() }; voskModel = null
    }

    // ---- helpers ------------------------------------------------------------

    private fun File?.isNonEmptyDir() = this != null && isDirectory && (list()?.isNotEmpty() == true)

    private fun mirror(src: File, dst: File) {
        if (dst.exists()) dst.deleteRecursively()
        src.copyRecursively(dst, overwrite = true)
    }

    private fun hasAsset(dir: String) =
        runCatching { context.assets.list(dir)?.isNotEmpty() == true }.getOrDefault(false)

    companion object {
        private const val TAG = "HybridSpeechEngine"
        private const val SAMPLE_RATE = 16000
        private const val RING_SECONDS = 14
        private const val PREROLL_SAMPLES = SAMPLE_RATE / 2 // 0.5s lookback before wake
        private const val REFRESH_MINUTES = 5L

        private const val VOSK_ASSET_DIR = "model-en-us"
        private const val VOSK_DIR = "vosk-model"

        /** Decode little-endian PCM-16 [bytes] (length [n]) into [out]; returns sample count. */
        fun pcm16ToFloat(bytes: ByteArray, n: Int, out: FloatArray): Int {
            var i = 0
            var j = 0
            while (i + 1 < n && j < out.size) {
                val s = ((bytes[i + 1].toInt() shl 8) or (bytes[i].toInt() and 0xFF)).toShort()
                out[j++] = s / 32768f
                i += 2
            }
            return j
        }
    }
}
