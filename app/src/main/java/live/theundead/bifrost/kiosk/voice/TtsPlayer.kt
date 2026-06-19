package live.theundead.bifrost.kiosk.voice

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import live.theundead.bifrost.kiosk.Prefs
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Speaks Bifrost's `said` reply.
 *
 * Prefers **server-synthesized** audio from `POST /api/voice/speak` (so the
 * satellite uses the hub's configured TTS voice — e.g. a cloned voice — matching
 * the web/desktop clients), played via [MediaPlayer]. Falls back to the
 * **on-device** Android [TextToSpeech] whenever the hub has no TTS configured
 * (503) or is unreachable, so voice never goes silent.
 *
 * [speak] reports completion either way so the pipeline can resume listening
 * (half-duplex). The fetch runs off the main thread; playback is driven on the
 * main thread because [MediaPlayer] delivers its callbacks on the Looper of the
 * thread that created it.
 */
class TtsPlayer(context: Context, private val prefs: Prefs) {
    private val appContext = context.applicationContext
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null

    // On-device fallback engine.
    private var ready = false
    private var onDone: (() -> Unit)? = null
    private val tts: TextToSpeech = TextToSpeech(appContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            tts.language = Locale.getDefault()
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { onDone?.invoke() }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { onDone?.invoke() }
            })
        } else {
            Log.w(TAG, "on-device TTS init failed: $status")
        }
    }

    /** Speak [text]; [whenDone] fires once after playback (server audio, or the
     * on-device engine on failure) — or immediately if both are unavailable. */
    fun speak(text: String, whenDone: () -> Unit) {
        if (text.isBlank()) { whenDone(); return }
        io.execute {
            val audio = runCatching {
                BifrostVoiceClient(prefs.serverBase, prefs.voiceEndpoint, prefs.apiKey).speak(text)
            }.getOrNull()
            val file = audio?.let {
                runCatching {
                    File.createTempFile("bifrost_reply", ".audio", appContext.cacheDir)
                        .apply { writeBytes(it) }
                }.getOrNull()
            }
            if (file == null) {
                main.post { speakOnDevice(text, whenDone) }
            } else {
                main.post { playServerAudio(file, text, whenDone) }
            }
        }
    }

    /** Play server-synthesized audio from [file] (main thread). On any error,
     * clean up and fall back to on-device TTS. [whenDone] fires exactly once. */
    private fun playServerAudio(file: File, text: String, whenDone: () -> Unit) {
        val done = AtomicBoolean(false)
        try {
            releasePlayer()
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    if (!done.getAndSet(true)) { releasePlayer(); file.delete(); whenDone() }
                }
                setOnErrorListener { _, _, _ ->
                    // Playback failed mid-flight — fall back to on-device TTS.
                    if (!done.getAndSet(true)) {
                        releasePlayer(); file.delete(); speakOnDevice(text, whenDone)
                    }
                    true
                }
                setOnPreparedListener { start() }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.w(TAG, "server audio playback failed — on-device TTS", e)
            if (!done.getAndSet(true)) { releasePlayer(); file.delete(); speakOnDevice(text, whenDone) }
        }
    }

    private fun speakOnDevice(text: String, whenDone: () -> Unit) {
        if (!ready || text.isBlank()) { whenDone(); return }
        onDone = whenDone
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    private fun releasePlayer() {
        runCatching { player?.release() }
        player = null
    }

    fun shutdown() {
        runCatching { releasePlayer() }
        runCatching { tts.stop(); tts.shutdown() }
        io.shutdownNow()
    }

    companion object {
        private const val TAG = "TtsPlayer"
        private const val UTTERANCE_ID = "bifrost_reply"
    }
}
