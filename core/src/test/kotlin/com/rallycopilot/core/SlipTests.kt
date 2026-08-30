package com.rallycopilot.core

import com.rallycopilot.core.imu.SlipEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Is the car going where it is pointing? Two detectors:
 *
 *  - the impossible radius: speed / yaw below the car's physical turning circle
 *    (found drive 44's donut, which the old estimator never saw — 81°/s at 7 mph
 *    was UNDER its speed gate);
 *  - the angle comparison: gyro yaw integrated between GNSS fixes against the
 *    bearing change over the same window (replaced the guessed 700 ms rate lag
 *    that manufactured five phantom slides on that same drive).
 */
class SlipTests {

    /** Feed [secs] seconds of gyro at 10 Hz plus a fix each second whose bearing
     *  advances by [bearingStepDeg] — i.e. a course turning at that rate. */
    private fun drive(
        e: SlipEstimator, secs: Int, yawRadS: Double, bearingStepDeg: Double,
        speed: Double = 20.0, startMs: Long = 0, startBearing: Double = 0.0,
    ): SlipEstimator.State {
        var st = e.state
        var t = startMs
        var bearing = startBearing
        repeat(secs) {
            repeat(10) { st = e.onYaw(t, yawRadS, speed); t += 100 }
            bearing = (bearing + bearingStepDeg + 360.0) % 360.0
            st = e.onFix(t, bearing, speed)
        }
        return st
    }

    @Test
    fun `a gripping car reads neutral`() {
        val e = SlipEstimator()
        // 0.20 rad/s body yaw (anticlockwise = bearing FALLING) matched by the
        // course: ~11.5° of bearing change per second, same direction.
        val st = drive(e, 4, yawRadS = 0.20, bearingStepDeg = -Math.toDegrees(0.20))
        assertEquals(SlipEstimator.Verdict.NEUTRAL, st.verdict)
        assertFalse(st.sliding)
    }

    @Test
    fun `body turning far more than the path is oversteer`() {
        val e = SlipEstimator()
        // Gyro says 0.40 rad/s (~23°/s); the path only bends 11°/s. The 12°/s
        // difference is far over the 7° rigid-mount threshold.
        val st = drive(e, 3, yawRadS = 0.40, bearingStepDeg = -11.0)
        assertEquals(SlipEstimator.Verdict.OVERSTEER, st.verdict)
        assertTrue("must be flagged as sliding", st.sliding)
        assertTrue(e.slidSinceReset)
    }

    @Test
    fun `turning far less than the road demands is understeer`() {
        val e = SlipEstimator()
        // Path bends 20°/s; the body only rotates ~6°/s: pushing wide.
        val st = drive(e, 3, yawRadS = 0.10, bearingStepDeg = -20.0)
        assertEquals(SlipEstimator.Verdict.UNDERSTEER, st.verdict)
        assertTrue(st.sliding)
    }

    @Test
    fun `small disagreement inside the noise floor stays neutral`() {
        val e = SlipEstimator()
        // 3°/s of mismatch: under the 7° per-window threshold, which sits above
        // the measured p99 noise (~5°) of a real drive on a wedged mount.
        val st = drive(e, 4, yawRadS = 0.25, bearingStepDeg = -Math.toDegrees(0.20))
        assertEquals(SlipEstimator.Verdict.NEUTRAL, st.verdict)
        assertFalse(st.sliding)
    }

    @Test
    fun `the slide bar widens when the phone is moving in its holder`() {
        // 8 degrees of sideslip. On a wedged mount that is a slide; with the phone
        // shifting 12 degrees in its cradle it is inside the noise and must not be
        // called. Measured floors: rigid p99 ~5, loose p99 ~9.
        fun run(wobble: Double): Boolean {
            val e = SlipEstimator()
            var t = 0L
            var bearing = 0.0
            var sliding = false
            repeat(4) {
                repeat(10) { e.onYaw(t, Math.toRadians(19.0), 20.0); t += 100 }
                bearing = (bearing - 11.0 + 360.0) % 360.0   // path turns 11 deg/s
                sliding = e.onFix(t, bearing, 20.0, wobble).sliding
            }
            return sliding
        }
        assertTrue("a rigid mount must call it", run(0.0))
        assertFalse("a wobbling mount must not", run(12.0))
    }

