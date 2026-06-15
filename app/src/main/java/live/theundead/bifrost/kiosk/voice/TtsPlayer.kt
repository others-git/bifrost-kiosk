package live.theundead.bifrost.kiosk.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * Speaks Bifrost's `said` reply with the on-device Android TTS engine.
 *
 * On-device TTS (not server-served audio) keeps the satellite working with zero
 * extra server contract — Bifrost returns text, the tablet voices it. [speak]
 * reports completion so the pipeline can resume listening (half-duplex).
 */
class TtsPlayer(context: Context) {
    private var ready = false
    private var onDone: (() -> Unit)? = null

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
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
            Log.w(TAG, "TTS init failed: $status")
        }
    }

    /** Speak [text]; [whenDone] fires after playback (or immediately if TTS is down). */
    fun speak(text: String, whenDone: () -> Unit) {
        if (!ready || text.isBlank()) { whenDone(); return }
        onDone = whenDone
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    fun shutdown() {
        runCatching { tts.stop(); tts.shutdown() }
    }

    companion object {
        private const val TAG = "TtsPlayer"
        private const val UTTERANCE_ID = "bifrost_reply"
    }
}
