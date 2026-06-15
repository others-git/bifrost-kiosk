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
}
