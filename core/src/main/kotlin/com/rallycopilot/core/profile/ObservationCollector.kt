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
    private data class Tracking(
        val hc: HorizonCorner,
        var vEntry: Double = Double.NaN,
        var vMin: Double = Double.MAX_VALUE,
        var vExit: Double = Double.NaN,
        var throttleSum: Double = 0.0,
        var throttleN: Int = 0,
        var sawStopAfter: Boolean = false,
    )

    private val active = ArrayList<Tracking>()
    private val done = ArrayList<CornerObservation>()

    val observations: List<CornerObservation> get() = done

    /**
     * Feed the collector each engine tick.
     * [distanceAheadOf] maps a horizon corner to its current distance-ahead (negative = behind us).
     */
    fun tick(
        tMs: Long,
        speedMps: Double,
        throttle01: Double?,
        horizonCorners: List<HorizonCorner>,
        distanceAheadOf: (HorizonCorner) -> Double,
    ) {
        // Start tracking corners we are about to enter.
        for (hc in horizonCorners) {
            val ahead = distanceAheadOf(hc)
            if (ahead in 0.0..15.0 && active.none { it.hc.corner.id == hc.corner.id }) {
                active += Tracking(hc, vEntry = speedMps)
            }
        }
        // Update and close out corners we are inside / past.
        val it = active.iterator()
        while (it.hasNext()) {
            val t = it.next()
            val ahead = distanceAheadOf(t.hc)
            val exitAhead = ahead + t.hc.corner.arcLengthM
            when {
                exitAhead > 0.0 -> { // still inside (or approaching apex)
                    if (speedMps < t.vMin) t.vMin = speedMps
                    if (throttle01 != null) { t.throttleSum += throttle01; t.throttleN++ }
                }
                else -> { // fully past: close out
                    t.vExit = speedMps
                    if (speedMps < 1.0) t.sawStopAfter = true
                    done += finish(t, tMs)
                    it.remove()
                }
            }
        }
    }

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
