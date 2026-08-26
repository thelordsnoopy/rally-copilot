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

/** The "map is lying" detector: repeated measurement can tighten a corner, never loosen it. */

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
    fun `one pass is never enough to move a corner`() {
        val a = RadiusAuditor(MemAuditStore())
        // Implied R = 15²/9 = 25 m; map claims 50 m — the road looks much tighter,
        // but a single measurement does not get to rewrite the map.
        pass(a, 1, rMap = 50.0, v = 15.0, aLat = 9.0)
        assertNull("no correction from one pass", a.adviceFor(1))
    }

    @Test
    fun `two consistent passes correct the radius, and only downward`() {
        val a = RadiusAuditor(MemAuditStore())
        pass(a, 1, rMap = 50.0, v = 15.0, aLat = 9.0)
        pass(a, 1, rMap = 50.0, v = 15.0, aLat = 9.0)
        val advice = a.adviceFor(1)!!
        assertEquals(0.5, advice.radiusFactor, 0.02) // 25/50, floored at 0.5
        assertTrue("corrections only tighten", advice.radiusFactor < 1.0)
    }

    @Test
    fun `a road gentler than mapped is left alone, never sped up`() {
        val a = RadiusAuditor(MemAuditStore())
        // Implied R = 30²/4.5 = 200 m; map claims 60 m — over-called, not dangerous.
        pass(a, 1, rMap = 60.0, v = 30.0, aLat = 4.5)
        pass(a, 1, rMap = 60.0, v = 30.0, aLat = 4.5)
        // A driven line is always wider than the centreline, so this is the normal
        // reading, not news — and raising a suggested speed from it is never done.
        assertNull("gentler-than-mapped changes nothing", a.adviceFor(1))
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
    fun `a single pass leaves the call completely untouched`() {
        val store = MemAuditStore()
        store.put(CornerAudit(7, passes = 1, ratioEma = 0.5))
        val auditor = RadiusAuditor(store)
        val plain = Advisor(DriverProfile.COLD_START)
        val audited = Advisor(DriverProfile.COLD_START).apply {
            radiusAuditLookup = { auditor.adviceFor(it) }
        }
        val before = plain.annotate(raw(corner(7, 90.0) to 300.0), 25.0, 0L).corners.single()
        val after = audited.annotate(raw(corner(7, 90.0) to 300.0), 25.0, 0L).corners.single()
        assertEquals(before.band, after.band)
        assertEquals(before.vTargetMps, after.vTargetMps, 1e-9)
        val keys = NoteComposer.cornerKeys(after, NoteComposer.Detail())
        assertEquals("nothing hedging is ever spoken", "left_four", keys.first())
    }

    /**
     * "into" says two corners are one continuous piece of road. It used to sit in
     * the shape tier and was the third thing dropped when a burst ran long, so the
     * joined-up corners that most need the word were the ones that lost it.
     */
    @Test
    fun `into survives compression down to the bare calls`() {
        val hc = HorizonCorner(
            corner = corner(1, 30.0), distanceAheadM = 200.0, pathConfidence = 0.9,
            band = SeverityBand.TWO,
            modifiers = listOf(Modifier.INTO, Modifier.LONG, Modifier.TIGHTENS),
            vTargetMps = 15.0, brakingPointM = 150.0, triggerDistanceM = 120.0,
        )
        val bare = NoteComposer.Detail(
            speed = false, gear = false, dangerModifiers = false, shapeModifiers = false)
        val keys = NoteComposer.cornerKeys(hc, bare)
        assertTrue("into must survive", "into" in keys)
        assertFalse("but long must not", "long" in keys)
    }
}

/**
 * The corner you are actually driving through must survive to become an observation.
 *
 * The horizon is rebuilt whenever heading changes by more than 25°, which happens
 * part-way through every real corner, and a rebuilt horizon only lists corners still
 * AHEAD. The collector used to discard any tracker whose corner had vanished — so
 * every corner genuinely driven through was thrown away, and only gentle bends that
 * never triggered a rebuild reached the profile. One spirited drive over ten-plus
 * corners produced exactly one observation, in band five.
 */
class ObservationSurvivalTests {

    private fun corner(id: Long, rM: Double, arc: Double) = Corner(
        id, 1, 0.0, arc / 2, arc, Direction.LEFT, rM, rM, rM, arc, 0.9,
    )

    private fun horizonCorner(c: Corner, aheadM: Double) = HorizonCorner(
        corner = c, distanceAheadM = aheadM, pathConfidence = 0.9,
        band = SeverityBand.TWO, modifiers = emptyList(),
        vTargetMps = 14.0, brakingPointM = 0.0, triggerDistanceM = 0.0,
    )

    @Test
    fun `a corner driven through survives the mid-corner horizon rebuild`() {
        val col = com.rallycopilot.core.profile.ObservationCollector(
            runId = 1, conditions = com.rallycopilot.core.model.Conditions.DRY)
        val c = corner(1, 35.0, arc = 60.0)
        var ahead = 10.0
        var t = 0L
        val speed = 15.0
        // Approach: the corner is on the horizon and we enter it.
        repeat(3) {
            col.tick(t, speed, null, listOf(horizonCorner(c, ahead)), spiritedNow = true) { ahead }
            ahead -= 5.0; t += 333
        }
        // Turning in: heading swings, the horizon is rebuilt, and the corner we are
        // INSIDE is no longer listed on it.
        col.onHorizonRebuilt(emptyList())
        // Drive the rest of the corner with an empty horizon.
        repeat(30) {
            col.tick(t, speed, null, emptyList(), spiritedNow = true) { -999.0 }
            t += 333
        }
        val obs = col.observations
        assertEquals("the corner must produce exactly one observation", 1, obs.size)
        assertEquals(1L, obs[0].cornerId)
        assertTrue("entry speed recorded", obs[0].vEntryMps > 0)
        assertTrue("lateral g computed", obs[0].aLatObserved > 0)
    }

    @Test
    fun `an approaching corner that vanishes for real is still closed, not leaked`() {
        val col = com.rallycopilot.core.profile.ObservationCollector(
            runId = 1, conditions = com.rallycopilot.core.model.Conditions.DRY)
        val c = corner(2, 35.0, arc = 40.0)
        col.tick(0, 12.0, null, listOf(horizonCorner(c, 5.0)), spiritedNow = true) { 5.0 }
        col.onHorizonRebuilt(emptyList())
        // Long enough to run past the arc plus the margin at 12 m/s.
        var t = 100L
        repeat(60) { col.tick(t, 12.0, null, emptyList(), spiritedNow = true) { -999.0 }; t += 200 }
        assertEquals(1, col.observations.size)
    }
}
