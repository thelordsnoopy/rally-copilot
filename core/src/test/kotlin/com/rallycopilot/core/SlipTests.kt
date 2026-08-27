package com.rallycopilot.core

import com.rallycopilot.core.imu.SlipEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Is the car going where it is pointing? Gyro yaw rate vs GNSS course rate.
 *
 * Built because Chad drives a car with a welded diff on part-worn rear tyres, where
 * "0.9 g through that corner" may be a slide rather than grip — and the learning
 * loop, which has no absolute ceiling, would happily treat it as grip.
 */
class SlipTests {

    /** Drive [secs] seconds at 10 Hz with the given rates. */
    private fun run(
        e: SlipEstimator, secs: Double, yaw: Double, course: Double,
        speed: Double = 20.0, startMs: Long = 0,
    ): SlipEstimator.State {
        var st = e.state
        var t = startMs
        repeat((secs * 10).toInt()) {
            st = e.tick(t, yaw, course, speed)
            t += 100
        }
        return st
    }

    @Test
    fun `a gripping car reads neutral`() {
        val e = SlipEstimator()
        // 20 m/s round a 100 m radius: 0.2 rad/s, and the body follows the path.
        val st = run(e, 3.0, yaw = 0.20, course = 0.20)
        assertEquals(SlipEstimator.Verdict.NEUTRAL, st.verdict)
        assertFalse(st.sliding)
        assertEquals(1.0, st.ratio, 0.05)
    }

    @Test
    fun `the body rotating faster than the path is oversteer`() {
        val e = SlipEstimator()
        // Path still bending at 0.20, but the car has stepped out and is rotating
        // at 0.32 — the welded-diff-on-worn-rears signature.
        val st = run(e, 3.0, yaw = 0.32, course = 0.20)
        assertEquals(SlipEstimator.Verdict.OVERSTEER, st.verdict)
        assertTrue("must be flagged as sliding", st.sliding)
    }

    @Test
    fun `turning less than the road demands is understeer`() {
        val e = SlipEstimator()
        val st = run(e, 3.0, yaw = 0.12, course = 0.22)
        assertEquals(SlipEstimator.Verdict.UNDERSTEER, st.verdict)
        assertTrue(st.sliding)
    }

    @Test
    fun `a brief disagreement is not a slide`() {
        val e = SlipEstimator()
        run(e, 2.0, yaw = 0.20, course = 0.20)
        // 300 ms of divergence — inside the sustain window, so not yet a verdict.
        val st = run(e, 0.3, yaw = 0.34, course = 0.20, startMs = 2_000)
        assertFalse("must persist before it counts", st.sliding)
    }

    @Test
    fun `a straight road says nothing at all`() {
        val e = SlipEstimator()
        // Both rates near zero: the ratio of two small noisy numbers is meaningless.
        val st = run(e, 3.0, yaw = 0.02, course = 0.01)
        assertEquals(SlipEstimator.Verdict.UNKNOWN, st.verdict)
        assertFalse(st.sliding)
    }

    @Test
    fun `crawling says nothing either`() {
        val e = SlipEstimator()
        val st = run(e, 3.0, yaw = 0.30, course = 0.15, speed = 2.0)
        assertEquals(SlipEstimator.Verdict.UNKNOWN, st.verdict)
    }

    @Test
    fun `the driven radius comes from speed and course rate, with no map`() {
        val e = SlipEstimator()
        run(e, 3.0, yaw = 0.20, course = 0.20, speed = 20.0)
        val r = e.drivenRadiusM(20.0)!!
        assertEquals("v / courseRate = 20 / 0.2", 100.0, r, 5.0)
    }

    @Test
    fun `the driven radius is unavailable on a straight`() {
        val e = SlipEstimator()
        run(e, 3.0, yaw = 0.01, course = 0.01, speed = 20.0)
        assertNull(e.drivenRadiusM(20.0))
    }

    @Test
    fun `one rate turning alone says nothing`() {
        // The drive-42 regression class: the old gate said UNKNOWN only when BOTH
        // rates were small, so one noisy small number under a modest real one
        // produced verdicts. 8 of 16 observations were flagged slides at up to
        // 0.05 g that way. Yaw clearly turning, course not: no verdict.
        val e = SlipEstimator()
        val st = run(e, 3.0, yaw = 0.15, course = 0.03)
        assertEquals(SlipEstimator.Verdict.UNKNOWN, st.verdict)
        assertFalse(st.sliding)
    }

    @Test
    fun `a strong rotation the path never shows is a spin`() {
        val e = SlipEstimator()
        val st = run(e, 3.0, yaw = 0.50, course = 0.02)
        assertEquals(SlipEstimator.Verdict.OVERSTEER, st.verdict)
        assertTrue(st.sliding)
    }

    @Test
    fun `no cornering load, no slide`() {
        // Ratio 1.5 would read oversteer, but at 6.5 m/s x 0.15 rad/s the implied
        // lateral acceleration is 0.1 g — nothing slides there.
        val e = SlipEstimator()
        val st = run(e, 3.0, yaw = 0.15, course = 0.10, speed = 6.5)
        assertEquals(SlipEstimator.Verdict.UNKNOWN, st.verdict)
        assertFalse(st.sliding)
    }

    @Test
    fun `corner entry lag is not oversteer`() {
        // GNSS course rate trails the gyro by most of a second: on entry the body
        // is already turning while the reported path is not. The delayed-yaw
        // comparison must ride that out without declaring a slide.
        val e = SlipEstimator()
        var st = run(e, 0.7, yaw = 0.25, course = 0.02)
        assertFalse("entry transient must not slide", st.sliding)
        st = run(e, 3.0, yaw = 0.25, course = 0.25, startMs = 700)
        assertEquals(SlipEstimator.Verdict.NEUTRAL, st.verdict)
        assertFalse(st.sliding)
    }

    @Test
    fun `the slide flag latches until the corner is closed out`() {
        val e = SlipEstimator()
        run(e, 3.0, yaw = 0.34, course = 0.20)
        assertTrue(e.slidSinceReset)
        run(e, 3.0, yaw = 0.20, course = 0.20, startMs = 5_000)
        assertTrue("still latched for this corner", e.slidSinceReset)
        e.resetSlide()
        assertFalse(e.slidSinceReset)
    }
}
