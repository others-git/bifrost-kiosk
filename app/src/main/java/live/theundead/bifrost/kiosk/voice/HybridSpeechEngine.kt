package live.theundead.bifrost.kiosk.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.whispercpp.whisper.WhisperContext
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
 * Hybrid on-device speech engine: a **light Vosk model for always-on wake
 * spotting** and a **heavier whisper.cpp model for one-shot command
 * transcription**. This solves the tension that broke the single-model approach
 * — a big model (e.g. Vosk lgraph) is far more accurate but too heavy to run
 * continuously (it pegged the tablet's CPU and starved the wake loop), while the
 * small model keeps up with real-time but mishears commands.
 *
 * We own the [AudioRecord] directly (rather than Vosk's `SpeechService`) so the
 * raw PCM is in hand: every frame is fed to the Vosk [Recognizer] for wake/end
 * detection **and** appended to a rolling ring buffer. When the pipeline fires
 * the wake word it calls [noteWake]; when the command utterance ends it calls
 * [transcribeCommand], which decodes the buffered audio once with whisper.
 *
 * Whisper is optional: with no model resolved, [transcribeCommand] returns null
 * and the pipeline falls back to the Vosk transcript — so the app still works,
 * just at small-model accuracy.
 */
class HybridSpeechEngine(
    private val context: Context,
    private val prefs: Prefs = Prefs(context),
) : SpeechEngine, CommandTranscriber {

    private var listener: SpeechEngine.Listener? = null
    @Volatile private var running = false
    @Volatile private var paused = false

    private var voskModel: Model? = null
    @Volatile private var recognizer: Recognizer? = null
    @Volatile private var whisper: WhisperContext? = null
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
        // Load both models off the audio path; the loop starts once Vosk is ready.
        worker.execute { loadWhisper() }
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

    /** Resolve + load the whisper command model (pushed → internal cache → bundled asset). */
    private fun loadWhisper() {
        val path = resolveWhisperModel() ?: run {
            Log.w(TAG, "no whisper model — commands fall back to Vosk transcript")
            return
        }
        runCatching {
            whisper = WhisperContext.fromFile(path)
            Log.i(TAG, "whisper command model loaded: $path")
        }.onFailure { Log.e(TAG, "whisper load failed", it) }
    }

    /** First `.bin` pushed under `…/files/whisper` (mirrored to internal for a real-fs mmap), else cached. */
    private fun resolveWhisperModel(): String? {
        val internal = File(context.filesDir, WHISPER_FILE)
        val ext = context.getExternalFilesDir(null)?.let { File(it, WHISPER_DIR) }
        val pushed = ext?.takeIf { it.isDirectory }?.listFiles { f -> f.name.endsWith(".bin") }?.firstOrNull()
        if (pushed != null && (!internal.exists() || pushed.lastModified() > internal.lastModified())) {
            runCatching { pushed.copyTo(internal, overwrite = true) }
                .onFailure { Log.e(TAG, "mirror whisper model failed", it) }
        }
        return internal.takeIf { it.exists() && it.length() > 0 }?.absolutePath
    }

    // ---- recognizer + vocabulary -------------------------------------------

    private fun startVocabularyAndLoop() {
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

    @SuppressLint("MissingPermission") // RECORD_AUDIO is auto-granted to the device-owner kiosk
    private fun startAudioLoop() {
        if (audioThread != null) return
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        val bufBytes = maxOf(minBuf, SAMPLE_RATE / 5 * 2) // ~200ms, 16-bit mono
        audioThread = thread(name = "hybrid-audio", isDaemon = true) {
            val record = runCatching {
                AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, CHANNEL, ENCODING, bufBytes * 2)
            }.getOrElse { Log.e(TAG, "AudioRecord init failed", it); return@thread }
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord not initialized"); record.release(); return@thread
            }
            val bytes = ByteArray(bufBytes)
            val floats = FloatArray(bufBytes / 2)
            record.startRecording()
            Log.i(TAG, "hybrid listening @ ${SAMPLE_RATE}Hz")
            while (running) {
                val n = record.read(bytes, 0, bytes.size)
                if (n <= 0 || paused) continue
                val samples = pcm16ToFloat(bytes, n, floats)
                appendToRing(floats, samples)
                feedVosk(bytes, n)
            }
            runCatching { record.stop(); record.release() }
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

    override fun transcribeCommand(): String? {
        val w = whisper ?: return null
        val audio = synchronized(ringLock) {
            val to = writeIndex
            var from = wakeIndex
            if (to - from > ring.size) from = to - ring.size
            val size = (to - from).toInt().coerceAtLeast(0)
            FloatArray(size) { ring[((from + it) % ring.size).toInt()] }
        }
        if (audio.size < SAMPLE_RATE / 4) return null // <250ms — nothing useful
        return runCatching { w.transcribe(audio) }
            .onFailure { Log.e(TAG, "whisper transcribe failed", it) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
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
        runCatching { whisper?.release() }; whisper = null
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
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val RING_SECONDS = 14
        private const val PREROLL_SAMPLES = SAMPLE_RATE / 2 // 0.5s lookback before wake
        private const val REFRESH_MINUTES = 5L

        private const val VOSK_ASSET_DIR = "model-en-us"
        private const val VOSK_DIR = "vosk-model"
        private const val WHISPER_DIR = "whisper"       // pushed: …/files/whisper/*.bin
        private const val WHISPER_FILE = "whisper.bin"  // internal mirror

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
