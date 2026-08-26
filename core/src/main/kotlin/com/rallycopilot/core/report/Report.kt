package com.rallycopilot.core.report

import com.rallycopilot.core.model.CornerObservation
import com.rallycopilot.core.model.SeverityBand
import kotlin.math.sqrt

/**
 * Post-drive scoring, road identity hashing for personal bests, and the learned
 * "traffic encountered" score that powers quiet-road discovery.
 */
object Report {

    data class DriveSummary(
        val distanceM: Double,
        val durationMs: Long,
        val cornersByBand: Map<SeverityBand, Int>,
        /** 0..100: how close entry speeds were to suggestion — RMS error mapped down. */
        val smoothness: Int,
        val constrainedFraction: Double,
    )

    fun summarise(
        distanceM: Double,
        durationMs: Long,
        observations: List<CornerObservation>,
        vSuggestedOf: (CornerObservation) -> Double?,
    ): DriveSummary {
        val byBand = observations.groupingBy { it.band }.eachCount()
        val errs = observations.mapNotNull { o ->
            val vs = vSuggestedOf(o) ?: return@mapNotNull null
            if (vs <= 0) null else ((o.vMinMps - vs) / vs)
        }
        val rms = if (errs.isEmpty()) 0.0 else sqrt(errs.sumOf { it * it } / errs.size)
        val smoothness = ((1.0 - rms.coerceIn(0.0, 1.0)) * 100).toInt()
        val constrained = if (observations.isEmpty()) 0.0
        else observations.count { it.wasConstrained }.toDouble() / observations.size
        return DriveSummary(distanceM, durationMs, byBand, smoothness, constrained)
    }

    /**
     * Stable identity for a stretch of road: hash of its ordered edge-id chain.
     * Immune to renames; personal bests key off this.
     */
    fun roadHash(edgeIds: List<Long>): Long {
        var h = 1125899906842597L
        for (id in edgeIds) h = 31 * h + id
        return h
    }

    /**
     * Traffic score for road discovery: fraction of constrained corners on this road,
     * bucketed by hour-of-day. Learned from your own drives — no live data needed.
     * Lower = quieter when you drive it.
     */
    data class TrafficBucket(val hourOfDay: Int, val constrained: Int, val total: Int) {
        val score: Double get() = if (total == 0) 0.5 else constrained.toDouble() / total
    }

    /**
     * Twistiness for road discovery: corner density weighted by severity.
     * Precomputable per edge chain at build time.
     */
    fun twistiness(cornerBands: List<SeverityBand>, lengthM: Double): Double {
        if (lengthM <= 0) return 0.0
        val weight = cornerBands.sumOf {
            when (it) {
                SeverityBand.HAIRPIN -> 6.0
                SeverityBand.ONE -> 5.0
                SeverityBand.TWO -> 4.0
                SeverityBand.THREE -> 3.0
                SeverityBand.FOUR -> 2.0
                SeverityBand.FIVE -> 1.0
                SeverityBand.SIX -> 0.5
                SeverityBand.FLAT -> 0.0
            }
        }
        return weight / (lengthM / 1000.0) // severity-weighted corners per km
    }
}

/**
 * Incident detection (opt-in): a large longitudinal deceleration spike followed by a
 * stop. Pure state machine — the app layer owns the "Are you OK?" countdown and SMS.
 */
class IncidentDetector(
    /** Deceleration threshold, m/s² — ~0.9 g sustained over the sample gap. */
    private val decelThreshold: Double = 8.8,
    private val stopWindowMs: Long = 8000,
) {
    private var lastSpeed = Double.NaN
    private var lastT = 0L
    private var spikeAtMs = 0L

    /** Feed speed each tick; returns true when an incident is suspected. */
    fun tick(tMs: Long, speedMps: Double): Boolean {
        if (!lastSpeed.isNaN() && tMs > lastT) {
            val dt = (tMs - lastT) / 1000.0
            if (dt in 0.05..3.0) {
                val decel = (lastSpeed - speedMps) / dt
                if (decel > decelThreshold && lastSpeed > 8.0) spikeAtMs = tMs
            }
        }
        lastSpeed = speedMps
        lastT = tMs
        return spikeAtMs > 0 && tMs - spikeAtMs < stopWindowMs && speedMps < 0.8
    }

    fun reset() { spikeAtMs = 0 }
}
