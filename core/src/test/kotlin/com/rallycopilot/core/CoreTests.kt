package com.rallycopilot.core

import com.rallycopilot.core.advisor.NoteComposer
import com.rallycopilot.core.geo.Curvature
import com.rallycopilot.core.geo.Geo
import com.rallycopilot.core.geo.Polyline
import com.rallycopilot.core.geo.XY
import com.rallycopilot.core.model.Conditions
import com.rallycopilot.core.model.Corner
import com.rallycopilot.core.model.CornerObservation
import com.rallycopilot.core.model.Direction
import com.rallycopilot.core.model.DriverProfile
import com.rallycopilot.core.model.FeedbackAnswer
import com.rallycopilot.core.model.HorizonCorner
import com.rallycopilot.core.model.LatLon
import com.rallycopilot.core.model.SeverityBand
import com.rallycopilot.core.model.SeverityTable
import com.rallycopilot.core.obd.Elm327
import com.rallycopilot.core.obd.GearInference
import com.rallycopilot.core.profile.Learning
import com.rallycopilot.core.report.IncidentDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class GeoTests {
    @Test
    fun `haversine on a known pair`() {
        // Stroud to Gloucester is roughly 14 km
        val stroud = LatLon(51.7457, -2.2178)
        val gloucester = LatLon(51.8642, -2.2380)
        val d = Geo.haversineM(stroud, gloucester)
        assertTrue("got $d", d in 13_000.0..14_500.0)
    }

    @Test
    fun `circumradius of points on a 50m circle is 50m`() {
        val r = 50.0
        val a = XY(r * cos(0.1), r * sin(0.1))
        val b = XY(r * cos(0.5), r * sin(0.5))
        val c = XY(r * cos(0.9), r * sin(0.9))
        assertEquals(r, Curvature.circumradius(a, b, c), 0.01)
    }

    @Test
    fun `projection onto a straight east-west line`() {
        val line = listOf(LatLon(51.7, -2.3), LatLon(51.7, -2.2))
        val cum = Polyline.cumulative(line)
        // Point slightly north of the midpoint
        val p = LatLon(51.7005, -2.25)
        val proj = Polyline.project(line, cum, p)
        assertEquals(cum.last() / 2, proj.distanceAlongM, cum.last() * 0.02)
        assertTrue(proj.lateralOffsetM in 40.0..70.0) // ~55m at this latitude
    }

    @Test
    fun `bearing diff wraps correctly`() {
        assertEquals(20.0, Geo.bearingDiffDeg(350.0, 10.0), 1e-9)
        assertEquals(180.0, Geo.bearingDiffDeg(0.0, 180.0), 1e-9)
    }
}

class SeverityTests {
    @Test
    fun `severity table maps radii to bands`() {
        val t = SeverityTable.DEFAULT
        assertEquals(SeverityBand.HAIRPIN, t.bandFor(8.0))
        assertEquals(SeverityBand.ONE, t.bandFor(20.0))
        assertEquals(SeverityBand.FOUR, t.bandFor(100.0))
        assertEquals(SeverityBand.SIX, t.bandFor(300.0))
        assertEquals(SeverityBand.FLAT, t.bandFor(1000.0))
    }
}

class ProfileTests {
    private fun obs(band: SeverityBand, aLat: Double, constrained: Boolean = false) =
        CornerObservation(
            runId = 1, cornerId = (Math.random() * 1e9).toLong(), tMs = 0,
            band = band, minRadiusM = 50.0, vEntryMps = 20.0, vMinMps = 15.0, vExitMps = 20.0,
            aLatObserved = aLat, mapConfidence = 0.9, pathConfidence = 0.9,
            wasConstrained = constrained, conditions = Conditions.DRY,
        )

    @Test
    fun `cold start returns the spirited seed`() {
        val p = DriverProfile.COLD_START
        assertEquals(DriverProfile.SEED_A_LAT, p.aLatFor(SeverityBand.THREE), 1e-9)
    }

    @Test
    fun `constrained corners are filtered out of learning`() {
        val rows = List(10) { obs(SeverityBand.THREE, 3.0) } +
            List(10) { obs(SeverityBand.THREE, 9.0, constrained = true) }
        val (aLat, _) = Learning.derive(rows)
        // p80 of the unconstrained 3.0s only — the 9.0 constrained rows must not leak in
        assertEquals(3.0, aLat[SeverityBand.THREE]!!, 0.01)
    }

    @Test
    fun `session ratchet limits movement to 5 percent`() {
        val current = DriverProfile(
            aLatByBand = mapOf(SeverityBand.THREE to 4.0),
            sampleCountByBand = mapOf(SeverityBand.THREE to 50),
        )
        // New session says 6.0 — a 50% jump. Must be clamped to +5%.
        val rows = List(20) { obs(SeverityBand.THREE, 6.0) }
        val updated = Learning.applySession(current, rows)
        assertEquals(4.0 * 1.05, updated.aLatByBand[SeverityBand.THREE]!!, 0.001)
    }

    @Test
    fun `feedback is asymmetric hard bigger than easy`() {
        val p = DriverProfile.COLD_START
        val easier = Learning.applyFeedback(p, FeedbackAnswer.EASY)
        val harder = Learning.applyFeedback(p, FeedbackAnswer.HARD)
        assertEquals(1.04, easier.pushFactor, 1e-9)
        assertEquals(0.95, harder.pushFactor, 1e-9)
        assertTrue(abs(1.0 - harder.pushFactor) > abs(1.0 - easier.pushFactor))
    }

