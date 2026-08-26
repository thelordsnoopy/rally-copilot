package com.rallycopilot.core

import com.rallycopilot.core.advisor.NoteComposer
import com.rallycopilot.core.engine.MapStore
import com.rallycopilot.core.horizon.HorizonBuilder
import com.rallycopilot.core.knowledge.KnowledgeMath
import com.rallycopilot.core.knowledge.RoadBucket
import com.rallycopilot.core.matcher.MapMatcher
import com.rallycopilot.core.model.Conditions
import com.rallycopilot.core.model.Corner
import com.rallycopilot.core.model.CornerObservation
import com.rallycopilot.core.model.Direction
import com.rallycopilot.core.model.DriverProfile
import com.rallycopilot.core.model.Edge
import com.rallycopilot.core.model.Fix
import com.rallycopilot.core.model.Hazard
import com.rallycopilot.core.model.HorizonCorner
import com.rallycopilot.core.model.Junction
import com.rallycopilot.core.model.LatLon
import com.rallycopilot.core.model.MatchedPosition
import com.rallycopilot.core.model.Modifier
import com.rallycopilot.core.model.SeverityBand
import com.rallycopilot.core.obd.Elm327
import com.rallycopilot.core.profile.Learning
import com.rallycopilot.core.profile.StyleDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression tests for the full-debug fixes (v0.7.0). */

/** One straight north-south edge with a single stored corner. */
private class OneEdgeMap(val e: Edge, val corners: List<Corner>) : MapStore {
    override fun edgesNear(p: LatLon, radiusM: Double) = listOf(e)
    override fun edge(id: Long) = if (id == e.id) e else null
    override fun junction(nodeId: Long): Junction? = null
    override fun cornersOn(edgeId: Long) = if (edgeId == e.id) corners else emptyList()
    override fun hazardsOn(edgeId: Long) = emptyList<Hazard>()
    override fun isEmptyAt(p: LatLon) = false
}

private fun straightEdge(lengthM: Double = 400.0): Edge {
    // Northwards from Stroud; ~5 m spacing.
    val n = (lengthM / 5.0).toInt() + 1
    val pts = List(n) { LatLon(51.7400 + it * 5.0 / 111_111.0, -2.2200) }
    return Edge(
        id = 7, fromNodeId = 1, toNodeId = 2, lengthM = lengthM,
        name = "Test Lane", ref = null, highwayClass = "tertiary",
        maxspeedKph = null, oneway = false, geometry = pts,
    )
}

class DirectionMirrorTests {
    private val corner = Corner(
        id = 99, edgeId = 7,
        startOffsetM = 100.0, apexOffsetM = 120.0, endOffsetM = 140.0,
        direction = Direction.LEFT,
        minRadiusM = 30.0, entryRadiusM = 80.0, exitRadiusM = 40.0,
        arcLengthM = 40.0, confidence = 0.9,
    )
    private val edge = straightEdge()
    private val builder = HorizonBuilder(OneEdgeMap(edge, listOf(corner)))

    private fun pos(offset: Double, forward: Boolean) = MatchedPosition(
        tMs = 0, edgeId = 7, offsetM = offset, forward = forward,
        speedMps = 15.0, bearingDeg = if (forward) 0.0 else 180.0, confidence = 0.9,
    )

    @Test
    fun `forward traversal keeps stored direction and radii`() {
        val raw = builder.build(pos(10.0, forward = true))!!
        val rc = raw.corners.single()
        assertEquals(Direction.LEFT, rc.corner.direction)
        assertEquals(80.0, rc.corner.entryRadiusM, 1e-9)
        assertEquals(40.0, rc.corner.exitRadiusM, 1e-9)
        assertTrue(rc.forward)
        assertEquals(90.0, rc.distanceAheadM, 1.0) // corner entry 100, from offset 10
    }

    @Test
    fun `reverse traversal mirrors direction and swaps entry with exit`() {
        // Driving from the far end back: the stored LEFT is the driver's RIGHT.
        val raw = builder.build(pos(390.0, forward = false))!!
        val rc = raw.corners.single()
        assertEquals(Direction.RIGHT, rc.corner.direction)
        assertEquals(40.0, rc.corner.entryRadiusM, 1e-9)
        assertEquals(80.0, rc.corner.exitRadiusM, 1e-9)
        assertFalse(rc.forward)
        // Entry in travel space: lengthM - endOffset = 260; from offset 390 → 130 ahead.
        assertEquals(250.0, rc.distanceAheadM, 1.0)
    }
}

