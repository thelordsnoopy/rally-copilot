package com.rallycopilot.core.engine

import com.rallycopilot.core.advisor.Advisor
import com.rallycopilot.core.advisor.NoteComposer
import com.rallycopilot.core.horizon.HorizonBuilder
import com.rallycopilot.core.matcher.MapMatcher
import com.rallycopilot.core.model.Fix
import com.rallycopilot.core.model.Horizon
import com.rallycopilot.core.model.HorizonCorner
import com.rallycopilot.core.model.LatLon
import com.rallycopilot.core.model.MatchedPosition
import com.rallycopilot.core.model.RunEvent
import com.rallycopilot.core.model.RunEventType
import com.rallycopilot.core.model.Utterance
import com.rallycopilot.core.profile.ObservationCollector
import com.rallycopilot.core.profile.StyleDetector
import com.rallycopilot.core.report.IncidentDetector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The runtime pipeline, pure and replayable:
 *
 *   fix → validate → match → dead-reckon → horizon → advise → compose → speak
 *
 * Drive it by calling [onFix] for every incoming fix and [onTick] at UI/audio cadence
 * (both from the same injected clock domain, so replays are deterministic).
 */
class DriveEngine(
    private val matcher: MapMatcher,
    private val horizonBuilder: HorizonBuilder,
    private val advisor: Advisor,
    private val audio: AudioSink,
    private val runLog: RunLog,
    private val clock: Clock,
    private val vehicle: VehicleData = NullVehicleData,
    private val collector: ObservationCollector? = null,
    private val incidentDetector: IncidentDetector? = null,
    val styleDetector: StyleDetector? = null,
) {
    data class Params(
        val maxAccuracyM: Double = 25.0,
        val maxJumpMps: Double = 90.0,
        /** Below this path confidence at a corner, the note is suppressed. */
        val speakConfidence: Double = 0.50,
        val gpsLostAfterMs: Long = 3000,
        /** Rebuild horizon on edge change or heading change beyond this. */
        val rebuildHeadingDeg: Double = 25.0,
        /** Speech budget per burst before compression kicks in. */
        val burstBudgetMs: Long = 4000,
        /** Chain corners whose utterances would overlap within this gap. */
        val chainGapM: Double = 60.0,
        val a2dpLatencyMs: Long = 250,
        val includeGear: Boolean = true,
    )

    val params = Params()

    /** Everything the HUD needs, updated every tick. */
    data class HudState(
        val matched: MatchedPosition? = null,
        val horizon: Horizon? = null,
        /** Dead-reckoned distance ahead consumed since the horizon was built. */
        val progressM: Double = 0.0,
        val speedMps: Double = 0.0,
        val currentNote: HorizonCorner? = null,
        val nextNote: HorizonCorner? = null,
        val gpsOk: Boolean = false,
        val mapOk: Boolean = true,
        val obdConnected: Boolean = false,
        val gear: Int? = null,
        val incidentSuspected: Boolean = false,
        /** Style detector verdict: is this spirited driving right now? */
        val spirited: Boolean = false,
        val spiritedFraction: Double = 0.0,
    )

    private val _hud = MutableStateFlow(HudState())
    val hud: StateFlow<HudState> = _hud

    private var lastFix: Fix? = null
    private var lastMatch: MatchedPosition? = null
    private var horizon: Horizon? = null
    /** Metres consumed along the horizon since it was built (dead-reckoned between fixes). */
    private var progressM = 0.0
    private var lastTickMs = 0L
    private val spokenCornerIds = HashSet<Long>()
    private val spokenHazardKeys = HashSet<String>()

    fun onFix(fix: Fix) {
        val prev = lastFix
        // ---- validate ----
        if (fix.accuracyM > params.maxAccuracyM) {
            runLog.logFix(fix, null, null, null, wasPredicted = false)
            return
        }
        if (prev != null) {
            val dt = (fix.tMs - prev.tMs) / 1000.0
            if (dt > 0) {
                val d = com.rallycopilot.core.geo.Geo.haversineM(
                    LatLon(prev.lat, prev.lon), LatLon(fix.lat, fix.lon)
                )
                if (d / dt > params.maxJumpMps) return // implausible jump
            }
        }
        lastFix = fix

        // ---- match ----
        val m = matcher.match(fix)
        runLog.logFix(fix, m?.edgeId, m?.offsetM, m?.confidence, wasPredicted = false)
        if (m == null) {
            if (lastMatch != null) runLog.logEvent(RunEvent(fix.tMs, RunEventType.MATCH_LOST))
            lastMatch = null
            horizon = null
            return
        }

        // ---- horizon: rebuild on edge change or big heading change, else re-anchor progress ----
        val h = horizon
        val needRebuild = h == null ||
            lastMatch?.edgeId != m.edgeId ||
            com.rallycopilot.core.geo.Geo.bearingDiffDeg(
                lastMatch?.bearingDeg ?: m.bearingDeg, m.bearingDeg
            ) > params.rebuildHeadingDeg
        lastMatch = m

        if (needRebuild) {
            val raw = horizonBuilder.build(m)
            if (raw == null) {
                horizon = null
                runLog.logEvent(RunEvent(fix.tMs, RunEventType.MPP_AMBIGUOUS))
            } else {
                val speed = fusedSpeed(fix.speedMps)
                horizon = advisor.annotate(raw, speed, fix.tMs)
                progressM = 0.0
                lastBuildOffset = m.offsetM
                spokenHazardKeys.clear()
                runLog.logEvent(RunEvent(fix.tMs, RunEventType.HORIZON_REBUILT))
            }
        } else {
            // Same edge: re-anchor progress from the matched offset, blending, not jumping.
            val hcur = horizon ?: return
            val anchoredProgress = anchoredProgressOn(hcur, m) ?: progressM
            progressM += (anchoredProgress - progressM) * 0.5 // ~300 ms blend at fix rate
        }
    }

    /** Call at UI cadence (e.g. 10 Hz) with the injected clock. Dead-reckons and speaks. */
    fun onTick() {
        val now = clock.nowMs()
        val fix = lastFix
        val dt = if (lastTickMs == 0L) 0.0 else (now - lastTickMs) / 1000.0
        lastTickMs = now

        val gpsOk = fix != null && now - fix.tMs < params.gpsLostAfterMs
        val speed = fusedSpeed(fix?.speedMps ?: 0.0)

        // ---- dead-reckon between fixes ----
        if (gpsOk && dt > 0) progressM += speed * dt

        val h = horizon
        val incident = incidentDetector?.tick(now, speed) == true
        if (incident) runLog.logEvent(RunEvent(now, RunEventType.INCIDENT_SUSPECTED))

        val gear = vehicle.currentGear()

        if (h == null || !gpsOk) {
            _hud.value = HudState(
                matched = lastMatch, horizon = null, speedMps = speed,
                gpsOk = gpsOk, obdConnected = vehicle.rpm() != null, gear = gear,
                incidentSuspected = incident,
                spirited = styleDetector?.isSpirited ?: false,
                spiritedFraction = styleDetector?.spiritedFraction ?: 0.0,
            )
            return
        }

        fun aheadOf(c: HorizonCorner) = c.distanceAheadM - progressM

        // ---- style detection: is this spirited driving? ----
        val insideCorner = h.corners.firstOrNull { c ->
            val a = aheadOf(c)
            a < 0 && a > -c.corner.arcLengthM
        }
        styleDetector?.tick(
            StyleDetector.Sample(
                tMs = now, speedMps = speed,
                rpm = vehicle.rpm(), pedal01 = vehicle.throttle01(), gear = gear,
                aLatMps2 = insideCorner?.let { (speed * speed) / it.corner.minRadiusM },
            ),
            advisorProfile(),
        )
        val spiritedNow = styleDetector?.isSpirited ?: true

        // ---- feed the observation collector (runs behind the car) ----
        collector?.tick(now, speed, vehicle.throttle01(), h.corners, spiritedNow) { aheadOf(it) }

        // ---- decide what to speak ----
        maybeSpeak(h, speed, now, ::aheadOf)

        val upcoming = h.corners.filter { aheadOf(it) > -5.0 }.sortedBy { aheadOf(it) }
        _hud.value = HudState(
            matched = lastMatch,
            horizon = h,
            progressM = progressM,
            speedMps = speed,
            currentNote = upcoming.getOrNull(0),
            nextNote = upcoming.getOrNull(1),
            gpsOk = true,
            obdConnected = vehicle.rpm() != null,
            gear = gear,
            incidentSuspected = incident,
            spirited = spiritedNow && styleDetector != null,
            spiritedFraction = styleDetector?.spiritedFraction ?: 0.0,
        )
    }

    private fun maybeSpeak(h: Horizon, speed: Double, now: Long, aheadOf: (HorizonCorner) -> Double) {
        if (audio.isSpeaking()) return

        // Hazards first: they are short and urgent.
        for (hz in h.hazards) {
            val ahead = hz.distanceAheadM - progressM
            val key = "${hz.hazard.edgeId}:${hz.hazard.offsetM.toInt()}"
            if (ahead in 0.0..(speed * 6.0 + 50.0) && key !in spokenHazardKeys &&
                hz.pathConfidence >= params.speakConfidence
            ) {
                spokenHazardKeys += key
                val keys = NoteComposer.hazardKeys(
                    com.rallycopilot.core.model.HorizonHazard(hz.hazard, ahead, hz.pathConfidence)
                )
                audio.play(Utterance(keys, urgent = true, deadlineDistanceM = ahead, forCornerId = null))
                runLog.logEvent(RunEvent(now, RunEventType.HAZARD_SPOKEN, hz.hazard.kind.name))
                return
            }
        }

        // Find the first unspoken corner whose trigger point we have reached.
        val due = h.corners.firstOrNull { c ->
            c.corner.id !in spokenCornerIds && aheadOf(c) > 0 &&
                progressM + speechLeadM(speed, c) >= c.triggerDistanceM
        } ?: return

        if (due.pathConfidence < params.speakConfidence) {
            spokenCornerIds += due.corner.id
            runLog.logEvent(RunEvent(now, RunEventType.NOTE_SUPPRESSED_LOW_CONFIDENCE, due.corner.id.toString()))
            return
        }

        // Chain: pull in following corners that would overlap this utterance.
        val chain = ArrayList<HorizonCorner>()
        val gaps = ArrayList<Double?>()
        chain += due; gaps += null
        var tail = due
        for (c in h.corners) {
            if (c.corner.id in spokenCornerIds || c === due) continue
            val gap = c.distanceAheadM - (tail.distanceAheadM + tail.corner.arcLengthM)
            if (c.distanceAheadM > tail.distanceAheadM && gap < params.chainGapM + speed * 1.5) {
                chain += c; gaps += gap.coerceAtLeast(0.0); tail = c
            } else if (c.distanceAheadM > tail.distanceAheadM) break
        }
        if (chain.size > 1) runLog.logEvent(RunEvent(now, RunEventType.NOTE_CHAINED, chain.size.toString()))

        val utterance = NoteComposer.compose(
            chain = chain,
            gapsM = gaps,
            includeGear = params.includeGear && vehicle.rpm() != null,
            deadlineDistanceM = aheadOf(due),
            budgetMs = params.burstBudgetMs,
            durationOf = { audio.clipDurationMs(it) },
        )
        chain.forEach { spokenCornerIds += it.corner.id }
        audio.play(utterance)
        runLog.logEvent(RunEvent(now, RunEventType.NOTE_SPOKEN, chain.joinToString(",") { it.corner.id.toString() }))
    }

    /** Metres consumed while the utterance plays, incl. BT latency — end-anchored timing. */
    private fun speechLeadM(speed: Double, c: HorizonCorner): Double {
        val clipMs = NoteComposer.cornerKeys(c, includeGear = false).sumOf { audio.clipDurationMs(it) }
        return speed * (clipMs + params.a2dpLatencyMs) / 1000.0
    }

    /** OBD wheel speed preferred over GPS speed when available (faster, steadier). */
    private fun fusedSpeed(gpsSpeed: Double): Double = vehicle.obdSpeedMps() ?: gpsSpeed

    private fun advisorProfile() = advisor.profile

    /** Where the matched offset lands in the current horizon's progress space. */
    private fun anchoredProgressOn(h: Horizon, m: MatchedPosition): Double? {
        // Progress space starts at the position the horizon was built from on the same edge.
        val firstEdge = h.pathEdgeIds.firstOrNull() ?: return null
        if (m.edgeId != firstEdge) return null
        // The horizon's distance-ahead space was measured from the build position;
        // the difference in matched offsets maps 1:1 while we stay on the first edge.
        return if (m.forward) m.offsetM - (lastBuildOffset ?: m.offsetM)
        else (lastBuildOffset ?: m.offsetM) - m.offsetM
    }

    private var lastBuildOffset: Double? = null
}
