package com.rallycopilot.core

import com.rallycopilot.core.advisor.Advisor
import com.rallycopilot.core.model.DriverProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Gravity is part of the braking sum, and it changes sign with the hill. */
class GradientBrakingTests {
    private val advisor = Advisor(DriverProfile.COLD_START)

    @Test
    fun `downhill needs more room than flat, uphill less`() {
        val v = 30.0; val target = 12.0
        val flat = advisor.brakingDistanceM(v, target, 0.0)
        val down = advisor.brakingDistanceM(v, target, -0.10)   // 10% descent
        val up = advisor.brakingDistanceM(v, target, 0.10)      // 10% climb
        assertTrue("downhill $down should exceed flat $flat", down > flat * 1.15)
        assertTrue("uphill $up should be under flat $flat", up < flat * 0.9)
    }

    @Test
    fun `a severe descent never reduces usable braking to nothing`() {
        val d = advisor.brakingDistanceM(30.0, 10.0, -0.30)
        assertTrue("finite and sane: $d", d.isFinite() && d in 50.0..600.0)
    }

    @Test
    fun `grade only matters when actually slowing`() {
        assertEquals(0.0, advisor.brakingDistanceM(10.0, 25.0, -0.2), 1e-9)
    }

    @Test
    fun `a corner driven the other way sees the opposite hill`() {
        // The grade is stored in the edge's forward frame; the horizon builder
        // negates it on reverse traversal, so a climb becomes a descent.
        val climb = advisor.brakingDistanceM(28.0, 12.0, 0.08)
        val descent = advisor.brakingDistanceM(28.0, 12.0, -0.08)
        assertTrue("the same corner is not the same both ways", descent > climb * 1.2)
    }
}