class MatcherConfidenceTests {
    @Test
    fun `confidence approaches 1 on a lone road at speed`() {
        val edge = straightEdge()
        val matcher = MapMatcher(OneEdgeMap(edge, emptyList()))
        // Moving north at 15 m/s right on the line, with a good bearing.
        val fix = Fix(
            tMs = 0, lat = 51.7409, lon = -2.2200,
            speedMps = 15.0, bearingDeg = 0.0, accuracyM = 5.0,
        )
        val m = matcher.match(fix)
        assertNotNull(m)
        assertTrue(m!!.forward)
        // The reverse-direction hypothesis must be all but eliminated by heading.
        assertTrue("confidence=${m.confidence}", m.confidence > 0.9)
    }

    @Test
    fun `missing bearing is neutral not north`() {
        val edge = straightEdge()
        val matcher = MapMatcher(OneEdgeMap(edge, emptyList()))
        val fix = Fix(
            tMs = 0, lat = 51.7409, lon = -2.2200,
            speedMps = 15.0, bearingDeg = Double.NaN, accuracyM = 5.0,
        )
        // Must still match (NaN bearing must not poison scoring), at lower confidence.
        val m = matcher.match(fix)
        assertNotNull(m)
    }
}

class NegativeEvidenceTests {
    @Test
    fun `explicit no walks slow events back and never earns a caution`() {
        var b = RoadBucket(edgeId = 1, bucket = 4)
        // Three prompts, three explicit NOs — the old code turned this into a caution.
        repeat(3) {
            b = KnowledgeMath.applyNegativeAnswer(b)
        }
        assertEquals(0, b.slowEvents)
        assertEquals(3, b.cleanPasses)
        assertFalse(KnowledgeMath.warrantsCaution(b))
        assertEquals(1.0, b.speedFactor, 1e-9)
    }

    @Test
    fun `one rough second does not flag a caution`() {
        val b = KnowledgeMath.addRoughness(RoadBucket(1, 0), 5.0) // one violent window
        assertFalse(KnowledgeMath.warrantsCaution(b))
        // But a genuinely rough stretch (several windows) still does.
        var rough = RoadBucket(1, 1)
        repeat(4) { rough = KnowledgeMath.addRoughness(rough, 4.0) }
        assertTrue(KnowledgeMath.warrantsCaution(rough))
    }

    @Test
    fun `clean passes decay roughness`() {
        var b = RoadBucket(1, 0)
        repeat(4) { b = KnowledgeMath.addRoughness(b, 4.0) }
        assertTrue(KnowledgeMath.warrantsCaution(b))
        repeat(12) { b = KnowledgeMath.applyCleanPass(b) }
        assertFalse(KnowledgeMath.warrantsCaution(b))
    }
}

class LearningMergeTests {
    @Test
    fun `bands absent from a session survive applySession`() {
        val current = DriverProfile(
            aLatByBand = mapOf(SeverityBand.TWO to 5.0, SeverityBand.FOUR to 4.0),
            sampleCountByBand = mapOf(SeverityBand.TWO to 30, SeverityBand.FOUR to 30),
        )
        // A session containing only THREE-band corners.
        val rows = List(10) {
            CornerObservation(
                runId = 1, cornerId = it.toLong(), tMs = 0,
                band = SeverityBand.THREE, minRadiusM = 50.0,
                vEntryMps = 20.0, vMinMps = 15.0, vExitMps = 20.0,
                aLatObserved = 4.5, mapConfidence = 0.9, pathConfidence = 0.9,
                wasConstrained = false, conditions = Conditions.DRY,
            )
        }
        val updated = Learning.applySession(current, rows)
        assertEquals(5.0, updated.aLatByBand[SeverityBand.TWO]!!, 1e-9)
        assertEquals(4.0, updated.aLatByBand[SeverityBand.FOUR]!!, 1e-9)
        assertEquals(4.5, updated.aLatByBand[SeverityBand.THREE]!!, 1e-9)
    }
}

class ShiftRpmTests {
    @Test
    fun `upshifts record pre-shift revs across the clutch gap`() {
        val det = StyleDetector()
        val profile = DriverProfile.COLD_START
        var t = 0L
        fun tick(rpm: Int?, gear: Int?) {
            det.tick(StyleDetector.Sample(t, 20.0, rpm, 0.5, gear, null), profile)
            t += 200
        }
        // Two spirited upshifts, each through a realistic clutch gap where gear
        // inference reads null while the revs fall. The SHIFT rpm is 3200 both
        // times; the rpm visible when the new gear registers is ~2300.
        repeat(8) { tick(3200, 1) }
        tick(2800, null); tick(2400, null); tick(2300, null)
        tick(2300, 2)
        repeat(8) { tick(3200, 2) }
        tick(2800, null); tick(2400, null); tick(2300, null)
        tick(2300, 3)
        repeat(8) { tick(2600, 3) }
        // Shift vote (weight 1.3) on the E90 thresholds (2200..3200): pre-shift 3200
        // rates 1.0; the post-gap 2300 the old code recorded rated ~0.1 and dragged
        // the blend under 0.4. With the fix the blend clears the spirited entry bar.
        assertTrue("score=${det.score}", det.score > 0.5)
    }
}

