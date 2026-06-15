package live.theundead.bifrost.kiosk.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    // ---- Grammar token exposure: the wake word must stay recognizable under a
    // constrained Vosk grammar, so the homophone word tokens are injected.

    @Test fun grammarTokensIncludeBifrostHomophoneWords() {
        val tokens = WakeWord.grammarTokens("bifrost")
        // The split words the small model actually emits for "bifrost".
        for (t in listOf("by", "frost", "buy", "bi", "be", "bifrost")) {
            assertTrue("missing token '$t'", tokens.contains(t))
        }
    }

    @Test fun grammarTokensAreDedupedLowercaseSingleWords() {
        val tokens = WakeWord.grammarTokens("bifrost")
        assertEquals(tokens.distinct(), tokens)
        assertTrue(tokens.none { it.contains(' ') })
        assertTrue(tokens.all { it == it.lowercase() })
    }

    @Test fun grammarTokensForCustomWakeWordAreItsOwnTokens() {
        // No curated homophones for a custom word: at least its own token is present.
        assertEquals(listOf("jarvis"), WakeWord.grammarTokens("jarvis"))
        assertEquals(listOf("hey", "computer"), WakeWord.grammarTokens("Hey Computer"))
    }

    @Test fun grammarTokensEmptyForBlankWakeWord() {
        assertTrue(WakeWord.grammarTokens("").isEmpty())
    }

    @Test fun aliasesForBifrostIncludeKnownRenderings() {
        val aliases = WakeWord.aliasesFor("bifrost")
        assertTrue(aliases.contains("bifrost"))
        assertTrue(aliases.contains("by frost"))
        assertTrue(aliases.contains("buy frost"))
    }
}
