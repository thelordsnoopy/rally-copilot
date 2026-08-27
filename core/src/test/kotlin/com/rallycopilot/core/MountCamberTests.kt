package com.rallycopilot.core

import com.rallycopilot.core.imu.CamberEstimator
import com.rallycopilot.core.imu.MountAlignment
import com.rallycopilot.core.imu.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class MountAlignmentTests {

    /** Phone mounted rotated 30 deg in the horizontal plane; gravity straight down phone-Z. */
    private val yaw = Math.toRadians(30.0)
    private val gravity = Vec3(0.0, 0.0, -9.81)
    private fun carForwardInPhone(mag: Double) = Vec3(mag * cos(yaw), mag * sin(yaw), 0.0)

    /**
     * Drive a plausible sequence at 10 Hz: alternating hard-accelerate and
     * hard-brake phases with a consistent speed trace, which is what the polarity
     * vote needs. [dvdtSign] lets a test corrupt the per-sample dv/dt sign — the
     * drive-42 failure mode — without corrupting the physics.
     */
    private fun drive(
        m: MountAlignment,
        phases: Int = 8,
        dvdtSign: (sampleIndex: Int, trueDvDt: Double) -> Double = { _, d -> d },
    ) {
        var t = 0L
        var speed = 5.0
        var idx = 0
        repeat(phases) { phase ->
            val accelerating = phase % 2 == 0
            val dvdt = if (accelerating) 2.5 else -2.5
            repeat(30) { // 3 s per phase at 10 Hz
                m.tick(t, carForwardInPhone(dvdt), gravity, dvdtSign(idx, dvdt), speed)
                speed = (speed + dvdt * 0.1).coerceAtLeast(2.0)
                t += 100; idx++
            }
        }
    }

    @Test
    fun `alignment converges to the mount yaw from accel and brake events`() {
        val m = MountAlignment()
        drive(m)
        assertTrue("aligned after sustained accel/brake phases", m.isAligned)
        val f = m.forward!!
        assertEquals(cos(yaw), f.x, 0.05)
        assertEquals(sin(yaw), f.y, 0.05)
    }

    @Test
    fun `a coin-flipped dvdt sign still aligns with the right polarity`() {
        // The drive-42 regression: speed lags the accelerometer, so per-sample
        // dv/dt agreed with the true event direction only 54% of the time and the
        // signed sum never cohered. The axis must not depend on that sign at all,
        // and the polarity must come from the speed trace instead.
        val m = MountAlignment()
        drive(m, phases = 10, dvdtSign = { i, d -> if (i % 2 == 0) d else -d })
        assertTrue("axis and polarity must survive per-sample sign garbage", m.isAligned)
        val f = m.forward!!
        assertEquals("polarity resolved forward, not backward", cos(yaw), f.x, 0.05)
        assertEquals(sin(yaw), f.y, 0.05)
    }

    @Test
    fun `gentle driving never aligns`() {
        val m = MountAlignment()
        var t = 0L
        repeat(200) {
            m.tick(t, carForwardInPhone(0.4), gravity, 0.4, 10.0)
            t += 100
        }
        assertFalse(m.isAligned)
    }

    @Test
    fun `incoherent directions refuse to align`() {
        val m = MountAlignment()
        var t = 0L
        var speed = 5.0
        // Event directions spread round the compass with a consistent speed trace:
        // whatever the votes say, the folded axis must stay too diffuse to trust.
        repeat(120) { i ->
            val a = Math.toRadians(i * 137.0)
            val dvdt = if ((i / 30) % 2 == 0) 2.5 else -2.5
            m.tick(t, Vec3(2.5 * cos(a), 2.5 * sin(a), 0.0), gravity, dvdt, speed)
            speed = (speed + dvdt * 0.1).coerceAtLeast(2.0)
            t += 100
        }
        assertFalse(m.isAligned)
    }

    @Test
    fun `no polarity votes, no alignment`() {
        // Perfect line of events but a speed trace that never clearly changes
        // across a window: the axis is known, which END is forward is not.
        val m = MountAlignment()
        var t = 0L
        repeat(100) { i ->
            val dvdt = if (i % 2 == 0) 2.0 else -2.0 // dv/dt flaps, speed goes nowhere
            m.tick(t, carForwardInPhone(dvdt), gravity, dvdt, 10.0)
            t += 100
        }
        assertFalse("an axis without a direction must not claim alignment", m.isAligned)
        assertTrue("but the line itself should be coherent", m.coherence >= 0.72)
    }
}

class CamberEstimatorTests {
    private val flatGravity = Vec3(0.0, 0.0, -9.81)

    /** Phone aligned with the car: aligned mount via accel/brake phases + speed trace. */
    private fun aligned(): MountAlignment {
        val m = MountAlignment()
        var t = 0L
        var speed = 5.0
        repeat(8) { phase ->
            val dvdt = if (phase % 2 == 0) 2.5 else -2.5
            repeat(30) {
                m.tick(t, Vec3(dvdt, 0.0, 0.0), flatGravity, dvdt, speed)
                speed = (speed + dvdt * 0.1).coerceAtLeast(2.0)
                t += 100
            }
        }
        check(m.isAligned) { "test rig failed to align the mount" }
        return m
    }

    @Test
    fun `flat road reads near zero camber`() {
        val c = CamberEstimator(aligned())
        val deg = c.tick(Vec3(0.0, 0.0, 0.0), flatGravity, speedMps = 15.0)
        assertNotNull(deg)
        assertEquals(0.0, deg!!, 0.5)
    }

    @Test
    fun `road leaning left reads positive camber`() {
        val c = CamberEstimator(aligned())
        // Car (and phone) rolled 5 deg: gravity gains a +Y (car-left) component.
        val roll = Math.toRadians(5.0)
        val leanedGravity = Vec3(0.0, 9.81 * sin(roll), -9.81 * cos(roll))
        var deg: Double? = null
        repeat(10) { deg = c.tick(Vec3(0.0, 0.0, 0.0), leanedGravity, 15.0) }
        assertEquals(5.0, deg!!, 0.7)
    }

    @Test
    fun `mid-corner samples are rejected`() {
        val c = CamberEstimator(aligned())
        // Strong lateral acceleration: polluted by body roll -> refuse to sample.
        assertNull(c.tick(Vec3(0.0, 4.0, 0.0), flatGravity, 15.0))
    }

    @Test
    fun `no reading before the mount aligns`() {
        val c = CamberEstimator(MountAlignment())
        assertNull(c.tick(Vec3(0.0, 0.0, 0.0), flatGravity, 15.0))
    }
}
