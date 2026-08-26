package com.rallycopilot.core

import com.rallycopilot.core.advisor.NoteComposer
import com.rallycopilot.core.model.Conditions
import com.rallycopilot.core.model.Corner
import com.rallycopilot.core.model.CornerObservation
import com.rallycopilot.core.model.Direction
import com.rallycopilot.core.model.DriverProfile
import com.rallycopilot.core.model.Hazard
import com.rallycopilot.core.model.HazardKind
import com.rallycopilot.core.model.HorizonCorner
import com.rallycopilot.core.model.HorizonHazard
import com.rallycopilot.core.model.Modifier
import com.rallycopilot.core.model.SeverityBand
import com.rallycopilot.core.obd.GearInference
import com.rallycopilot.core.profile.StyleDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.8.0: gear calibration, quiet mode, and what the co-driver actually says. */

class GearCalibrationTests {

    /** Synthetic 6-speed: rpm-per-m/s for each gear. */
    private val ratios = listOf(420.0, 245.0, 170.0, 128.0, 103.0, 86.0)

    private fun drive(g: GearInference, passes: Int = 4) {
        // Sweep each gear through its usable speed range, as real driving would.
        repeat(passes) {
            for (r in ratios) {
                var v = 1400.0 / r
                while (v * r < 3600.0) {
                    g.addSample((v * r).toInt(), v)
                    v += 0.4
                }
            }
        }
    }

    @Test
    fun `gearing is learned from ordinary driving`() {
        val g = GearInference()
        drive(g)
        g.refit()
        assertTrue("calibrated", g.isCalibrated)
        assertEquals(6, g.gearCount)
        // Learned ratios should match the synthetic car within a few percent.
        for ((i, expected) in ratios.withIndex()) {
            val got = g.learnedRatios[i]
            assertTrue("gear ${i + 1}: got $got want $expected",
                kotlin.math.abs(got - expected) / expected < 0.06)
        }
    }

    @Test
    fun `calibration survives a round trip so gear calls work from the first corner`() {
        val a = GearInference()
        drive(a)
        a.refit()
        val saved = a.serialise()

        val b = GearInference()
        assertFalse(b.isCalibrated)
        b.restore(saved)
        assertTrue("restored calibration", b.isCalibrated)
        assertEquals(a.gearCount, b.gearCount)
        // And it can immediately answer the question the co-driver asks.
        assertNotNull(b.gearForSpeed(15.0))
    }

    @Test
    fun `knows how quick each gear is`() {
        val g = GearInference()
        drive(g)
        g.refit()
        // Top speed per gear = redline / ratio, and gears get progressively longer.
        val tops = (1..g.gearCount).mapNotNull { g.topSpeedOf(it) }
        assertEquals(g.gearCount, tops.size)
        for (i in 1 until tops.size) {
            assertTrue("gear ${i + 1} must be longer than gear $i", tops[i] > tops[i - 1])
        }
        // Second gear on this car tops out somewhere sane for a road car.
        val second = g.topSpeedOf(2)!!
        assertTrue("2nd tops at ${second * 2.23694} mph", second * 2.23694 in 25.0..70.0)
    }

    @Test
    fun `corner gear lands in the usable band and never bounces off the limiter`() {
        val g = GearInference()
        drive(g)
        g.refit()
        // A 30 mph corner (13.4 m/s).
        val gear = g.gearForSpeed(13.4)
        assertNotNull(gear)
        val revs = g.rpmAt(gear!!, 13.4)!!
        assertTrue("revs=$revs", revs < g.redlineRpm * 0.85)
        assertTrue("revs=$revs should be pulling, not bogging", revs > 1200.0)
    }

    @Test
    fun `exit revs follow how the driver actually shifts`() {
        val lazy = GearInference()
        val keen = GearInference()
        lazy.restore("420,245,170,128;4600;1900,2000,1950,2050")
        keen.restore("420,245,170,128;4600;3600,3800,3700,3650")
        assertTrue("keen driver should be given more revs",
            keen.exitRpm > lazy.exitRpm + 400)
    }
}

class QuietModeTests {
    private val profile = DriverProfile.COLD_START

    private fun feed(
        d: StyleDetector, fromS: Int, toS: Int,
        speed: (Int) -> Double, rpm: Int? = null, pedal: Double? = null,
    ) {
        for (i in fromS * 5 until toS * 5) {
            d.tick(
                StyleDetector.Sample(
                    tMs = i * 200L, speedMps = speed(i), rpm = rpm, pedal01 = pedal,
                    gear = null, aLatMps2 = null, nearestCornerM = 40.0,
                ),
                profile,
            )
        }
    }

