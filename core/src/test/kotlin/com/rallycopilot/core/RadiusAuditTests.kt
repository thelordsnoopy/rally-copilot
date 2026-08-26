package com.rallycopilot.core

import com.rallycopilot.core.advisor.Advisor
import com.rallycopilot.core.advisor.NoteComposer
import com.rallycopilot.core.horizon.HorizonBuilder
import com.rallycopilot.core.knowledge.AuditStore
import com.rallycopilot.core.knowledge.CornerAudit
import com.rallycopilot.core.knowledge.RadiusAuditor
import com.rallycopilot.core.model.Corner
import com.rallycopilot.core.model.Direction
import com.rallycopilot.core.model.DriverProfile
import com.rallycopilot.core.model.HorizonCorner
import com.rallycopilot.core.model.Modifier
import com.rallycopilot.core.model.SeverityBand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.12: the "map is lying" detector, and hedged calls. */

private class MemAuditStore : AuditStore {
    val map = HashMap<Long, CornerAudit>()
    override fun get(cornerId: Long) = map[cornerId]
    override fun put(a: CornerAudit) { map[a.cornerId] = a }
}

class RadiusAuditorTests {

    /** Drive one pass through corner [id]: mapped radius [rMap], at [v] m/s with the
     *  IMU reading [aLat] m/s² mid-corner. */
    private fun pass(
        a: RadiusAuditor, id: Long, rMap: Double, v: Double, aLat: Double,
        accuracy: Double = 5.0,
    ) {
        repeat(5) { a.tick(id, rMap, v, aLat, accuracy) }
        a.tick(null, null, v, 0.0, accuracy) // leave the corner
    }

    @Test
    fun `an agreeing pass produces no advice`() {
        val store = MemAuditStore()
        val a = RadiusAuditor(store)
        // R = v²/aLat = 20²/8 = 50 m, map says 50 m: the map is right.
        pass(a, 1, rMap = 50.0, v = 20.0, aLat = 8.0)
        assertEquals(1, store.map[1L]!!.passes)
        assertNull(a.adviceFor(1))
    }

    @Test
    fun `one wild mismatch hedges but does not correct`() {
        val a = RadiusAuditor(MemAuditStore())
        // Implied R = 15²/9 = 25 m; map claims 50 m — the road is much tighter.
        pass(a, 1, rMap = 50.0, v = 15.0, aLat = 9.0)
        val advice = a.adviceFor(1)!!
        assertTrue("hedge on a single pass", advice.hedge)
        assertEquals("no correction from one pass", 1.0, advice.radiusFactor, 1e-9)
    }

    @Test
    fun `two consistent passes correct the radius, and only downward`() {
        val a = RadiusAuditor(MemAuditStore())
        pass(a, 1, rMap = 50.0, v = 15.0, aLat = 9.0)
        pass(a, 1, rMap = 50.0, v = 15.0, aLat = 9.0)
        val advice = a.adviceFor(1)!!
        assertFalse("measurement now trusted, no hedge", advice.hedge)
        assertEquals(0.5, advice.radiusFactor, 0.02) // 25/50, floored at 0.5
        assertTrue("corrections only tighten", advice.radiusFactor < 1.0)
    }

    @Test
    fun `a road gentler than mapped is hedged, never sped up`() {
        val a = RadiusAuditor(MemAuditStore())
        // Implied R = 30²/4.5 = 200 m; map claims 60 m — over-called, not dangerous.
        pass(a, 1, rMap = 60.0, v = 30.0, aLat = 4.5)
        pass(a, 1, rMap = 60.0, v = 30.0, aLat = 4.5)
        val advice = a.adviceFor(1)!!
        assertTrue("say the doubt out loud", advice.hedge)
        assertEquals("but NEVER raise the radius", 1.0, advice.radiusFactor, 1e-9)
    }

