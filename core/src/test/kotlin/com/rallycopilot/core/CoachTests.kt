package com.rallycopilot.core

import com.rallycopilot.core.advisor.Coach
import com.rallycopilot.core.model.Conditions
import com.rallycopilot.core.model.Corner
import com.rallycopilot.core.model.CornerObservation
import com.rallycopilot.core.model.Direction
import com.rallycopilot.core.model.HorizonCorner
import com.rallycopilot.core.model.SeverityBand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CoachTests {

    private fun hc(vTarget: Double) = HorizonCorner(
        corner = Corner(1, 1, 0.0, 15.0, 30.0, Direction.RIGHT, 50.0, 50.0, 50.0, 30.0, 0.9),
        distanceAheadM = 0.0, pathConfidence = 0.9, band = SeverityBand.THREE,
        modifiers = emptyList(), vTargetMps = vTarget,
        brakingPointM = 0.0, triggerDistanceM = 0.0,
    )

    private fun obs(
        vMin: Double, constrained: Boolean = false, spirited: Boolean = true, id: Long = 1,
    ) = CornerObservation(
        runId = 1, cornerId = id, tMs = 0, band = SeverityBand.THREE, minRadiusM = 50.0,
        vEntryMps = vMin + 4, vMinMps = vMin, vExitMps = vMin + 4,
        aLatObserved = vMin * vMin / 50.0, mapConfidence = 0.9, pathConfidence = 0.9,
        wasConstrained = constrained, conditions = Conditions.DRY, spirited = spirited,
    )

    /** The whole point: coaching must never talk over the road ahead. */
    @Test
    fun `stays silent when a corner is coming up`() {
        val c = Coach()
        c.onCornerClosed(obs(10.0), hc(20.0), 1_000)
        assertNull("no gap, no talking", c.poll(2_000, gapSeconds = 1.5, speaking = false))
        assertNotNull("straight ahead, now it can speak", c.poll(3_000, gapSeconds = 12.0, speaking = false))
    }

    @Test
    fun `stays silent while the co-driver is mid-sentence`() {
        val c = Coach()
        c.onCornerClosed(obs(10.0), hc(20.0), 1_000)
        assertNull(c.poll(2_000, gapSeconds = 30.0, speaking = true))
    }

    @Test
    fun `a verdict is dropped rather than queued once it is stale`() {
        val c = Coach()
        c.onCornerClosed(obs(10.0), hc(20.0), 1_000)
        // Busy road for half a minute: the moment has gone, say nothing later.
        assertNull(c.poll(30_000, gapSeconds = 30.0, speaking = false))
        assertNull(c.poll(31_000, gapSeconds = 30.0, speaking = false))
    }

    @Test
    fun `reads the corner correctly`() {
        val slow = Coach()
        slow.onCornerClosed(obs(12.0), hc(20.0), 0)     // 60% of target
        assertEquals("coach_more", slow.poll(1_000, 20.0, false))

        val hot = Coach()
        hot.onCornerClosed(obs(25.0), hc(20.0), 0)      // 125% of target
        assertEquals("coach_hot", hot.poll(1_000, 20.0, false))

        val ok = Coach()
        ok.onCornerClosed(obs(20.0), hc(20.0), 0)       // on the money
        assertEquals("coach_good", ok.poll(1_000, 20.0, false))
    }

    @Test
    fun `says nothing about corners you were not driving`() {
        val traffic = Coach()
        traffic.onCornerClosed(obs(8.0, constrained = true), hc(20.0), 0)
        assertNull("stuck behind someone teaches nothing", traffic.poll(1_000, 30.0, false))

        val pottering = Coach()
        pottering.onCornerClosed(obs(8.0, spirited = false), hc(20.0), 0)
        assertNull(pottering.poll(1_000, 30.0, false))
    }

    @Test
    fun `does not chatter`() {
        val c = Coach()
        c.onCornerClosed(obs(12.0, id = 1), hc(20.0), 0)
        assertNotNull(c.poll(1_000, 30.0, false))
        // Next corner closes straight after — must hold its tongue.
        c.onCornerClosed(obs(12.0, id = 2), hc(20.0), 5_000)
        assertNull(c.poll(6_000, 30.0, false))
    }

    @Test
    fun `praise is rarer than criticism`() {
        val c = Coach()
        c.onCornerClosed(obs(20.0, id = 1), hc(20.0), 0)
        assertEquals("coach_good", c.poll(1_000, 30.0, false))
        // A well-driven corner two minutes later: past the general cooldown, but
        // praise has its own, longer one, so nothing is said.
        c.onCornerClosed(obs(20.0, id = 2), hc(20.0), 120_000)
        assertNull(c.poll(121_000, 30.0, false))
    }
}
