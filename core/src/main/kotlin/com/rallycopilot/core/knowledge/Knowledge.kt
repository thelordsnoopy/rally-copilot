package com.rallycopilot.core.knowledge

/**
 * The personal knowledge layer: what YOUR drives have taught the app about specific
 * stretches of road, overlaid on the shipped map. Sunk cattle grids, pothole strings,
 * blind gateways — things no map knows and every local does.
 *
 * Keyed by (edgeId, 25 m bucket). Pure interfaces + maths here; storage in the app.
 */

data class RoadBucket(
    val edgeId: Long,
    val bucket: Int,               // offsetM / BUCKET_M
    val slowEvents: Int = 0,       // unexplained big slowdowns seen here
    val cleanPasses: Int = 0,      // passes at normal speed since last event
    val roughSum: Double = 0.0,    // vertical-accel RMS accumulator
    val roughN: Int = 0,
    val hazardConfirmed: Boolean = false,
    val speedFactor: Double = 1.0, // learned multiplier applied to vTarget here
) {
    val roughness: Double get() = if (roughN == 0) 0.0 else roughSum / roughN

    companion object {
        const val BUCKET_M = 25.0
        fun bucketOf(offsetM: Double): Int = (offsetM / BUCKET_M).toInt()
    }
}

/** Storage port. App implements over SQLite; tests use a map. */
interface KnowledgeStore {
    fun get(edgeId: Long, bucket: Int): RoadBucket?
    fun put(b: RoadBucket)
    /** Buckets on this edge that warrant a spoken caution. */
    fun cautionsOn(edgeId: Long): List<RoadBucket>
    /** Smallest learned speed factor across [startM, endM] of this edge. */
    fun factorFor(edgeId: Long, startM: Double, endM: Double): Double
}

object KnowledgeMath {
    /** A bucket earns a spoken caution when confirmed, or when evidence has piled up. */
    fun warrantsCaution(b: RoadBucket): Boolean =
        b.hazardConfirmed || b.slowEvents >= 3 || b.roughness > ROUGH_CAUTION

    const val ROUGH_CAUTION = 3.2       // m/s2 vertical RMS: genuinely rough surface

    /**
     * Update a bucket after an unexplained slowdown. [observedRatio] = actual/expected
     * speed. Confirmed hazards pin the factor low; soft events move it by EMA.
     */
    fun applySlowEvent(b: RoadBucket, observedRatio: Double, confirmed: Boolean): RoadBucket {
        val ratio = observedRatio.coerceIn(0.4, 1.0)
        val newFactor = if (confirmed) minOf(b.speedFactor, maxOf(0.7, ratio))
        else (b.speedFactor * 0.7 + ratio * 0.3).coerceIn(0.6, 1.0)
        return b.copy(
            slowEvents = b.slowEvents + 1,
            cleanPasses = 0,
            hazardConfirmed = b.hazardConfirmed || confirmed,
            speedFactor = newFactor,
        )
    }

    /**
     * Update after passing through at normal speed with no drama: evidence decays.
     * A confirmed hazard needs several clean passes before it starts to relax —
     * the grid may just have been driven around this time.
     */
    fun applyCleanPass(b: RoadBucket): RoadBucket {
        val passes = b.cleanPasses + 1
        val relax = when {
            b.hazardConfirmed && passes < 4 -> b.speedFactor
            else -> (b.speedFactor + 0.05).coerceAtMost(1.0)
        }
        val stillConfirmed = b.hazardConfirmed && passes < 8
        return b.copy(
            cleanPasses = passes,
            speedFactor = relax,
            hazardConfirmed = stillConfirmed,
            slowEvents = if (passes % 3 == 0 && b.slowEvents > 0) b.slowEvents - 1 else b.slowEvents,
        )
    }

    fun addRoughness(b: RoadBucket, rms: Double): RoadBucket =
        b.copy(roughSum = b.roughSum + rms, roughN = b.roughN + 1)
}

/**
 * Watches for unexplained slowdowns: you doing a speed far below what the model
 * expects, sustained, not near a junction or mapped hazard, not stop-and-go traffic.
 * Emits an anomaly the UI turns into the 7-second "HAZARD?" prompt.
 */
class SlowdownMonitor(
    private val params: Params = Params(),
) {
    data class Params(
        /** Anomaly when actual < this fraction of expected... */
        val ratioThreshold: Double = 0.55,
        /** ...sustained for this long. */
        val sustainMs: Long = 2000,
        /** Ignore near-stationary (junction queues, parking). */
        val minSpeedMps: Double = 2.5,
        /** Suppress near mapped hazards/junctions — slowing there is explained. */
        val suppressNearMappedM: Double = 80.0,
        /** One prompt per bucket per drive; global cooldown between prompts. */
        val cooldownMs: Long = 30_000,
    )

    data class Anomaly(val edgeId: Long, val offsetM: Double, val observedRatio: Double, val hadBump: Boolean)

    private var belowSinceMs = -1L
    private var lastPromptMs = Long.MIN_VALUE / 2
    private var minRatioInEpisode = 1.0
    private val promptedBuckets = HashSet<Long>()
    private var recentBumpMs = Long.MIN_VALUE / 2

    /** App layer calls this when the IMU sees a vertical spike (pothole hit). */
    fun reportBump(tMs: Long) { recentBumpMs = tMs }

    fun tick(
        tMs: Long,
        edgeId: Long,
        offsetM: Double,
        speedMps: Double,
        expectedMps: Double,
        nearMappedHazardM: Double?,   // distance to nearest mapped hazard/junction, null if none near
    ): Anomaly? {
        if (expectedMps < 4.0 || speedMps < params.minSpeedMps) { belowSinceMs = -1; return null }
        if (nearMappedHazardM != null && nearMappedHazardM < params.suppressNearMappedM) {
            belowSinceMs = -1; return null
        }
        val ratio = speedMps / expectedMps
        if (ratio >= params.ratioThreshold) { belowSinceMs = -1; minRatioInEpisode = 1.0; return null }

        if (belowSinceMs < 0) { belowSinceMs = tMs; minRatioInEpisode = ratio }
        minRatioInEpisode = minOf(minRatioInEpisode, ratio)
        if (tMs - belowSinceMs < params.sustainMs) return null
        if (tMs - lastPromptMs < params.cooldownMs) return null

        val key = edgeId * 100_000 + RoadBucket.bucketOf(offsetM)
        if (key in promptedBuckets) return null
        promptedBuckets += key
        lastPromptMs = tMs
        belowSinceMs = -1
        return Anomaly(
            edgeId = edgeId, offsetM = offsetM,
            observedRatio = minRatioInEpisode,
            hadBump = tMs - recentBumpMs < 6000,
        )
    }
}
