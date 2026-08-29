package com.rallycopilot.core.profile

import com.rallycopilot.core.model.Conditions
import com.rallycopilot.core.model.CornerObservation
import com.rallycopilot.core.model.HorizonCorner

/**
 * Runs BEHIND the car: as each corner is exited, records what you actually did.
 * Never influences the current drive — only the profile, later.
 */
class ObservationCollector(
    private val runId: Long,
    private val conditions: Conditions,
) {
    companion object {
        /** Slack past the mapped arc before a detached corner is closed out — the
         *  driven line runs slightly long, and closing early clips the exit speed. */
        const val DETACHED_MARGIN_M = 15.0

        /**
         * Lateral acceleration at which a corner is self-evidently committed, and
         * counts as spirited whatever the sustained detector thinks. In m/s².
         *
         * The sustained detector exists so a brief squirt of throttle never trains
         * the model, and that intent is right. But its proxy for "spirited" needs
         * about two minutes of moving before the pace vote even activates, and the
         * first real trace was a 2.6 minute drive: the first spirited tick landed at
         * +132 s, so twelve of seventeen corners were rejected as "not spirited" —
         * including ones taken at 0.90 g, 0.78 g and 0.69 g. A corner held at 0.6 g
         * is not a short pull or an accident of traffic; it is the driver at work,
         * and it is exactly the sample the profile is starved of.
         *
         * Note this cannot be gamed by a gentle drive: it is a floor on measured
         * cornering load, and the per-session ratchet still bounds how far one
         * session may move any band.
         */
        const val DECISIVE_A_LAT = 0.60 * 9.81

        /**
         * The same floor for a WET drive. 0.60 g is a dry number: drive 44 was a
         * wet drive whose hardest corners ran 0.39-0.50 g — genuinely committed
         * for the grip available — and fifteen of its twenty-nine observations
         * were rejected "not spirited" because the sustained detector's pace vote
         * had not warmed up and the dry floor was unreachable. The same corners
         * driven a second time, 3 mph faster, were kept; the profile was starved
         * by a threshold that assumed dry grip in the rain.
         */
        const val DECISIVE_A_LAT_WET = 0.42 * 9.81
    }

    /** Cornering load that is self-evidently spirited under THESE conditions. */
    private val decisiveALat =
        if (conditions == Conditions.WET) DECISIVE_A_LAT_WET else DECISIVE_A_LAT

    private data class Tracking(
        var hc: HorizonCorner,
        var vEntry: Double = Double.NaN,
        var vMin: Double = Double.MAX_VALUE,
        var vExit: Double = Double.NaN,
        var throttleSum: Double = 0.0,
        var throttleN: Int = 0,
        var sawStopAfter: Boolean = false,
        var wasSpirited: Boolean = false,
        /**
         * Metres driven since this corner was entered, integrated from live speed.
         *
         * This is what makes a corner survive to become an observation. The horizon
         * is rebuilt whenever heading changes by more than 25°, which is guaranteed
         * part-way through any real corner — and a rebuilt horizon only lists
         * corners still AHEAD, so the corner being driven vanishes from it. The
         * tracker used to be discarded at that moment, which is to say: every corner
         * actually driven through was thrown away, and only the gentle ones that
         * never triggered a rebuild ever reached the profile.
         */
        var travelledM: Double = 0.0,
        /** The corner is no longer in the horizon; distance alone closes it now. */
        var detached: Boolean = false,
        /** The matcher put us on this corner's own edge while we were driving it. */
        var confirmed: Boolean = false,
        /** The car was sliding at some point through this corner. */
        var slid: Boolean = false,
    )

    private var spiritedNow: Boolean = false

    private val active = ArrayList<Tracking>()
    private val done = ArrayList<CornerObservation>()
    /** The corner that just closed, for live coaching. Read once, then gone. */
    private var lastClosed: Pair<CornerObservation, HorizonCorner>? = null

    fun takeLastClosed(): Pair<CornerObservation, HorizonCorner>? {
        val v = lastClosed
        lastClosed = null
        return v
    }

    /** Observations closed since the last drain — for logging each one AT the
     *  moment and place it closed. A batch stamped at drive_end answers "what",
     *  never "where": drive 44's twenty-nine obs records all carried +392.8 s. */
    private val closedUnlogged = ArrayList<CornerObservation>()
    fun drainClosed(): List<CornerObservation> {
        if (closedUnlogged.isEmpty()) return emptyList()
        val out = closedUnlogged.toList()
        closedUnlogged.clear()
        return out
    }

    val observations: List<CornerObservation> get() = done

    /** Corners currently being tracked through — black box only. */
    val activeCount: Int get() = active.size

    /**
     * The horizon was rebuilt: every active tracker's distances are stale. Remap each
     * onto the corner's fresh HorizonCorner, or abort trackers whose corner is gone —
     * closing them against old coordinates would ratchet vMin through unrelated road
     * and save a garbage observation.
     */
    fun onHorizonRebuilt(newCorners: List<HorizonCorner>) {
        val byId = newCorners.associateBy { it.corner.id }
        for (t in active) {
            val fresh = byId[t.hc.corner.id]
            if (fresh != null) {
                t.hc = fresh
                t.detached = false
            } else {
                // Gone from the horizon because we are INSIDE it. Keep tracking on
                // distance travelled — never discard it, that was the whole bug.
                t.detached = true
            }
        }
    }

    /**
     * Feed the collector each engine tick.
     * [distanceAheadOf] maps a horizon corner to its current distance-ahead (negative = behind us).
     * [spiritedNow] is the style detector's current verdict; it stamps each observation.
     */
    fun tick(
        tMs: Long,
        speedMps: Double,
        throttle01: Double?,
        horizonCorners: List<HorizonCorner>,
        // Fail CLOSED: with no style verdict, corners must not train the profile.
        spiritedNow: Boolean = false,
        /** The edge the matcher currently has the car on, for confirmation. */
        matchedEdgeId: Long? = null,
        /** The car is not going where it is pointing right now. */
        slipping: Boolean = false,
        distanceAheadOf: (HorizonCorner) -> Double,
    ) {
        this.spiritedNow = spiritedNow
        // Metres covered since the last tick, for the distance-based fallback.
        val dtS = if (lastTickMs == 0L) 0.0 else ((tMs - lastTickMs) / 1000.0).coerceIn(0.0, 2.0)
        lastTickMs = tMs
        val stepM = speedMps * dtS

        // Start tracking corners we are about to enter.
        for (hc in horizonCorners) {
            val ahead = distanceAheadOf(hc)
            if (ahead in 0.0..15.0 && active.none { it.hc.corner.id == hc.corner.id }) {
                active += Tracking(hc, vEntry = speedMps, wasSpirited = spiritedNow)
            }
        }
        // Update and close out corners we are inside / past.
        val it = active.iterator()
        while (it.hasNext()) {
            val t = it.next()
            t.travelledM += stepM
            // Corner still on the horizon: trust its geometry. Detached (we are
            // inside it, so it has dropped off): fall back to distance travelled.
            val stillInside = if (t.detached) {
                t.travelledM < t.hc.corner.arcLengthM + DETACHED_MARGIN_M
            } else {
                distanceAheadOf(t.hc) + t.hc.corner.arcLengthM > 0.0
            }
            if (stillInside) {
                // Proof we are on this corner's road, not one the horizon guessed.
                if (matchedEdgeId != null && matchedEdgeId == t.hc.corner.edgeId) t.confirmed = true
                if (slipping) t.slid = true
                if (speedMps < t.vMin) t.vMin = speedMps
                if (throttle01 != null) { t.throttleSum += throttle01; t.throttleN++ }
            } else {
                t.vExit = speedMps
                if (speedMps < 1.0) t.sawStopAfter = true
                val closed = finish(t, tMs)
                done += closed
                closedUnlogged += closed
                lastClosed = closed to t.hc
                it.remove()
            }
        }
    }

    private var lastTickMs = 0L

    private fun finish(t: Tracking, tMs: Long): CornerObservation {
        val r = t.hc.corner.minRadiusM
        val vMin = if (t.vMin == Double.MAX_VALUE) t.vEntry else t.vMin
        val aLat = (vMin * vMin) / r
        val throttleMean = if (t.throttleN > 0) t.throttleSum / t.throttleN else null
        return CornerObservation(
            runId = runId,
            cornerId = t.hc.corner.id,
            tMs = tMs,
            band = t.hc.band,
            minRadiusM = r,
            vEntryMps = t.vEntry,
            vMinMps = vMin,
            vExitMps = t.vExit,
            aLatObserved = aLat,
            mapConfidence = t.hc.corner.confidence,
            pathConfidence = t.hc.pathConfidence,
            wasConstrained = constrained(t, throttleMean),
            conditions = conditions,
            throttleMean = throttleMean,
            // Spirited at entry, at exit, or self-evidently from the cornering load.
            spirited = t.wasSpirited || spiritedNow || aLat >= decisiveALat,
            confirmed = t.confirmed,
            slid = t.slid,
        )
    }

    /**
     * Were you the limiting factor? Reject as constrained when:
     *  - speed flat across entry→min (following someone), or
     *  - you stopped shortly after (junction/traffic), or
     *  - OBD throttle says you were coasting through (low mean throttle + low aLat).
     */
    private fun constrained(t: Tracking, throttleMean: Double?): Boolean {
        val vMin = if (t.vMin == Double.MAX_VALUE) t.vEntry else t.vMin
        val flatSpeed = t.vEntry.isFinite() && vMin > 0 && (t.vEntry - vMin) / t.vEntry < 0.04 &&
            t.vEntry < 0.7 * (t.hc.vTargetMps.takeIf { it > 0 } ?: Double.MAX_VALUE)
        val stopped = t.sawStopAfter
        val coasting = throttleMean != null && throttleMean < 0.12 &&
            (vMin * vMin / t.hc.corner.minRadiusM) < 2.0
        return flatSpeed || stopped || coasting
    }
}
