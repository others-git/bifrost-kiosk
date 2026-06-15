package live.theundead.bifrost.kiosk.voice

/**
 * Pure wake-word logic, kept free of Android types so it is unit-testable on the
 * JVM. Given a full STT transcript and the configured wake word, decide whether
 * the utterance was addressed to Bifrost and, if so, what the command was.
 *
 * Lightweight CPU STT (Vosk small models) mishears "bifrost" often — most
 * commonly splitting it into two words ("by frost"). Rather than enumerate every
 * homophone, we normalize both the configured wake word and the heard leading
 * token(s) by lowercasing and collapsing spaces, then accept the lead if it is
 * within a small Levenshtein distance of the wake word (so "byfrost" vs
 * "bifrost" = distance 1 matches). An exact/startsWith fast path is kept. The
 * engine finalizes an utterance on silence, so a typical transcript is the whole
 * phrase: "bifrost turn off the office lights".
 */
object WakeWord {

    /** Common Vosk small-model renderings of "bifrost" (exact fast-path aliases). */
    private val BIFROST_ALIASES = listOf(
        "bifrost", "by frost", "be frost", "buy frost", "bi frost", "by frosch",
    )

    /** Max Levenshtein distance (on the space-collapsed candidate) for a fuzzy match. */
    private const val MAX_EDIT_DISTANCE = 2

    data class Match(val command: String)

    /**
     * @return a [Match] with the text after the wake word (possibly empty for a
     * bare wake, meaning "listening, awaiting command"), or null if the wake word
     * is absent.
     */
    fun parse(rawTranscript: String, wakeWord: String): Match? {
        val transcript = normalize(rawTranscript)
        if (transcript.isEmpty()) return null

        val wake = normalize(wakeWord)
        if (wake.isEmpty()) return null

        val aliases = buildList {
            add(wake)
            if (wake == "bifrost") addAll(BIFROST_ALIASES)
        }.filter { it.isNotEmpty() }.distinct()

        // Fast path: an exact (whole-word) alias hit anywhere. Prefer the earliest
        // so everything after it is the command. This keeps homophones working even
        // mid-utterance ("hey bifrost dim the kitchen").
        var best: Int? = null
        var bestLen = 0
        for (alias in aliases) {
            val idx = indexOfPhrase(transcript, alias)
            if (idx >= 0 && (best == null || idx < best!!)) {
                best = idx
                bestLen = alias.length
            }
        }
        if (best != null) {
            val command = transcript.substring(best!! + bestLen).trim()
            return Match(command = stripPoliteness(command))
        }

        // Fuzzy path: the wake word is mis-transcribed. Only consider the *start* of
        // the utterance (a wake word is an address, not a mid-sentence word), as 1
        // or 2 leading tokens collapsed to one candidate, within an edit-distance
        // tolerance relative to the configured wake word.
        val tokens = transcript.split(' ')
        val target = wake.replace(" ", "")
        val maxLead = minOf(2, tokens.size)
        for (lead in maxLead downTo 1) {
            val candidate = tokens.take(lead).joinToString("")
            // Guard: a single clean leading word that simply *extends* the wake word
            // ("bifrosty") is a different word, not a mishear — the whole-word fast
            // path already rejected it, so don't let edit-distance resurrect it.
            if (lead == 1 && candidate.length > target.length && candidate.startsWith(target)) {
                continue
            }
            if (levenshtein(candidate, target) <= editTolerance(target)) {
                val command = tokens.drop(lead).joinToString(" ").trim()
                return Match(command = stripPoliteness(command))
            }
        }
        return null
    }

    /**
     * Edit tolerance relative to the wake word length: capped at [MAX_EDIT_DISTANCE]
     * but never so large it would match unrelated short words (no more than ~⅓ of
     * the target's length).
     */
    private fun editTolerance(target: String): Int =
        minOf(MAX_EDIT_DISTANCE, target.length / 3)

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

    /** Classic iterative two-row Levenshtein distance. */
    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(
                    curr[j - 1] + 1,
                    prev[j] + 1,
                    prev[j - 1] + cost,
                )
            }
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return prev[b.length]
    }

    /** Strip the wake word (and its variants) from the front of a command string. */
    fun stripWake(rawCommand: String, wakeWord: String): String {
        val m = parse(rawCommand, wakeWord)
        return m?.command ?: stripPoliteness(normalize(rawCommand))
    }

    private fun stripPoliteness(command: String): String {
        var c = command
        for (filler in listOf("please", "could you", "can you", "would you", "hey", "okay", "ok")) {
            c = c.removePrefix(filler).trim()
        }
        return c.trim()
    }
}