class MultiEcuPidTests {
    @Test
    fun `supported pid masks from multiple responders are unioned`() {
        // Two ECUs answer 0100 (headers off): first supports only 0x0C/0x0D,
        // second also flags 0x11. Taking just the first would hide the throttle PID.
        val raw = "41 00 00 18 00 00 \r 41 00 00 18 80 00"
        val pids = Elm327.supportedPids(0x00, raw)
        assertTrue(0x0C in pids)
        assertTrue(0x0D in pids)
        assertTrue(0x11 in pids)
    }

    @Test
    fun `nul bytes from clone dongles are stripped`() {
        assertEquals(60, Elm327.speedKph("41 0D 3C\u0000"))
    }
}

class CautionKeyTests {
    @Test
    fun `caution modifier is spoken before the corner call`() {
        val hc = HorizonCorner(
            corner = Corner(1, 1, 0.0, 15.0, 30.0, Direction.RIGHT, 30.0, 40.0, 40.0, 30.0, 0.3),
            distanceAheadM = 100.0, pathConfidence = 0.9, band = SeverityBand.TWO,
            modifiers = listOf(Modifier.CAUTION, Modifier.LONG),
            vTargetMps = 15.0, brakingPointM = 50.0, triggerDistanceM = 30.0,
        )
        assertEquals(listOf("caution", "right_two", "long"), NoteComposer.cornerKeys(hc, includeGear = false))
    }
}

class SustainedStyleTests {
    private val profile = DriverProfile.COLD_START

    private fun feed(
        d: StyleDetector, fromS: Int, toS: Int,
        speed: (Int) -> Double,
        rpm: Int? = null, pedal: Double? = null,
        nearCorner: (Int) -> Double? = { null },
        onTick: (Int) -> Unit = {},
    ) {
        for (i in fromS * 5 until toS * 5) { // 5 Hz
            d.tick(
                StyleDetector.Sample(
                    tMs = i * 200L, speedMps = speed(i),
                    rpm = rpm, pedal01 = pedal, gear = null, aLatMps2 = null,
                    nearestCornerM = nearCorner(i),
                ),
                profile,
            )
            onTick(i)
        }
    }

    @Test
    fun `a short pull never reads as spirited`() {
        val d = StyleDetector()
        // Gentle cruising, one 10-second full-bore pull, gentle again. The pull's
        // commitment never holds for the 20 s sustain, so nothing may be logged.
        feed(d, 0, 40, { 22.0 }, rpm = 1900, pedal = 0.18)
        var everSpirited = false
        feed(d, 40, 50, { i -> 24.0 + 6.0 * kotlin.math.sin(i / 3.0) },
            rpm = 4200, pedal = 0.95, onTick = { if (d.isSpirited) everSpirited = true })
        feed(d, 50, 90, { 22.0 }, rpm = 1900, pedal = 0.18,
            onTick = { if (d.isSpirited) everSpirited = true })
        assertFalse(everSpirited)
    }

    @Test
    fun `a sustained committed run does read as spirited`() {
        val d = StyleDetector()
        // 45 s of hard, unbroken pressing on: aggressive accel, high revs, corners near.
        feed(d, 0, 45, { i -> 22.0 + 8.0 * kotlin.math.sin(i / 8.0) },
            rpm = 3600, pedal = 0.85, nearCorner = { 40.0 })
        assertTrue(d.isSpirited)
    }

    @Test
    fun `hard braking away from any corner breaks the run`() {
        val d = StyleDetector()
        // Committed for 15 s...
        feed(d, 0, 15, { i -> 24.0 + 6.0 * kotlin.math.sin(i / 8.0) },
            rpm = 3600, pedal = 0.85, nearCorner = { 40.0 })
        // ...then a hard ~4 m/s^2 brake on a straight, 500 m from any corner
        // (traffic), which must reset the sustain clock...
        var v = 26.0
        var t = 15 * 5
        while (v > 12.0) {
            d.tick(StyleDetector.Sample(t * 200L, v, 2000, 0.0, null, null, nearestCornerM = 500.0), profile)
            v -= 0.8; t++
        }
        assertFalse(d.isSpirited)
        val resumeS = t / 5 + 1
        // ...and only 20+ unbroken seconds later may the verdict flip.
        feed(d, resumeS, resumeS + 7, { i -> 24.0 + 6.0 * kotlin.math.sin(i / 8.0) },
            rpm = 3600, pedal = 0.85, nearCorner = { 40.0 })
        assertFalse(d.isSpirited) // ~7 s back in: still not sustained
        feed(d, resumeS + 7, resumeS + 40, { i -> 24.0 + 6.0 * kotlin.math.sin(i / 8.0) },
            rpm = 3600, pedal = 0.85, nearCorner = { 40.0 })
        assertTrue(d.isSpirited)
    }
}
