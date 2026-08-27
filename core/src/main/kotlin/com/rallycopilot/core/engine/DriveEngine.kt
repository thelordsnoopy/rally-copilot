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
import com.rallycopilot.core.knowledge.KnowledgeMath
import com.rallycopilot.core.knowledge.KnowledgeStore
import com.rallycopilot.core.knowledge.RoadBucket
import com.rallycopilot.core.knowledge.SlowdownMonitor
import com.rallycopilot.core.obd.HealthWatch
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
    private val healthWatch: HealthWatch? = null,
    private val knowledge: KnowledgeStore? = null,
    private val slowdown: SlowdownMonitor? = null,
    /** A word about the corner just taken — spoken only into a straight. */
    private val coach: com.rallycopilot.core.advisor.Coach? = null,
    /** Compares IMU-measured corner radius against the map (see RadiusAuditor). */
    private val radiusAuditor: com.rallycopilot.core.knowledge.RadiusAuditor? = null,
    /** Black box. Records what was nearly done as well as what was done. */
    private val telemetry: Telemetry = NullTelemetry,
) {
    data class Params(
        val maxAccuracyM: Double = 25.0,
        val maxJumpMps: Double = 90.0,
        /** At or above this path confidence a corner is called plainly. */
        val speakConfidence: Double = 0.50,
        val gpsLostAfterMs: Long = 3000,
        /** Rebuild horizon on edge change or heading change beyond this. */
        val rebuildHeadingDeg: Double = 25.0,
        /** Speech budget per burst before compression kicks in. */
        val burstBudgetMs: Long = 4000,
        /** Chain corners whose utterances would overlap within this gap. */
        val chainGapM: Double = 60.0,
        /**
         * Most corners a single call may describe.
         *
         * A real co-driver gives you the corner you are arriving at and, at most,
         * the one immediately after it. Chaining was previously unbounded, so a
         * wiggly lane could open with five corners in one breath — which nobody can
         * hold, and which pushes the compression ladder so far that "into" (the word
         * that says the corners are joined) gets dropped to make room.
         */
        val maxChain: Int = 2,
        /** Bluetooth A2DP output delay to the car. SBC to a car head unit measures
         *  150-250 ms; this is the figure the end-anchored timing works back from. */
        val a2dpLatencyMs: Long = 220,
        val includeGear: Boolean = true,
        /** Call the target speed when a corner is this much slower than current pace. */
        val speakSpeed: Boolean = true,
        val speakSpeedBelowRatio: Double = 0.85,
        /** Minimum distance travelled between two camera announcements. */
        val cameraRepeatM: Double = 400.0,
    )

    val params = Params()

    /**
     * When true the co-driver calls everything all the time, whatever the style
     * detector thinks. When false (default) ordinary cruising is QUIET — corner
     * calls stop and only speed cameras get through. Settable mid-drive from
     * settings; read on the engine thread every tick.
     */
    @Volatile
    var alwaysSpeak: Boolean = false

    /**
     * Verbosity: the gentlest band still worth calling aloud, by ordinal
     * (HAIRPIN=0 … SIX=6). Everything is still drawn on the HUD; this only decides
     * what is SPOKEN. A "six" is barely a bend, so calling every one of them on a
     * wiggly lane is most of what makes the co-driver feel like chatter.
     */
    @Volatile
    var maxSpokenBandOrdinal: Int = com.rallycopilot.core.model.SeverityBand.SIX.ordinal

    /** Where speed comes from. AUTO uses OBD whenever it is live, GPS otherwise. */
    enum class SpeedSource { AUTO, GPS_ONLY, OBD_ONLY }

    @Volatile
    var speedSource: SpeedSource = SpeedSource.AUTO

    /**
     * Output delay to the car's speakers, milliseconds. Defaults to the documented
     * SBC-to-head-unit range, but the app measures the real figure with a chirp at
     * drive start — corner timing is scheduled backwards from this, so a guess here
     * is a guess in every single call.
     */
    @Volatile
    var audioLatencyMs: Long = 220

    /** IMU lateral acceleration magnitude in the CAR frame, m/s², set by the app
     *  layer once the mount is aligned. Null = no trustworthy measurement. */
    @Volatile
    var imuLateralMps2: Double? = null

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
        /** True when the displayed speed came from the car, false when from GPS. */
        val speedFromObd: Boolean = false,
        /** Dongle link is up but the car is not answering — worth saying out loud,
         *  because "connected" and "working" are not the same thing. */
        val obdSilent: Boolean = false,
        val gear: Int? = null,
        val incidentSuspected: Boolean = false,
        /** Strict verdict — gates learning. */
        val spirited: Boolean = false,
        val spiritedFraction: Double = 0.0,
        /** Will the co-driver actually call corners right now? False = quiet mode
         *  (cameras only). True either because you are pressing on, or because
         *  the "call everything" setting is on. */
        val pressingOn: Boolean = false,
        /** Active "was there a hazard?" prompt: auto-answers NO at deadline. */
        val hazardPrompt: HazardPrompt? = null,
    )

    data class HazardPrompt(val edgeId: Long, val offsetM: Double, val ratio: Double, val deadlineMs: Long)

    private val _hud = MutableStateFlow(HudState())
    val hud: StateFlow<HudState> = _hud

    private var lastFix: Fix? = null
    private var lastMatch: MatchedPosition? = null
    private var horizon: Horizon? = null
    /** Metres consumed along the horizon since it was built (dead-reckoned between fixes). */
    private var progressM = 0.0
    private var lastTickMs = 0L
    /** Spoken corners: id → last tick (ms) it was still relevant. Entries leave the set
     *  once the corner is passed, or after it has been absent from the horizon for a
     *  while — so the return leg of an out-and-back gets its calls again. */
    private val spokenCorners = HashMap<Long, Long>()
    private val spokenHazards = HashMap<String, Long>()
    private val suppressionLogged = HashSet<Long>()
    private var activePrompt: HazardPrompt? = null
    private val cleanPassChecked = HashSet<Long>()
    /** Rolling recent-speed window for the "expected speed on a straight" baseline. */
    private val recentSpeeds = ArrayDeque<Pair<Long, Double>>()
    private var lastEdgeChangeMs = 0L
    /** Distance travelled this drive. Never reset — unlike progressM, which is
     *  relative to the current horizon — so it can gate repeat announcements. */
    private var odometerM = 0.0
    private var lastCameraOdoM = Double.NEGATIVE_INFINITY

    /** UI answer to the hazard prompt. YES confirms; the deadline auto-answers NO. */
    fun answerHazardPrompt(yes: Boolean) {
        val p = activePrompt ?: return
        activePrompt = null
        resolvePrompt(p, confirmed = yes, explicit = true)
    }

    private fun resolvePrompt(p: HazardPrompt, confirmed: Boolean, explicit: Boolean) {
        val store = knowledge ?: return
        val bucket = RoadBucket.bucketOf(p.offsetM)
        val existing = store.get(p.edgeId, bucket) ?: RoadBucket(p.edgeId, bucket)
        when {
            confirmed -> store.put(KnowledgeMath.applySlowEvent(existing, p.ratio, true))
            // An explicit NO is evidence AGAINST a hazard here; a silent timeout is no
            // evidence at all. Neither may accrue slow-event counts (that turned three
            // "no, it's fine" answers into a spoken caution).
            explicit -> store.put(KnowledgeMath.applyNegativeAnswer(existing))
        }
        runLog.logEvent(RunEvent(clock.nowMs(),
            if (confirmed) RunEventType.HAZARD_CONFIRMED else RunEventType.HAZARD_AUTO_NO,
            "${p.edgeId}:${p.offsetM.toInt()}"))
    }

    fun onFix(fix: Fix) {
        val prev = lastFix
        // ---- validate ----
        if (fix.accuracyM > params.maxAccuracyM) {
            telemetry.log("fix_rejected", mapOf("why" to "accuracy", "acc" to fix.accuracyM))
            runLog.logFix(fix, null, null, null, wasPredicted = false)
            return
        }
        if (prev != null) {
            val dt = (fix.tMs - prev.tMs) / 1000.0
            if (dt > 0) {
                val d = com.rallycopilot.core.geo.Geo.haversineM(
                    LatLon(prev.lat, prev.lon), LatLon(fix.lat, fix.lon)
                )
                if (d / dt > params.maxJumpMps) {
                    telemetry.log("fix_rejected", mapOf("why" to "jump", "mps" to d / dt))
                    return
                }
            }
        }
        lastFix = fix
        telemetry.log("fix", mapOf(
            "lat" to fix.lat, "lon" to fix.lon, "speed" to fix.speedMps,
            "bearing" to fix.bearingDeg, "acc" to fix.accuracyM, "tMs" to fix.tMs,
        ))

        // ---- match ----
        val m = matcher.match(fix)
        runLog.logFix(fix, m?.edgeId, m?.offsetM, m?.confidence, wasPredicted = false)
        if (m == null) {
            telemetry.log("match_lost", mapOf("lat" to fix.lat, "lon" to fix.lon, "acc" to fix.accuracyM))
            if (lastMatch != null) runLog.logEvent(RunEvent(fix.tMs, RunEventType.MATCH_LOST))
            lastMatch = null
            horizon = null
            return
        }

        // ---- horizon: rebuild on edge change, direction flip, or big heading change ----
        val h = horizon
        val edgeChanged = lastMatch?.edgeId != m.edgeId
        val needRebuild = h == null ||
            edgeChanged ||
            lastBuildForward != m.forward ||
            com.rallycopilot.core.geo.Geo.bearingDiffDeg(
                lastMatch?.bearingDeg ?: m.bearingDeg, m.bearingDeg
            ) > params.rebuildHeadingDeg
        lastMatch = m
        if (edgeChanged) lastEdgeChangeMs = fix.tMs

        if (needRebuild) {
            val raw = horizonBuilder.build(m)
            if (raw == null) {
                horizon = null
                telemetry.log("horizon_none", mapOf("edge" to m.edgeId, "off" to m.offsetM))
                runLog.logEvent(RunEvent(fix.tMs, RunEventType.MPP_AMBIGUOUS))
            } else {
                val speed = fusedSpeed(fix.speedMps)
                val prevHighway = horizon?.currentEdgeHighway
                val newHorizon = advisor.annotate(raw, speed, fix.tMs)
                // Road class changed → the recent-pace baseline belongs to the old road.
                if (prevHighway != null && newHorizon.currentEdgeHighway != prevHighway) recentSpeeds.clear()
                horizon = newHorizon
                progressM = 0.0
                lastBuildOffset = m.offsetM
                lastBuildForward = m.forward
                // Corner trackers hold distances from the OLD horizon — remap or drop
                // them, or a corner in progress reopens and ratchets vMin through
                // unrelated road into a garbage observation.
                collector?.onHorizonRebuilt(newHorizon.corners)
                telemetry.log("horizon", mapOf(
                    "edges" to newHorizon.pathEdgeIds.size,
                    "lengthM" to newHorizon.totalLengthM,
                    "confEnd" to newHorizon.confidenceAtEnd,
                    "why" to if (edgeChanged) "edge" else "heading",
                    "corners" to newHorizon.corners.joinToString(";") { c ->
                        "${c.corner.id}:${c.corner.direction}:${c.band}:" +
                            "${c.distanceAheadM.toInt()}m:r${c.corner.minRadiusM.toInt()}:" +
                            "conf${(c.corner.confidence * 100).toInt()}:" +
                            "path${(c.pathConfidence * 100).toInt()}:" +
                            "v${(c.vTargetMps * 2.23694).toInt()}"
                    },
                ))
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
        if (gpsOk && dt > 0) { progressM += speed * dt; odometerM += speed * dt }

        val h = horizon
        val incident = incidentDetector?.tick(now, speed) == true
        if (incident) runLog.logEvent(RunEvent(now, RunEventType.INCIDENT_SUSPECTED))

        val gear = vehicle.currentGear()

        // Health watch: coolant, battery, ambient/ice. One calm warning per crossing,
        // never interrupting a pacenote.
        healthWatch?.check(vehicle.coolantC(), vehicle.batteryV(), vehicle.ambientC())?.let { key ->
            if (!audio.isSpeaking()) {
                audio.play(Utterance(listOf(key), urgent = false, deadlineDistanceM = 0.0, forCornerId = null))
                runLog.logEvent(RunEvent(now, RunEventType.HEALTH_WARNING, key))
            }
        }

        // Prompt deadline runs regardless of GPS/horizon state — a prompt raised just
        // before match loss must still auto-resolve, not linger forever.
        activePrompt?.let { p ->
            if (now >= p.deadlineMs) { activePrompt = null; resolvePrompt(p, confirmed = false, explicit = false) }
        }

        if (h == null || !gpsOk) {
            _hud.value = HudState(
                matched = lastMatch, horizon = null, speedMps = speed,
                gpsOk = gpsOk, obdConnected = vehicle.rpm() != null, speedFromObd = usingObdSpeed,
                obdSilent = vehicle.linkSilent(), gear = gear,
                incidentSuspected = incident,
                spirited = styleDetector?.isSpirited ?: false,
                spiritedFraction = styleDetector?.spiritedFraction ?: 0.0,
                pressingOn = alwaysSpeak || (styleDetector?.isPressingOn ?: true),
                hazardPrompt = activePrompt,
            )
            return
        }

        fun aheadOf(c: HorizonCorner) = c.distanceAheadM - progressM

        // ---- retire spoken notes once they are passed or long gone from the horizon ----
        run {
            val inHorizon = HashMap<Long, HorizonCorner>(h.corners.size)
            for (c in h.corners) inHorizon[c.corner.id] = c
            val it = spokenCorners.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                val hc = inHorizon[e.key]
                if (hc != null) {
                    if (aheadOf(hc) < -(hc.corner.arcLengthM + 30.0)) it.remove() else e.setValue(now)
                } else if (now - e.value > 30_000) it.remove()
            }
            val hzIt = spokenHazards.entries.iterator()
            val hazardKeys = HashMap<String, Double>(h.hazards.size)
            for (hz in h.hazards) hazardKeys["${hz.hazard.edgeId}:${hz.hazard.offsetM.toInt()}"] = hz.distanceAheadM - progressM
            while (hzIt.hasNext()) {
                val e = hzIt.next()
                val rel = hazardKeys[e.key]
                if (rel != null) {
                    if (rel < -50.0) hzIt.remove() else e.setValue(now)
                } else if (now - e.value > 30_000) hzIt.remove()
            }
        }

        val quietNow = !alwaysSpeak && !(styleDetector?.isPressingOn ?: true)

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
                // Lets the detector tell necessary braking (for a corner) from
                // traffic/hesitation braking, which breaks a "spirited" run.
                nearestCornerM = h.corners.minOfOrNull { kotlin.math.abs(aheadOf(it)) },
            ),
            advisorProfile(),
        )
        // No detector wired = no learning. Fail CLOSED: this flag gates the profile.
        val spiritedNow = styleDetector?.isSpirited ?: false

        // ---- radius audit: does the road agree with the map? ----
        radiusAuditor?.tick(
            cornerId = insideCorner?.corner?.id,
            mapRadiusM = insideCorner?.corner?.minRadiusM,
            speedMps = speed,
            aLatImuMps2 = imuLateralMps2,
            gpsAccuracyM = fix?.accuracyM,
        )

        // ---- feed the observation collector (runs behind the car) ----
        collector?.tick(now, speed, vehicle.throttle01(), h.corners, spiritedNow,
            matchedEdgeId = lastMatch?.edgeId) { aheadOf(it) }

        // ---- personal knowledge: unexplained slowdowns become hazard evidence ----
        recentSpeeds += now to speed
        while (recentSpeeds.isNotEmpty() && now - recentSpeeds.first().first > 60_000) recentSpeeds.removeFirst()
        val m = lastMatch
        if (slowdown != null && knowledge != null && m != null) {
            // Expected speed here: a nearby corner's target, else your own recent pace.
            val nearCorner = h.corners.minByOrNull { kotlin.math.abs(aheadOf(it)) }
                ?.takeIf { kotlin.math.abs(aheadOf(it)) < 40.0 }
            val recentP90 = recentSpeeds.map { it.second }.sorted()
                .let { if (it.isEmpty()) 0.0 else it[((it.size - 1) * 0.9).toInt()] }
            val expected = nearCorner?.vTargetMps ?: recentP90
            // Slowing right after a junction/edge change is explained by the turn, not a
            // hazard — treat the passage itself as a nearby mapped feature for a while.
            val nearestMapped = if (now - lastEdgeChangeMs < 8_000) 0.0
            else h.hazards
                .filter { it.hazard.kind != com.rallycopilot.core.model.HazardKind.LEARNED }
                .minOfOrNull { kotlin.math.abs(it.distanceAheadM - progressM) }
            slowdown.tick(now, m.edgeId, m.offsetM, speed, expected, nearestMapped)?.let { anomaly ->
                // Never clobber a prompt the driver hasn't answered yet.
                if (activePrompt == null) {
                    activePrompt = HazardPrompt(anomaly.edgeId, anomaly.offsetM, anomaly.observedRatio, now + 7_000)
                    runLog.logEvent(RunEvent(now, RunEventType.HAZARD_PROMPT,
                        if (anomaly.hadBump) "bump" else ""))
                }
            }
            // Clean passes over known trouble spots relax them over time.
            for (hz in h.hazards) {
                if (hz.hazard.kind != com.rallycopilot.core.model.HazardKind.LEARNED) continue
                val rel = hz.distanceAheadM - progressM
                val key = hz.hazard.edgeId * 100_000 + RoadBucket.bucketOf(hz.hazard.offsetM)
                if (rel < -30 && key !in cleanPassChecked) {
                    cleanPassChecked += key
                    val b = knowledge.get(hz.hazard.edgeId, RoadBucket.bucketOf(hz.hazard.offsetM))
                    if (b != null && activePrompt == null) knowledge.put(KnowledgeMath.applyCleanPass(b))
                }
            }
        }

        // ---- black box: EVERYTHING, every tick (10 Hz) ----
        run {
            val m2 = lastMatch
            val nextC = h.corners.firstOrNull { aheadOf(it) > 0 }
            telemetry.log("s", mapOf(
                // where
                "lat" to fix?.lat, "lon" to fix?.lon, "acc" to fix?.accuracyM,
                "gpsSpeed" to fix?.speedMps, "gpsBearing" to fix?.bearingDeg,
                "fixAgeMs" to fix?.let { now - it.tMs },
                // map match
                "edge" to m2?.edgeId, "off" to m2?.offsetM, "fwd" to m2?.forward,
                "matchConf" to m2?.confidence, "matchBearing" to m2?.bearingDeg,
                "progress" to progressM, "odo" to odometerM,
                // the car
                "speed" to speed, "obdSpeed" to vehicle.obdSpeedMps(), "usingObd" to usingObdSpeed,
                "rpm" to vehicle.rpm(), "gear" to gear, "throttle" to vehicle.throttle01(),
                "coolantC" to vehicle.coolantC(), "batteryV" to vehicle.batteryV(),
                "ambientC" to vehicle.ambientC(), "mapKpa" to vehicle.mapKpa(),
                "fuel" to vehicle.fuelLevel01(), "obdSilent" to vehicle.linkSilent(),
                // motion
                "aLatImu" to imuLateralMps2,
                "aLatGeom" to insideCorner?.let { (speed * speed) / it.corner.minRadiusM },
                "insideCorner" to insideCorner?.corner?.id,
                // what the co-driver thinks
                "horizonCorners" to h.corners.size, "horizonM" to h.totalLengthM,
                "confEnd" to h.confidenceAtEnd, "pathEdges" to h.pathEdgeIds.size,
                "spirited" to spiritedNow, "spiritedFrac" to (styleDetector?.spiritedFraction ?: 0.0),
                "styleScore" to (styleDetector?.score ?: 0.0),
                "styleFast" to (styleDetector?.fastScore ?: 0.0),
                "pressingOn" to (styleDetector?.isPressingOn ?: true),
                "quiet" to quietNow, "speaking" to audio.isSpeaking(),
                "speakingMs" to audio.remainingMs(),
                "activeObs" to (collector?.activeCount ?: 0),
                "obsSoFar" to (collector?.observations?.size ?: 0),
                // the next corner, in full
                "nextId" to nextC?.corner?.id,
                "nextDir" to nextC?.corner?.direction?.name,
                "nextBand" to nextC?.band?.name,
                "nextAheadM" to nextC?.let { aheadOf(it) },
                "nextRadiusM" to nextC?.corner?.minRadiusM,
                "nextConf" to nextC?.corner?.confidence,
                "nextPathConf" to nextC?.pathConfidence,
                "nextVTarget" to nextC?.vTargetMps,
                "nextGrade" to nextC?.corner?.approachGrade,
                "nextSpoken" to (nextC?.corner?.id?.let { it in spokenCorners } ?: false),
            ))
        }

        // ---- decide what to speak ----
        maybeSpeak(h, speed, now, ::aheadOf)

        // ---- a word about the corner just taken, if the road allows ----
        // Strictly subordinate to the road ahead: the verdict is only voiced when
        // nothing is due, and is thrown away rather than queued if a call arrives.
        coach?.let { c ->
            collector?.takeLastClosed()?.let { (obs, hc) -> c.onCornerClosed(obs, hc, now) }
            if (!quietNow) {
                val gapS = clearRoadSeconds(h, speed, ::aheadOf)
                c.poll(now, gapS, audio.isSpeaking())?.let { key ->
                    audio.play(Utterance(listOf(key), urgent = false,
                        deadlineDistanceM = 0.0, forCornerId = null))
                }
            } else {
                c.clear()
            }
        }

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
            speedFromObd = usingObdSpeed,
            obdSilent = vehicle.linkSilent(),
            gear = gear,
            incidentSuspected = incident,
            spirited = spiritedNow && styleDetector != null,
            spiritedFraction = styleDetector?.spiritedFraction ?: 0.0,
            pressingOn = alwaysSpeak || (styleDetector?.isPressingOn ?: true),
            hazardPrompt = activePrompt,
        )
    }

    /**
     * How long the road is clear for, in seconds: time until the next corner call
     * would come due, or the next hazard needs announcing. This is the budget any
     * non-essential speech has to fit inside.
     */
    private fun clearRoadSeconds(h: Horizon, speed: Double, aheadOf: (HorizonCorner) -> Double): Double {
        if (speed < 3.0) return 0.0
        var nearest = Double.MAX_VALUE
        for (c in h.corners) {
            if (c.corner.id in spokenCorners) continue
            val ahead = aheadOf(c)
            if (ahead <= 0) continue
            // The moment this corner's call becomes due, not the corner itself.
            val trigger = advisor.brakingDistanceM(speed, c.vTargetMps, c.corner.approachGrade) +
                speed * advisor.noteLeadSeconds + speechLeadM(speed, c)
            nearest = minOf(nearest, (ahead - trigger).coerceAtLeast(0.0))
        }
        for (hz in h.hazards) {
            val ahead = hz.distanceAheadM - progressM
            val key = "${hz.hazard.edgeId}:${hz.hazard.offsetM.toInt()}"
            if (ahead <= 0 || key in spokenHazards) continue
            nearest = minOf(nearest, (ahead - (speed * 6.0 + 50.0)).coerceAtLeast(0.0))
        }
        return if (nearest == Double.MAX_VALUE) 30.0 else nearest / speed
    }

    private fun maybeSpeak(h: Horizon, speed: Double, now: Long, aheadOf: (HorizonCorner) -> Double) {
        if (audio.isSpeaking()) return

        // QUIET MODE: pottering along, the co-driver shuts up. Speed cameras are the
        // exception and are always called — an alert you only get when pressing on is
        // an alert you cannot rely on. Turned off entirely by [alwaysSpeak].
        val quiet = !alwaysSpeak && !(styleDetector?.isPressingOn ?: true)

        // Hazards first: they are short and urgent.
        for (hz in h.hazards) {
            val ahead = hz.distanceAheadM - progressM
            val key = "${hz.hazard.edgeId}:${hz.hazard.offsetM.toInt()}"
            if (quiet && !hz.hazard.kind.isAlwaysAnnounced) continue
            // Cameras get a longer runway: you want to be at the limit well before it,
            // not braking on top of it.
            val window = if (hz.hazard.kind.isAlwaysAnnounced) speed * 10.0 + 120.0
            else speed * 6.0 + 50.0
            // One call per camera SITE. A gantry, a dual carriageway, or a road split
            // at a junction can put several camera nodes within a few metres on
            // different edges, which per-edge declustering in the map builder cannot
            // merge — so gate on distance travelled since the last camera call too.
            if (hz.hazard.kind.isAlwaysAnnounced &&
                odometerM - lastCameraOdoM < params.cameraRepeatM
            ) continue
            if (ahead in 0.0..window && key !in spokenHazards &&
                hz.pathConfidence >= params.speakConfidence
            ) {
                spokenHazards[key] = now
                if (hz.hazard.kind.isAlwaysAnnounced) lastCameraOdoM = odometerM
                val keys = NoteComposer.hazardKeys(
                    com.rallycopilot.core.model.HorizonHazard(hz.hazard, ahead, hz.pathConfidence)
                )
                audio.play(Utterance(keys, urgent = true, deadlineDistanceM = ahead, forCornerId = null))
                lastCall = "hazard=${hz.hazard.kind.name} keys=" + keys.joinToString("+")
                runLog.logEvent(RunEvent(now, RunEventType.HAZARD_SPOKEN, hz.hazard.kind.name))
                return
            }
        }

        // Not pressing on: no corner calls at all. They resume the moment you do.
        if (quiet) return

        // Corners we could speak, nearest first. Confident enough → plain call;
        // otherwise silent, and NOT marked spoken, so a transient ambiguity does not
        // silence a corner forever. A co-driver is either sure or quiet — there is no
        // third, hedging voice, which is why the "care" prefix was dropped.
        val speakable = h.corners.filter { c ->
            val ok = c.corner.id !in spokenCorners && aheadOf(c) > 0 &&
                c.band.ordinal <= maxSpokenBandOrdinal
            if (ok && c.pathConfidence < params.speakConfidence) {
                if (suppressionLogged.add(c.corner.id)) {
                    telemetry.log("note_suppressed", mapOf(
                        "corner" to c.corner.id, "band" to c.band.name,
                        "pathConf" to c.pathConfidence, "need" to params.speakConfidence,
                        "aheadM" to aheadOf(c)))
                    runLog.logEvent(RunEvent(now, RunEventType.NOTE_SUPPRESSED_LOW_CONFIDENCE, c.corner.id.toString()))
                }
                false
            } else ok
        }
        if (speakable.isEmpty()) return

        // Trigger from LIVE speed: braking distance to this corner's target plus the
        // reaction lead plus the time the clips take to play. Build-time trigger
        // distances go stale the moment speed changes.
        fun triggerReached(c: HorizonCorner): Boolean {
            val need = advisor.brakingDistanceM(speed, c.vTargetMps, c.corner.approachGrade) +
                speed * advisor.noteLeadSeconds + speechLeadM(speed, c)
            return aheadOf(c) <= need
        }
        val due = speakable.firstOrNull { triggerReached(it) } ?: run {
            // The near-miss line: what WOULD be called, and how far off the trigger
            // it is. This is the record that says whether a missing call was a
            // suppressed corner, an untriggered one, or a corner never in the horizon.
            speakable.firstOrNull()?.let { c ->
                val need = advisor.brakingDistanceM(speed, c.vTargetMps, c.corner.approachGrade) +
                    speed * advisor.noteLeadSeconds + speechLeadM(speed, c)
                if (now - lastPendingLogMs >= 2000) {
                    lastPendingLogMs = now
                    telemetry.log("note_pending", mapOf(
                        "corner" to c.corner.id, "band" to c.band.name,
                        "aheadM" to aheadOf(c), "needM" to need, "speed" to speed,
                        "vTarget" to c.vTargetMps,
                    ))
                }
            }
            return
        }

        // Speak from the NEAREST speakable corner: if a far severe corner comes due
        // while a nearer gentle one hasn't triggered yet, the driver still must hear
        // them in road order — "six left into hairpin right", never the reverse.
        val chain = ArrayList<HorizonCorner>()
        val gaps = ArrayList<Double?>()
        var tail: HorizonCorner? = null
        for (c in speakable) {
            if (chain.size >= params.maxChain) break
            if (c.distanceAheadM > due.distanceAheadM) {
                // Past the due corner: keep chaining only while gaps genuinely overlap.
                val t = tail ?: break
                val gap = c.distanceAheadM - (t.distanceAheadM + t.corner.arcLengthM)
                if (gap < params.chainGapM + speed * 1.5) {
                    chain += c; gaps += gap.coerceAtLeast(0.0); tail = c
                } else break
            } else {
                gaps += tail?.let { (c.distanceAheadM - (it.distanceAheadM + it.corner.arcLengthM)).coerceAtLeast(0.0) }
                chain += c
                tail = c
            }
        }
        if (chain.isEmpty()) return
        if (chain.size > 1) runLog.logEvent(RunEvent(now, RunEventType.NOTE_CHAINED, chain.size.toString()))

        val first = chain.first()
        // The whole utterance must be DONE by the first corner's braking point.
        val deadline = (aheadOf(first) -
            advisor.brakingDistanceM(speed, first.vTargetMps, first.corner.approachGrade))
            .coerceAtLeast(0.0)

        // Speak speed and gear only when they tell you something you would not
        // already assume. Calling the target speed of a corner you are already
        // slower than is exactly the noise that makes a co-driver ignorable.
        // PER CORNER, not per chain: one corner needing "sixty" must not drag a
        // "ninety-five" onto the open bend next to it.
        fun speedWorthSaying(c: HorizonCorner): Boolean =
            c.vTargetMps < speed * params.speakSpeedBelowRatio ||
                c.band == com.rallycopilot.core.model.SeverityBand.HAIRPIN ||
                c.band == com.rallycopilot.core.model.SeverityBand.ONE ||
                c.band == com.rallycopilot.core.model.SeverityBand.TWO
        val speedCorners = chain.filter { speedWorthSaying(it) }.map { it.corner.id }.toSet()
        val currentGear = vehicle.currentGear()
        val needsDownshift = currentGear != null &&
            chain.any { c -> c.gear != null && c.gear < currentGear }
        val detail = NoteComposer.Detail(
            speed = params.speakSpeed && speedCorners.isNotEmpty(),
            speedCornerIds = speedCorners,
            gear = params.includeGear && vehicle.rpm() != null && needsDownshift,
        )

        val utterance = NoteComposer.compose(
            chain = chain,
            gapsM = gaps,
            detail = detail,
            deadlineDistanceM = deadline,
            budgetMs = params.burstBudgetMs,
            durationOf = { audio.clipDurationMs(it) },
        )
        chain.forEach { spokenCorners[it.corner.id] = now }
        telemetry.log("note", mapOf(
            "keys" to utterance.clipKeys.joinToString("+"),
            "urgent" to utterance.urgent,
            "chain" to chain.size,
            "speed" to speed,
            "deadlineM" to deadline,
            "corners" to chain.joinToString(";") { c ->
                "${c.corner.id}:${c.band}:${aheadOf(c).toInt()}m:v${(c.vTargetMps * 2.23694).toInt()}"
            },
            "detail" to "speed=${detail.speed},gear=${detail.gear}",
        ))
        audio.play(utterance)
        lastCall = "corners=" + chain.joinToString(",") { it.corner.id.toString() } +
            " keys=" + utterance.clipKeys.joinToString("+") +
            " speed=" + "%.1f".format(speed)
        runLog.logEvent(RunEvent(now, RunEventType.NOTE_SPOKEN, chain.joinToString(",") { it.corner.id.toString() }))
    }

    private var lastCall: String? = null
    private var lastPendingLogMs = 0L

    /**
     * The driver said "wrong". Stamp the last thing the co-driver said, plus where
     * and how fast the car is NOW, into the run log — the labelled example that
     * post-drive tuning (severity table, MPP weights, radius audit) feeds on.
     */
    fun flagLastCall() {
        val info = lastCall ?: return
        val m = lastMatch
        runLog.logEvent(RunEvent(clock.nowMs(), RunEventType.NOTE_FLAGGED,
            info + " at=" + (m?.let { "${it.edgeId}:${it.offsetM.toInt()}" } ?: "?")))
    }

    /** Metres consumed while the utterance plays, incl. BT latency — end-anchored timing. */
    private fun speechLeadM(speed: Double, c: HorizonCorner): Double {
        // Estimate with the details a real call is likely to carry. The old
        // estimate ignored the speed clip, so a call that gained "sixty" at
        // compose time travelled ~15-20 m further than the trigger had budgeted —
        // two of eleven calls in the first traced drive finished PAST their
        // braking point.
        val detail = NoteComposer.Detail(speed = params.speakSpeed, gear = false)
        val clipMs = NoteComposer.cornerKeys(c, detail).sumOf { audio.clipDurationMs(it) }
        return speed * (clipMs + audioLatencyMs) / 1000.0
    }

    /**
     * OBD wheel speed is preferred over GPS when available (faster, steadier), but
     * which one is actually in use is never left to guesswork — [usingObdSpeed]
     * drives the HUD so a silent fallback is impossible.
     */
    private fun fusedSpeed(gpsSpeed: Double): Double {
        val obd = if (speedSource == SpeedSource.GPS_ONLY) null else vehicle.obdSpeedMps()
        usingObdSpeed = obd != null
        return obd ?: gpsSpeed
    }

    @Volatile
    private var usingObdSpeed = false

    private fun advisorProfile() = advisor.profile

    /** Where the matched offset lands in the current horizon's progress space. */
    private fun anchoredProgressOn(h: Horizon, m: MatchedPosition): Double? {
        // Progress space starts at the position the horizon was built from on the same edge.
        val firstEdge = h.pathEdgeIds.firstOrNull() ?: return null
        if (m.edgeId != firstEdge) return null
        // A direction flip since the build forces a rebuild before this is used again;
        // anchoring must use the direction the horizon was BUILT with.
        if (m.forward != lastBuildForward) return null
        return if (lastBuildForward) m.offsetM - (lastBuildOffset ?: m.offsetM)
        else (lastBuildOffset ?: m.offsetM) - m.offsetM
    }

    private var lastBuildOffset: Double? = null
    private var lastBuildForward: Boolean = true
}
