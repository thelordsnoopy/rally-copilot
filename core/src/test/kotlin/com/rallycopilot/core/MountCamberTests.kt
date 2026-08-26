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

    @Test
    fun `alignment converges to the mount yaw from accel and brake events`() {
        val m = MountAlignment()
        repeat(20) { m.tick(carForwardInPhone(2.5), gravity, dvdtMps2 = 2.5) }      // accelerating
        repeat(20) { m.tick(carForwardInPhone(-3.0), gravity, dvdtMps2 = -3.0) }    // braking flips sign
        assertTrue(m.isAligned)
        val f = m.forward!!
        assertEquals(cos(yaw), f.x, 0.05)
        assertEquals(sin(yaw), f.y, 0.05)
    }

    @Test
    fun `gentle driving never aligns`() {
        val m = MountAlignment()
        repeat(200) { m.tick(carForwardInPhone(0.4), gravity, dvdtMps2 = 0.4) }
        assertFalse(m.isAligned)
    }

    @Test
    fun `incoherent directions refuse to align`() {
        val m = MountAlignment()
        // Random-ish directions: coherence must stay low.
        repeat(40) { i ->
            val a = Math.toRadians(i * 137.0)
            m.tick(Vec3(2.5 * cos(a), 2.5 * sin(a), 0.0), gravity, dvdtMps2 = 2.5)
        }
        assertFalse(m.isAligned)
    }
}

class CamberEstimatorTests {
    private val yaw = 0.0 // phone aligned with car for simplicity
    private val flatGravity = Vec3(0.0, 0.0, -9.81)

    private fun aligned(): MountAlignment {
        val m = MountAlignment()
        repeat(30) { m.tick(Vec3(2.5, 0.0, 0.0), flatGravity, 2.5) }
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