    @Test
    fun `pottering along keeps the co-driver quiet`() {
        val d = StyleDetector()
        feed(d, 0, 60, { 13.0 }, rpm = 1600, pedal = 0.15)
        assertFalse("should be quiet", d.isPressingOn)
    }

    @Test
    fun `the co-driver wakes up as soon as the driving does`() {
        val d = StyleDetector()
        feed(d, 0, 40, { 13.0 }, rpm = 1600, pedal = 0.15)
        assertFalse(d.isPressingOn)
        // Start pressing on — this must come alive within a few seconds, long before
        // the strict learning gate would ever agree.
        feed(d, 40, 48, { i -> 24.0 + 8.0 * kotlin.math.sin(i / 4.0) }, rpm = 3800, pedal = 0.9)
        assertTrue("fast gate should be live (score=${d.fastScore})", d.isPressingOn)
        assertFalse("learning gate must NOT be fooled this fast", d.isSpirited)
    }

    @Test
    fun `a village mid-blast does not chop the co-driver off`() {
        val d = StyleDetector()
        feed(d, 0, 30, { i -> 24.0 + 8.0 * kotlin.math.sin(i / 4.0) }, rpm = 3800, pedal = 0.9)
        assertTrue(d.isPressingOn)
        // 20 s of 30 mph village — still within the hangover, keep talking.
        feed(d, 30, 50, { 13.0 }, rpm = 1600, pedal = 0.15)
        assertTrue("hangover should hold through a village", d.isPressingOn)
        // But a long potter afterwards does eventually go quiet.
        feed(d, 50, 110, { 13.0 }, rpm = 1600, pedal = 0.15)
        assertFalse("should be quiet again", d.isPressingOn)
    }
}

class SpokenCallTests {
    private fun hc(
        band: SeverityBand, vTarget: Double, gear: Int? = null,
        mods: List<Modifier> = emptyList(),
    ) = HorizonCorner(
        corner = Corner(1, 1, 0.0, 15.0, 30.0, Direction.RIGHT, 30.0, 40.0, 40.0, 30.0, 0.9),
        distanceAheadM = 150.0, pathConfidence = 0.9, band = band, modifiers = mods,
        vTargetMps = vTarget, brakingPointM = 80.0, triggerDistanceM = 40.0, gear = gear,
    )

    @Test
    fun `target speed is spoken in five mph steps`() {
        assertEquals("s_40", NoteComposer.speedKey(40 / 2.23694))
        assertEquals("s_30", NoteComposer.speedKey(31 / 2.23694))
        assertEquals("s_55", NoteComposer.speedKey(54 / 2.23694))
        assertNull("out of range", NoteComposer.speedKey(120 / 2.23694))
    }

    @Test
    fun `a full call reads as a co-driver would say it`() {
        val c = hc(SeverityBand.TWO, 40 / 2.23694, gear = 2,
            mods = listOf(Modifier.LONG, Modifier.TIGHTENS))
        val keys = NoteComposer.cornerKeys(c, NoteComposer.Detail(speed = true, gear = true))
        assertEquals(listOf("right_two", "long", "tightens", "s_40", "gear_2"), keys)
    }

    @Test
    fun `speed and gear are left out when they say nothing`() {
        val c = hc(SeverityBand.FIVE, 60 / 2.23694, gear = 4)
        val keys = NoteComposer.cornerKeys(c, NoteComposer.Detail())
        assertEquals(listOf("right_five"), keys)
    }

    @Test
    fun `compression drops gear before speed and speed before the danger call`() {
        val c = hc(SeverityBand.TWO, 40 / 2.23694, gear = 2,
            mods = listOf(Modifier.LONG, Modifier.TIGHTENS))
        val detail = NoteComposer.Detail(speed = true, gear = true)
        fun at(budget: Long) = NoteComposer.compose(
            listOf(c), listOf(null), detail,
            deadlineDistanceM = 100.0, budgetMs = budget, durationOf = { 500 },
        ).clipKeys

        assertEquals(listOf("right_two", "long", "tightens", "s_40", "gear_2"), at(10_000))
        assertTrue("gear goes first", "gear_2" !in at(2_000))
        assertTrue("shape goes next", "long" !in at(1_600))
        val tight = at(1_100)
        assertTrue("speed goes before the danger call", "s_40" !in tight)
        assertTrue("tightens survives longer than speed", "tightens" in tight)
        assertEquals("the corner call is never dropped", listOf("right_two"), at(400))
    }

