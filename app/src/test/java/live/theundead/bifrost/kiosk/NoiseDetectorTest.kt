package live.theundead.bifrost.kiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure edge detector behind the mic-presence monitor: two-window
 * confirmation, quiet-only baseline learning, and the hangover — the logic the
 * hub's occupancy verdict ultimately rides on.
 */
class NoiseDetectorTest {

    private fun quietFor(d: NoiseDetector, windows: Int, startMs: Long, db: Double = -60.0): Long {
        var t = startMs
        repeat(windows) {
            d.onWindow(db, t)
            t += 250
        }
        return t
    }

    @Test
    fun `a single loud window is not presence - two consecutive confirm`() {
        val d = NoiseDetector(marginDb = 10.0)
        var t = quietFor(d, 8, 0)
        assertNull("one loud window must not fire", d.onWindow(-30.0, t))
        t += 250
        // A quiet window resets the confirmation count…
        assertNull(d.onWindow(-60.0, t))
        t += 250
        assertNull(d.onWindow(-30.0, t))
        t += 250
        // …two in a row fire the elevated edge exactly once.
        assertEquals(true, d.onWindow(-30.0, t))
        assertTrue(d.elevated)
        t += 250
        assertNull("no repeated edge while it stays loud", d.onWindow(-28.0, t))
    }

    @Test
    fun `quiet clears only after the hangover`() {
        val d = NoiseDetector(marginDb = 10.0)
        var t = quietFor(d, 8, 0)
        d.onWindow(-30.0, t)
        t += 250
        assertEquals(true, d.onWindow(-30.0, t))
        // Quiet windows inside the hangover keep it elevated (a conversation
        // pause), the first window past it emits the quiet edge.
        val loudAt = t
        t += 250
        while (t - loudAt < NoiseDetector.HANGOVER_MS) {
            assertNull(d.onWindow(-60.0, t))
            assertTrue(d.elevated)
            t += 250
        }
        assertEquals(false, d.onWindow(-60.0, t))
        assertFalse(d.elevated)
    }

    @Test
    fun `a permanently raised ambience converges and stops reading as presence`() {
        val d = NoiseDetector(marginDb = 10.0)
        // An AC spins up to a constant -35 dB and never stops. The detector
        // fires at first (it IS a change), but the slow loud-side learning must
        // converge within ~25min so the room doesn't read occupied forever.
        var t = 0L
        repeat(6000) {
            d.onWindow(-35.0, t)
            t += 250
        }
        assertTrue("baseline should have adapted upward: ${d.baselineDb}", d.baselineDb > -40.0)
        assertFalse("constant ambience must eventually clear", d.elevated)
        assertNull(d.onWindow(-32.0, t))
        t += 250
        assertNull(d.onWindow(-32.0, t))
        assertFalse("within-margin noise over an adapted baseline is ambience", d.elevated)
    }

    @Test
    fun `a conversation cannot quickly teach itself into the baseline`() {
        val d = NoiseDetector(marginDb = 10.0)
        var t = quietFor(d, 8, 0)
        d.onWindow(-30.0, t)
        t += 250
        d.onWindow(-30.0, t)
        // Five minutes of continuous speech: the baseline may creep, but far too
        // slowly to swallow the conversation — still elevated, threshold intact.
        repeat(1200) {
            t += 250
            d.onWindow(-30.0, t)
        }
        assertTrue("5min of speech must barely move the baseline: ${d.baselineDb}", d.baselineDb < -50.0)
        assertTrue(d.elevated)
    }

    @Test
    fun `sensitivity margins map low-medium-high`() {
        assertEquals(15.0, NoiseMonitor.marginDb("low"), 0.0)
        assertEquals(10.0, NoiseMonitor.marginDb("medium"), 0.0)
        assertEquals(10.0, NoiseMonitor.marginDb(null), 0.0)
        assertEquals(6.0, NoiseMonitor.marginDb("high"), 0.0)
    }
}
