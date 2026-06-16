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

/**
 * Optional capability for engines that can transcribe the **command** utterance
 * with a higher-accuracy model than the always-on wake recognizer (the hybrid:
 * light Vosk for wake spotting, whisper for the command). The pipeline uses this
 * when present and falls back to the wake recognizer's transcript otherwise, so
 * a missing whisper model degrades gracefully.
 */
interface CommandTranscriber {
    /** Mark "the command starts about here" — called the instant the wake word fires. */
    fun noteWake()

    /**
     * Transcribe the buffered audio captured since [noteWake] with the
     * high-accuracy model. Returns null if unavailable (no model / not ready), so
     * the caller falls back to the wake recognizer's transcript.
     */
    fun transcribeCommand(): String?
}
