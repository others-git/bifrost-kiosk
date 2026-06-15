package live.theundead.bifrost.kiosk.voice

/**
 * Pure wake-word logic, kept free of Android types so it is unit-testable on the
 * JVM. Given a full STT transcript and the configured wake word, decide whether
 * the utterance was addressed to Bifrost and, if so, what the command was.
 *
 * Lightweight CPU STT (Vosk small models) mishears "bifrost" often, so we accept
 * a few homophones. The engine finalizes an utterance on silence, so a typical
 * transcript is the whole phrase: "bifrost turn off the office lights".
 */
object WakeWord {

    /** Common Vosk small-model renderings of "bifrost". */
    private val BIFROST_ALIASES = listOf(
        "bifrost", "by frost", "be frost", "buy frost", "bi frost", "by frosch",
    )

    data class Match(val command: String)

    /**
     * @return a [Match] with the text after the wake word (possibly empty for a
     * bare wake, meaning "listening, awaiting command"), or null if the wake word
     * is absent.
     */
    fun parse(rawTranscript: String, wakeWord: String): Match? {
        val transcript = normalize(rawTranscript)
        if (transcript.isEmpty()) return null

        val aliases = buildList {
            add(normalize(wakeWord))
            if (normalize(wakeWord) == "bifrost") addAll(BIFROST_ALIASES)
        }.filter { it.isNotEmpty() }.distinct()

        // Prefer the earliest wake-word hit so everything after it is the command.
        var best: Int? = null
        var bestLen = 0
        for (alias in aliases) {
            val idx = indexOfPhrase(transcript, alias)
            if (idx >= 0 && (best == null || idx < best!!)) {
                best = idx
                bestLen = alias.length
            }
        }
        val at = best ?: return null
        val command = transcript.substring(at + bestLen).trim()
        return Match(command = stripPoliteness(command))
    }

    private fun normalize(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    /** Whole-word phrase search so "be frosty" doesn't match "be frost". */
    private fun indexOfPhrase(haystack: String, phrase: String): Int {
        if (phrase.isEmpty()) return -1
        val padded = " $haystack "
        val needle = " $phrase "
        val i = padded.indexOf(needle)
        return if (i < 0) -1 else i // index into padded; +1 offset cancels with leading space
    }

    private fun stripPoliteness(command: String): String {
        var c = command
        for (filler in listOf("please", "could you", "can you", "would you", "hey", "okay", "ok")) {
            c = c.removePrefix(filler).trim()
        }
        return c.trim()
    }
}