    @Test
    fun `bad GPS, low speed and gentle cornering never produce a pass`() {
        val store = MemAuditStore()
        val a = RadiusAuditor(store)
        pass(a, 1, rMap = 50.0, v = 15.0, aLat = 9.0, accuracy = 25.0) // GPS poor
        pass(a, 2, rMap = 50.0, v = 4.0, aLat = 9.0)                   // crawling
        pass(a, 3, rMap = 50.0, v = 15.0, aLat = 1.0)                  // not cornering
        repeat(5) { a.tick(4, 50.0, 15.0, null, 5.0) }                 // mount not aligned
        a.closePass()
        assertTrue("nothing qualified", store.map.isEmpty())
    }

    @Test
    fun `evidence accumulates by EMA so one later good pass softens the verdict`() {
        val store = MemAuditStore()
        val a = RadiusAuditor(store)
        pass(a, 1, rMap = 50.0, v = 15.0, aLat = 9.0) // ratio 0.5
        pass(a, 1, rMap = 50.0, v = 20.0, aLat = 8.0) // ratio 1.0 — map right this time
        val ema = store.map[1L]!!.ratioEma
        assertEquals(0.75, ema, 0.01)
    }
}

class HedgedCallTests {

    private fun corner(id: Long, rM: Double) = Corner(
        id, 1, 0.0, 30.0, 60.0, Direction.LEFT, rM, rM, rM, 60.0, 0.9,
    )

    private fun raw(vararg corners: Pair<Corner, Double>) = HorizonBuilder.RawHorizon(
        steps = emptyList(), totalLengthM = 800.0, confidenceAtEnd = 0.9,
        corners = corners.map { HorizonBuilder.RawCorner(it.first, it.second, 0.9, true) },
        hazards = emptyList(),
    )

    @Test
    fun `audit correction retightens the band and slows the target`() {
        val store = MemAuditStore()
        // 90 m mapped (a FOUR), but two passes measured it at half that (a TWO/THREE).
        store.put(CornerAudit(7, passes = 3, ratioEma = 0.5))
        val auditor = RadiusAuditor(store)
        val plain = Advisor(DriverProfile.COLD_START)
        val audited = Advisor(DriverProfile.COLD_START).apply {
            radiusAuditLookup = { auditor.adviceFor(it) }
        }
        val before = plain.annotate(raw(corner(7, 90.0) to 300.0), 25.0, 0L).corners.single()
        val after = audited.annotate(raw(corner(7, 90.0) to 300.0), 25.0, 0L).corners.single()
        assertTrue("band must tighten", after.band.ordinal < before.band.ordinal)
        assertTrue("target must drop", after.vTargetMps < before.vTargetMps)
        assertEquals("the corner keeps the MAP radius as reference",
            90.0, after.corner.minRadiusM, 1e-9)
    }

    @Test
    fun `single-pass mismatch speaks maybe before the call`() {
        val store = MemAuditStore()
        store.put(CornerAudit(7, passes = 1, ratioEma = 0.5))
        val auditor = RadiusAuditor(store)
        val advisor = Advisor(DriverProfile.COLD_START).apply {
            radiusAuditLookup = { auditor.adviceFor(it) }
        }
        val hc = advisor.annotate(raw(corner(7, 90.0) to 300.0), 25.0, 0L).corners.single()
        assertTrue(Modifier.MAYBE in hc.modifiers)
        val keys = NoteComposer.cornerKeys(hc, NoteComposer.Detail())
        assertEquals("doubt lands first", "maybe", keys.first())
    }

    @Test
    fun `maybe and caution never stack`() {
        val hc = HorizonCorner(
            corner = corner(1, 30.0), distanceAheadM = 200.0, pathConfidence = 0.4,
            band = SeverityBand.TWO,
            modifiers = listOf(Modifier.MAYBE, Modifier.CAUTION),
            vTargetMps = 15.0, brakingPointM = 150.0, triggerDistanceM = 120.0,
        )
        val keys = NoteComposer.cornerKeys(hc, NoteComposer.Detail())
        assertEquals(1, keys.count { it == "maybe" || it == "caution" })
        assertEquals("maybe", keys.first())
    }
}