    @Test
    fun `a camera is stated plainly, never as a caution`() {
        val cam = HorizonHazard(Hazard(1, 0.0, HazardKind.SPEED_CAMERA), 200.0, 0.9)
        assertEquals(listOf("speed_camera"), NoteComposer.hazardKeys(cam))
        val ford = HorizonHazard(Hazard(1, 0.0, HazardKind.FORD), 200.0, 0.9)
        assertEquals(listOf("caution", "ford"), NoteComposer.hazardKeys(ford))
        assertTrue(HazardKind.SPEED_CAMERA.isAlwaysAnnounced)
        assertTrue(HazardKind.AVERAGE_CAMERA.isAlwaysAnnounced)
        assertFalse(HazardKind.FORD.isAlwaysAnnounced)
    }
}

class CornerSpamTests {
    /** OSM routinely splits one sweeping bend into two or three corner rows.
     *  Calling each of them is the "right, right, right" the driver hears. */
    private fun raw(vararg c: Triple<Double, Direction, Double>) =
        com.rallycopilot.core.horizon.HorizonBuilder.RawHorizon(
            steps = emptyList(),
            totalLengthM = 1000.0,
            confidenceAtEnd = 0.9,
            corners = c.mapIndexed { i, (ahead, dir, minR) ->
                com.rallycopilot.core.horizon.HorizonBuilder.RawCorner(
                    Corner(i.toLong(), 1, 0.0, 10.0, 20.0, dir, minR, minR, minR, 20.0, 0.9),
                    ahead, 0.9, true,
                )
            },
            hazards = emptyList(),
        )

    private fun advisor() = com.rallycopilot.core.advisor.Advisor(DriverProfile.COLD_START)

    @Test
    fun `one bend split into three fragments becomes a single call`() {
        // Three right-handers 25 m apart — one real corner in the road.
        val h = advisor().annotate(
            raw(
                Triple(100.0, Direction.RIGHT, 60.0),
                Triple(145.0, Direction.RIGHT, 40.0),
                Triple(190.0, Direction.RIGHT, 55.0),
            ),
            currentSpeedMps = 20.0, nowMs = 0L,
        )
        assertEquals("should be one call, not three", 1, h.corners.size)
        // And it must keep the TIGHTEST of the run — the bit that actually bites.
        assertEquals(40.0, h.corners[0].corner.minRadiusM, 0.001)
    }

    @Test
    fun `a genuine left-right stays two calls`() {
        val h = advisor().annotate(
            raw(
                Triple(100.0, Direction.RIGHT, 50.0),
                Triple(145.0, Direction.LEFT, 50.0),
            ),
            currentSpeedMps = 20.0, nowMs = 0L,
        )
        assertEquals(2, h.corners.size)
    }

    @Test
    fun `well separated same-direction corners stay separate`() {
        val h = advisor().annotate(
            raw(
                Triple(100.0, Direction.RIGHT, 50.0),
                Triple(400.0, Direction.RIGHT, 50.0),
            ),
            currentSpeedMps = 20.0, nowMs = 0L,
        )
        assertEquals(2, h.corners.size)
    }

    @Test
    fun `target speed ignores the speed limit by design`() {
        // A fast open bend on a lane with a 30 limit still reports what the corner
        // can physically take — the number is a physical limit, not a legal one.
        val a = advisor()
        val h = a.annotate(
            com.rallycopilot.core.horizon.HorizonBuilder.RawHorizon(
                steps = emptyList(), totalLengthM = 500.0, confidenceAtEnd = 0.9,
                corners = listOf(
                    com.rallycopilot.core.horizon.HorizonBuilder.RawCorner(
                        Corner(1, 1, 0.0, 10.0, 20.0, Direction.RIGHT, 300.0, 300.0, 300.0, 20.0, 0.9),
                        150.0, 0.9, true, maxspeedKph = 48, highwayClass = "residential",
                    )
                ),
                hazards = emptyList(),
            ),
            currentSpeedMps = 20.0, nowMs = 0L,
        )
        val mph = h.corners.single().vTargetMps * 2.23694
        assertTrue("physical target was clamped to the limit: $mph mph", mph > 50.0)
    }
}