    @Test
    fun `push factor clamps at bounds`() {
        var p = DriverProfile.COLD_START
        repeat(20) { p = Learning.applyFeedback(p, FeedbackAnswer.EASY) }
        assertEquals(1.15, p.pushFactor, 1e-9)
        repeat(40) { p = Learning.applyFeedback(p, FeedbackAnswer.HARD) }
        assertEquals(0.85, p.pushFactor, 1e-9)
    }

    @Test
    fun `onset detection finds where driving starts in earnest`() {
        // 10 gentle corners (2 m/s2) then 15 committed ones (6 m/s2)
        val session = List(10) { obs(SeverityBand.THREE, 2.0) } +
            List(15) { obs(SeverityBand.THREE, 6.0) }
        val onset = Learning.onsetIndex(session)
        assertTrue("onset=$onset", onset in 8..12)
    }

    @Test
    fun `percentile interpolates`() {
        val v = listOf(1.0, 2.0, 3.0, 4.0, 5.0)
        assertEquals(5.0, Learning.percentileOf(v, 1.0), 1e-9)
        assertEquals(3.0, Learning.percentileOf(v, 0.5), 1e-9)
    }
}

class ObdTests {
    @Test
    fun `parses standard mode 01 responses`() {
        assertEquals(60, Elm327.speedKph("41 0D 3C"))
        assertEquals(3000, Elm327.rpm("41 0C 2E E0"))
        assertEquals(50, Elm327.coolantC("41 05 5A"))
        assertEquals(0.5, Elm327.throttle01("41 11 80")!!, 0.01)
        assertEquals(12.6, Elm327.batteryV("12.6V")!!, 0.001)
        assertNull(Elm327.speedKph("NO DATA"))
    }

    @Test
    fun `gear inference clusters ratios and infers gears`() {
        val g = GearInference()
        // Synthetic car: gear ratios give rpm/speed of 400, 220, 140 (1st..3rd)
        val ratios = listOf(400.0, 220.0, 140.0)
        for (r in ratios) for (i in 0 until 200) {
            val v = 5.0 + (i % 40) * 0.3
            g.addSample((r * v).toInt(), v)
        }
        g.refit()
        assertEquals(3, g.learnedRatios.size)
        assertEquals(2, g.currentGear(rpm = (220 * 10).toInt(), speedMps = 10.0))
        assertNull(g.currentGear(rpm = 3100, speedMps = 10.0)) // between gears
    }
}

class IncidentTests {
    @Test
    fun `hard stop after a spike is flagged`() {
        val d = IncidentDetector()
        // cruising at 25 m/s
        var t = 0L
        d.tick(t, 25.0)
        // massive decel over 1.5s to zero
        t += 500; d.tick(t, 12.0)
        t += 500; d.tick(t, 3.0)
        t += 500
        assertTrue(d.tick(t, 0.2))
    }

    @Test
    fun `normal braking is not an incident`() {
        val d = IncidentDetector()
        var t = 0L
        var v = 25.0
        while (v > 0) { d.tick(t, v); v -= 1.5; t += 500 } // ~3 m/s2, normal stop
        assertFalse(d.tick(t, 0.0))
    }
}

class ComposerTests {
    private fun hc(id: Long, band: SeverityBand, aheadM: Double, arc: Double = 30.0) = HorizonCorner(
        corner = Corner(id, 1, aheadM, aheadM + arc / 2, aheadM + arc, Direction.LEFT,
            50.0, 60.0, 40.0, arc, 0.9),
        distanceAheadM = aheadM, pathConfidence = 0.9, band = band,
        modifiers = listOf(com.rallycopilot.core.model.Modifier.TIGHTENS),
        vTargetMps = 20.0, brakingPointM = aheadM - 50, triggerDistanceM = aheadM - 100,
    )

    @Test
    fun `link distances rounded to vocabulary`() {
        assertEquals("d_100", NoteComposer.linkKey(97.0))
        assertEquals("d_1000", NoteComposer.linkKey(1400.0))
        assertNull(NoteComposer.linkKey(20.0))
    }

    @Test
    fun `burst compression drops links then modifiers but never corners`() {
        val chain = listOf(
            hc(1, SeverityBand.THREE, 100.0),
            hc(2, SeverityBand.TWO, 200.0),
            hc(3, SeverityBand.FOUR, 300.0),
        )
        val gaps = listOf(null, 70.0, 70.0)
        // Give every clip a fat duration so compression must engage fully.
        val u = NoteComposer.compose(chain, gaps, detail = NoteComposer.Detail(),
            deadlineDistanceM = 100.0, budgetMs = 2000, durationOf = { 600 })
        // Fully compressed: exactly one key per corner, no links, no modifiers.
        assertEquals(3, u.clipKeys.size)
        assertEquals(listOf("left_three", "left_two", "left_four"), u.clipKeys)
        assertTrue(u.urgent) // contains a TWO
    }

    @Test
    fun `uncompressed keeps links and modifiers`() {
        val chain = listOf(hc(1, SeverityBand.FOUR, 100.0), hc(2, SeverityBand.FIVE, 260.0))
        val gaps = listOf(null, 100.0)
        val u = NoteComposer.compose(chain, gaps, detail = NoteComposer.Detail(),
            deadlineDistanceM = 100.0, budgetMs = 60_000, durationOf = { 300 })
        assertEquals(listOf("left_four", "tightens", "d_100", "left_five", "tightens"), u.clipKeys)
        assertFalse(u.urgent)
    }
}
