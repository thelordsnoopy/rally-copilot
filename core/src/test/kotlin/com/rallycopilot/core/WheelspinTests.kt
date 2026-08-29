package com.rallycopilot.core

import com.rallycopilot.core.obd.WheelspinDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wheelspin from rpm against ground speed. The ratios below are a 320d's, roughly:
 * rpm per m/s, first gear tallest.
 */
class WheelspinTests {

    private val ratios = listOf(430.0, 245.0, 165.0, 122.0, 97.0, 80.0)

    /** Hold [rpm] at [speed] for [secs], returning the final state. */
    private fun hold(
        d: WheelspinDetector, secs: Double, rpm: Int?, speed: Double,
        accel: Double = 0.5, startMs: Long = 0,
    ): WheelspinDetector.State {
        var st = d.state
        var t = startMs
        repeat((secs * 10).toInt()) {
            st = d.tick(t, rpm, speed, accel, ratios)
            t += 100
        }
        return st
    }

    @Test
    fun `a car in gear reads hooked up`() {
        val d = WheelspinDetector()
        // 3rd gear, 20 m/s: 165 * 20 = 3300 rpm. Exactly a gear the car has.
        val st = hold(d, 2.0, rpm = 3300, speed = 20.0)
        assertEquals(WheelspinDetector.Verdict.HOOKED_UP, st.verdict)
        assertFalse(st.spinning)
        assertFalse(d.spunSinceReset)
    }

    @Test
    fun `every real gear reads hooked up, first included`() {
        for ((i, r) in ratios.withIndex()) {
            val d = WheelspinDetector()
            val speed = 6.0 + i * 4
            val st = hold(d, 2.0, rpm = (r * speed).toInt(), speed = speed)
            assertEquals("gear ${i + 1} must not read as spin",
                WheelspinDetector.Verdict.HOOKED_UP, st.verdict)
        }
    }

    @Test
    fun `rpm no gear can explain is wheelspin`() {
        val d = WheelspinDetector()
        // 4 m/s with 2600 rpm implies a ratio of 650 — half again over first gear.
        // The wheels are turning far faster than the car is moving.
        val st = hold(d, 2.0, rpm = 2600, speed = 4.0, accel = 1.0)
        assertEquals(WheelspinDetector.Verdict.SUSPECTED, st.verdict)
        assertTrue(st.spinning)
        assertTrue(d.spunSinceReset)
        assertTrue("should report how far over", (st.excess ?: 0.0) > 0.4)
    }

    @Test
    fun `a brief rev blip is not a slide`() {
        val d = WheelspinDetector()
        hold(d, 2.0, rpm = 3300, speed = 20.0)
        // 200 ms of flare — a heel-and-toe downshift, not traction lost.
        val st = hold(d, 0.2, rpm = 9000, speed = 20.0, startMs = 2_000)
        assertFalse("must persist before it counts", st.spinning)
    }

    @Test
    fun `a rev-match under heavy braking is not counted`() {
        val d = WheelspinDetector()
        // Same impossible ratio, but the car is shedding speed hard: this is the
        // clutch, not the tyres.
        val st = hold(d, 2.0, rpm = 2600, speed = 4.0, accel = -4.0)
        assertFalse(st.spinning)
    }

    @Test
    fun `nothing is claimed before the gearing is learned`() {
        val d = WheelspinDetector()
        // Two ratios is not a gearbox: an rpm/speed that would scream wheelspin
        // against the real set must stay UNKNOWN while the fit is this thin.
        var st = d.state
        var t = 0L
        repeat(20) { st = d.tick(t, 5000, 4.0, 1.0, listOf(430.0, 245.0)); t += 100 }
        assertEquals(WheelspinDetector.Verdict.UNKNOWN, st.verdict)
        assertFalse(d.spunSinceReset)
    }

    @Test
    fun `no rpm, no verdict`() {
        val d = WheelspinDetector()
        val st = hold(d, 2.0, rpm = null, speed = 20.0)
        assertEquals(WheelspinDetector.Verdict.UNKNOWN, st.verdict)
    }

    @Test
    fun `crawling says nothing - dividing by a tiny speed proves nothing`() {
        val d = WheelspinDetector()
        val st = hold(d, 2.0, rpm = 2000, speed = 1.0)
        assertEquals(WheelspinDetector.Verdict.UNKNOWN, st.verdict)
        assertFalse(d.spunSinceReset)
    }

    @Test
    fun `the flag holds briefly after the wheels hook back up`() {
        val d = WheelspinDetector()
        var st = hold(d, 2.0, rpm = 2600, speed = 4.0, accel = 1.0)
        assertTrue(st.spinning)
        // Traction returns; the flag should linger for the hold, then clear.
        st = hold(d, 0.5, rpm = 1720, speed = 4.0, startMs = 2_000)
        assertTrue("still held", st.spinning)
        st = hold(d, 1.0, rpm = 1720, speed = 4.0, startMs = 10_000)
        assertFalse("hold expired", st.spinning)
        assertTrue("but the drive-long flag stays until reset", d.spunSinceReset)
        d.resetSpin()
        assertFalse(d.spunSinceReset)
    }
}