class LearningCutoffTests {
    /**
     * The app keeps every observation forever and re-derives the profile from the
     * whole history at each drive end. That is what made "reset profile" a no-op:
     * the next drive rebuilt the old numbers from the same rows. Learning must
     * therefore be able to ignore everything before a cutoff.
     */
    private fun obs(tMs: Long, aLat: Double) = CornerObservation(
        runId = 1, cornerId = tMs, tMs = tMs,
        band = SeverityBand.FOUR, minRadiusM = 90.0,
        vEntryMps = 25.0, vMinMps = 22.0, vExitMps = 25.0,
        aLatObserved = aLat, mapConfidence = 0.9, pathConfidence = 0.9,
        wasConstrained = false, conditions = Conditions.DRY,
    )

    @Test
    fun `pre-cutoff observations no longer shape the profile`() {
        // Ten inflated corners from before the geometry fix, then five honest ones.
        val contaminated = (1L..10L).map { obs(it, 12.0) }
        val clean = (100L..104L).map { obs(it, 6.0) }

        val withOld = com.rallycopilot.core.profile.Learning
            .derive(contaminated + clean).first[SeverityBand.FOUR]!!
        val afterCutoff = com.rallycopilot.core.profile.Learning
            .derive((contaminated + clean).filter { it.tMs >= 100L }).first[SeverityBand.FOUR]!!

        assertTrue("old data should drag the value up: $withOld", withOld > 8.0)
        assertEquals("post-cutoff should reflect real driving", 6.0, afterCutoff, 0.01)
    }

    @Test
    fun `a reset with no new corners leaves the seed standing`() {
        // Nothing after the cutoff means nothing to learn from — and crucially the
        // old rows must not creep back in and re-derive the previous numbers.
        val (derived, counts) = com.rallycopilot.core.profile.Learning
            .derive(emptyList())
        assertTrue(derived.isEmpty())
        assertTrue(counts.isEmpty())
        val profile = com.rallycopilot.core.profile.Learning
            .applySession(DriverProfile.COLD_START, emptyList())
        assertEquals(DriverProfile.SEED_A_LAT, profile.aLatFor(SeverityBand.FOUR), 1e-9)
    }
}

class SpeakModeTests {
    /** The setting only flips one flag, but it is the flag that decides whether a
     *  whole drive is narrated or silent, so pin both directions down. */
    @Test
    fun `alwaysSpeak defaults off so cruising is quiet`() {
        val e = com.rallycopilot.core.engine.DriveEngine(
            matcher = com.rallycopilot.core.matcher.MapMatcher(EmptyMap),
            horizonBuilder = com.rallycopilot.core.horizon.HorizonBuilder(EmptyMap),
            advisor = com.rallycopilot.core.advisor.Advisor(DriverProfile.COLD_START),
            audio = SilentSink,
            runLog = NullRunLog,
            clock = object : com.rallycopilot.core.engine.Clock {
                override fun nowMs() = 0L
            },
        )
        assertFalse(e.alwaysSpeak)
        e.alwaysSpeak = true
        assertTrue(e.alwaysSpeak)
    }
}

private object EmptyMap : com.rallycopilot.core.engine.MapStore {
    override fun edgesNear(p: com.rallycopilot.core.model.LatLon, radiusM: Double) =
        emptyList<com.rallycopilot.core.model.Edge>()
    override fun edge(id: Long): com.rallycopilot.core.model.Edge? = null
    override fun junction(nodeId: Long): com.rallycopilot.core.model.Junction? = null
    override fun cornersOn(edgeId: Long) = emptyList<com.rallycopilot.core.model.Corner>()
    override fun hazardsOn(edgeId: Long) = emptyList<com.rallycopilot.core.model.Hazard>()
    override fun isEmptyAt(p: com.rallycopilot.core.model.LatLon) = true
}

private object SilentSink : com.rallycopilot.core.engine.AudioSink {
    override fun clipDurationMs(key: String) = 500L
    override fun play(utterance: com.rallycopilot.core.model.Utterance) {}
    override fun isSpeaking() = false
    override fun remainingMs() = 0L
}

private object NullRunLog : com.rallycopilot.core.engine.RunLog {
    override fun logFix(
        fix: com.rallycopilot.core.model.Fix, matchedEdgeId: Long?, offsetM: Double?,
        confidence: Double?, wasPredicted: Boolean,
    ) {}
    override fun logEvent(event: com.rallycopilot.core.model.RunEvent) {}
}
