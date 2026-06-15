package live.theundead.bifrost.kiosk.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WakeWordTest {

    @Test fun extractsCommandAfterWakeWord() {
        val m = WakeWord.parse("bifrost turn off the office lights", "bifrost")
        assertEquals("turn off the office lights", m?.command)
    }

    @Test fun returnsNullWhenWakeWordAbsent() {
        assertNull(WakeWord.parse("turn off the office lights", "bifrost"))
    }

    @Test fun handlesLeadingFillerBeforeWakeWord() {
        val m = WakeWord.parse("hey bifrost dim the kitchen", "bifrost")
        assertEquals("dim the kitchen", m?.command)
    }

    @Test fun stripsPolitenessAfterWakeWord() {
        val m = WakeWord.parse("bifrost please play jazz in the den", "bifrost")
        assertEquals("play jazz in the den", m?.command)
    }

    @Test fun acceptsHomophoneRenderings() {
        // Vosk small models frequently mishear "bifrost".
        assertEquals("lights off", WakeWord.parse("by frost lights off", "bifrost")?.command)
        assertEquals("lights off", WakeWord.parse("be frost lights off", "bifrost")?.command)
    }

    @Test fun bareWakeWordYieldsEmptyCommand() {
        val m = WakeWord.parse("bifrost", "bifrost")
        assertEquals("", m?.command)
    }

    @Test fun normalizesPunctuationAndCase() {
        val m = WakeWord.parse("Bifrost, turn ON the Lights!", "bifrost")
        assertEquals("turn on the lights", m?.command)
    }

    @Test fun wholeWordMatchAvoidsSubstringFalsePositive() {
        // "bifrosty" should not count as the wake word.
        assertNull(WakeWord.parse("bifrosty thing happened", "bifrost"))
    }

    @Test fun customWakeWordHasNoHomophoneExpansion() {
        assertEquals("lights on", WakeWord.parse("jarvis lights on", "jarvis")?.command)
        assertNull(WakeWord.parse("by frost lights on", "jarvis"))
    }

    // ---- Fix 1: tolerant (edit-distance) wake-word matching for unanticipated mishears.

    @Test fun matchesExactBifrost() {
        assertEquals("", WakeWord.parse("bifrost", "bifrost")?.command)
    }

    @Test fun matchesSplitByFrost() {
        // The dominant small-model mishear: "bifrost" → "by frost" (two words).
        assertEquals("", WakeWord.parse("by frost", "bifrost")?.command)
    }

    @Test fun matchesByFrostWithCommand() {
        assertEquals(
            "turn off the lights",
            WakeWord.parse("by frost turn off the lights", "bifrost")?.command,
        )
    }

    @Test fun matchesBuyFrost() {
        assertEquals("lights off", WakeWord.parse("buy frost lights off", "bifrost")?.command)
    }

    @Test fun fuzzyMatchesUnlistedSingleTokenMishear() {
        // Not in the alias list; "bigfrost" is 1 edit from "bifrost".
        assertEquals("dim the den", WakeWord.parse("bigfrost dim the den", "bifrost")?.command)
    }

    @Test fun fuzzyMatchesUnlistedTwoWordMishear() {
        // "be frosty" collapses to "befrosty", 2 edits from "bifrost"; the whole-word
        // alias "be frost" won't match here (trailing "y"), so this exercises fuzzy.
        assertEquals("dim the den", WakeWord.parse("be frosty dim the den", "bifrost")?.command)
    }

    @Test fun rejectsUnrelatedLeadingWord() {
        assertNull(WakeWord.parse("turn off the lights", "bifrost"))
    }

    @Test fun stripWakeRemovesLeadingWakeAndVariants() {
        assertEquals("turn off the lights", WakeWord.stripWake("bifrost turn off the lights", "bifrost"))
        assertEquals("turn off the lights", WakeWord.stripWake("by frost turn off the lights", "bifrost"))
        // No wake word present — return the normalized text unchanged (post-wake remainder).
        assertEquals("turn off the lights", WakeWord.stripWake("turn off the lights", "bifrost"))
    }
}
