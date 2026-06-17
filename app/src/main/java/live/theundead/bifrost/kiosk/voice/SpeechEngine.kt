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
 * Optional capability for engines that own the mic and can hand back the raw
 * **command** audio (the utterance after the wake word). The pipeline sends that
 * to the server for high-accuracy transcription (`/api/voice/listen` → Speaches/
 * whisper) and falls back to the wake recognizer's own transcript (`/command`)
 * when the server has no transcription model or is unreachable — so voice keeps
 * working regardless. The light Vosk model always does wake spotting on-device.
 */
interface CommandTranscriber {
    /** Mark "the command starts about here" — called the instant the wake word fires. */
    fun noteWake()

    /**
     * The audio captured since [noteWake] as a 16 kHz mono 16-bit WAV, ready to
     * POST to the server STT. Null if too short / nothing buffered.
     */
    fun commandAudioWav(): ByteArray?
}