    @Test
    fun `the donut - an impossible radius needs no GPS at all`() {
        val e = SlipEstimator()
        // Drive 44, +102.4 s: 81°/s of yaw at 7 mph (3.1 m/s) = 2.2 m implied
        // radius against a 5 m floor. No fixes are fed at all — a 1 Hz bearing
        // aliases on a spin and can never see one.
        var st = e.state
        var t = 0L
        repeat(10) { st = e.onYaw(t, 1.41, 3.1); t += 100 }
        assertEquals(SlipEstimator.Verdict.OVERSTEER, st.verdict)
        assertTrue("a donut is a slide", st.sliding)
        assertTrue(e.slidSinceReset)
        assertTrue(st.impliedRadiusM != null && st.impliedRadiusM!! < 5.0)
    }

    @Test
    fun `a parked phone twisted in the hand is not a donut`() {
        val e = SlipEstimator()
        var st = e.state
        var t = 0L
        // Same rotation, but the car is not moving: radius zero of speed zero.
        repeat(10) { st = e.onYaw(t, 1.41, 0.4); t += 100 }
        assertFalse(st.sliding)
        assertEquals(SlipEstimator.Verdict.UNKNOWN, st.verdict)
    }

    @Test
    fun `a flick of the wheel shorter than the sustain is not a slide`() {
        val e = SlipEstimator()
        var st = e.state
        var t = 0L
        repeat(2) { st = e.onYaw(t, 1.41, 3.1); t += 100 }   // 200 ms only
        assertFalse("must persist before it counts", st.sliding)
    }

    @Test
    fun `low-speed GPS bearing glitches are ignored by the window`() {
        val e = SlipEstimator()
        // Drive 47 logged a ±36° equal-and-opposite bearing pair at 9 mph
        // (4 m/s). Below windowMinSpeedMps the window must not judge at all.
        var t = 0L
        repeat(10) { e.onYaw(t, 0.02, 4.0); t += 100 }
        e.onFix(t, 10.0, 4.0)
        repeat(10) { e.onYaw(t, 0.02, 4.0); t += 100 }
        val st = e.onFix(t, 46.0, 4.0) // bearing leapt 36° with no body rotation
        assertFalse(st.sliding)
        assertEquals(SlipEstimator.Verdict.UNKNOWN, st.verdict)
    }

    @Test
    fun `the bearing is still rubble at 6 m per second`() {
        // Every false slide in drives 56-58 sat between 4.4 and 6.1 m/s, where the
        // p95 sideslip is 6.7-14.5° against 2.4° above 8 m/s. The worst of them was
        // 68° of "sideslip" logged while the gyro read 0.04 rad/s — a car going
        // dead straight. The window must stay silent through all of it.
        val e = SlipEstimator()
        var t = 0L
        repeat(10) { e.onYaw(t, 0.04, 6.0); t += 100 }
        e.onFix(t, 10.0, 6.0)
        repeat(10) { e.onYaw(t, 0.04, 6.0); t += 100 }
        val st = e.onFix(t, 78.0, 6.0) // 68° bearing leap, no body rotation
        assertFalse(st.sliding)
        assertEquals(SlipEstimator.Verdict.UNKNOWN, st.verdict)
    }

    @Test
    fun `driven radius comes from the course and needs real turning`() {
        val e = SlipEstimator()
        drive(e, 3, yawRadS = 0.20, bearingStepDeg = -Math.toDegrees(0.20))
        // 20 m/s at 0.2 rad/s of course = 100 m radius.
        val r = e.drivenRadiusM(20.0)
        assertTrue(r != null && r!! > 80 && r < 120)
        // Straight road: nothing to measure.
        val e2 = SlipEstimator()
        drive(e2, 3, yawRadS = 0.0, bearingStepDeg = 0.0)
        assertNull(e2.drivenRadiusM(20.0))
    }

    @Test
    fun `slidSinceReset holds until the collector clears it`() {
        val e = SlipEstimator()
        drive(e, 3, yawRadS = 0.40, bearingStepDeg = -11.0)
        assertTrue(e.slidSinceReset)
        // Long after the hold expires the flag must still be up...
        drive(e, 5, yawRadS = 0.0, bearingStepDeg = 0.0, startMs = 60_000)
        assertTrue(e.slidSinceReset)
        e.resetSlide()
        assertFalse(e.slidSinceReset)
    }

    @Test
    fun `the window verdict expires rather than lingering`() {
        val e = SlipEstimator()
        var st = drive(e, 3, yawRadS = 0.40, bearingStepDeg = -11.0)
        assertTrue(st.sliding)
        // Two seconds of straight driving later, the hold has lapsed.
        st = drive(e, 3, yawRadS = 0.0, bearingStepDeg = 0.0, startMs = 30_000)
        assertFalse(st.sliding)
    }
}
