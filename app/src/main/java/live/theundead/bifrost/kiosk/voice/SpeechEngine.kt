package live.theundead.bifrost.kiosk.voice

/**
 * Abstraction over the on-device speech recognizer so the wake-word/STT backend
 * is swappable (Vosk today; openWakeWord + a separate STT later — see README
 * open questions). The pipeline only depends on this seam.
 */
interface SpeechEngine {
    /** Emits a finalized transcript for one utterance (silence-bounded). */
    interface Listener {
        fun onTranscript(text: String)

        /** Live (non-finalized) hypothesis as the utterance is spoken. */
        fun onPartial(text: String) {}
    }

    /** Begin continuous recognition. Safe to call once. */
    fun start(listener: Listener)

    /** Stop feeding audio (half-duplex: while TTS is speaking). */
    fun pause()

    /** Resume after [pause]. */
    fun resume()

    /** Release the mic + model. */
    fun shutdown()
}
