package com.rallycopilot.core.knowledge

/**
 * The "map is lying" detector.
 *
 * Mid-corner, the IMU's lateral acceleration and the live speed imply the road's
 * REAL radius: R = v² / aLat. Each qualifying pass through a known corner compares
 * that against the mapped radius. A single wild mismatch makes the corner a HEDGED
 * call next time ("care left three"); two or more consistent passes correct the
 * corner's effective radius before banding and speed maths.
 *
 * Corrections only ever TIGHTEN. A map that under-claims severity gets fixed; one
 * that over-claims stays as-is — raising suggested speeds from inferred sensor data
 * is not something this app does.
 *
 * Passes are gated hard, because a bad measurement here moves real braking points:
 * GPS accuracy, mount alignment, and a lateral acceleration large enough that the
 * division is measuring the corner and not sensor noise.
 */

/** Accumulated audit state for one corner. Keyed by corner id — corner ids are NOT
 *  stable across map rebuilds, so the app wipes this store on map update. */
data class CornerAudit(
    val cornerId: Long,
    val passes: Int,
    /** EMA of (implied radius / mapped radius). 1.0 = the map is right.
     *  Below 1 = the road is TIGHTER than mapped. */
    val ratioEma: Double,
)

/** Storage port. App implements over SQLite; tests use a map. */
interface AuditStore {
    fun get(cornerId: Long): CornerAudit?
    fun put(a: CornerAudit)
}

class RadiusAuditor(
    private val store: AuditStore,
    private val params: Params = Params(),
) {
    data class Params(
        /** A pass only counts while GPS is at least this good. */
        val maxGpsAccuracyM: Double = 12.0,
        /** ...and the car is genuinely moving (walking-pace v²/a is noise). */
        val minSpeedMps: Double = 8.0,
        /** ...and cornering hard enough that aLat is signal, not sensor noise. */
        val minALatMps2: Double = 2.5,
        /** Implausible single-pass ratios are clamped before entering the EMA. */
        val minRatio: Double = 0.4,
        val maxRatio: Double = 2.5,
        /** New passes move the EMA by this much — recent evidence dominates. */
        val emaAlpha: Double = 0.5,
        /** One pass disagreeing this much (either way) → hedge the next call. */
        val hedgeBelow: Double = 0.75,
        val hedgeAbove: Double = 1.35,
        /** ≥2 passes agreeing the road is tighter than this → correct the radius. */
        val correctBelow: Double = 0.85,
        val minPassesToCorrect: Int = 2,
        /** Never shrink a radius past half of what the map claims. */
        val correctionFloor: Double = 0.5,
    )

    /** What the advisor should do about a corner, derived from stored audit state. */
    data class Advice(val radiusFactor: Double, val hedge: Boolean)

    private var currentCornerId: Long = -1
    private var currentMapRadiusM: Double = 0.0
    private var bestALat: Double = 0.0
    private var speedAtBest: Double = 0.0

    /**
     * Feed every engine tick. [cornerId]/[mapRadiusM] describe the corner the car is
     * currently INSIDE (null when on a straight). [aLatImuMps2] is the IMU's measured
     * lateral acceleration magnitude in the car frame, null until the mount is aligned.
     *
     * A pass closes when the car leaves the corner; a pass that never met the gates
     * simply records nothing.
     */
    fun tick(
        cornerId: Long?,
        mapRadiusM: Double?,
        speedMps: Double,
        aLatImuMps2: Double?,
        gpsAccuracyM: Double?,
    ) {
        if (cornerId != currentCornerId) {
            closePass()
            currentCornerId = cornerId ?: -1
            currentMapRadiusM = mapRadiusM ?: 0.0
        }
        if (cornerId == null) return
        // Gates: every sample must qualify on its own; the pass keeps only the
        // hardest-cornering qualifying moment (peak aLat ↔ closest to the apex).
        if (aLatImuMps2 == null || !aLatImuMps2.isFinite()) return
        if (gpsAccuracyM == null || gpsAccuracyM > params.maxGpsAccuracyM) return
        if (speedMps < params.minSpeedMps) return
        if (aLatImuMps2 < params.minALatMps2) return
        if (aLatImuMps2 > bestALat) {
            bestALat = aLatImuMps2
            speedAtBest = speedMps
        }
    }

    /** Close out any in-progress pass (drive end, corner left, horizon lost). */
    fun closePass() {
        val id = currentCornerId
        val rMap = currentMapRadiusM
        val aLat = bestALat
        val v = speedAtBest
        currentCornerId = -1
        bestALat = 0.0
        speedAtBest = 0.0
        if (id < 0 || rMap <= 0.0 || aLat < params.minALatMps2) return
        val rImplied = (v * v) / aLat
        val ratio = (rImplied / rMap).coerceIn(params.minRatio, params.maxRatio)
        val prev = store.get(id)
        val ema = if (prev == null) ratio
        else prev.ratioEma * (1 - params.emaAlpha) + ratio * params.emaAlpha
        store.put(CornerAudit(id, (prev?.passes ?: 0) + 1, ema))
    }

    /** What the stored evidence says the advisor should do. Null = trust the map. */
    fun adviceFor(cornerId: Long): Advice? {
        val a = store.get(cornerId) ?: return null
        if (a.passes >= params.minPassesToCorrect && a.ratioEma < params.correctBelow) {
            // Consistent evidence the road is tighter than mapped: correct it.
            // No hedge — at this point the measurement is more trusted than the map.
            return Advice(
                radiusFactor = a.ratioEma.coerceAtLeast(params.correctionFloor),
                hedge = false,
            )
        }
        if (a.ratioEma < params.hedgeBelow || a.ratioEma > params.hedgeAbove) {
            // Disagreement without enough passes to act on it (or in the direction we
            // refuse to act on): say so out loud, change nothing.
            return Advice(radiusFactor = 1.0, hedge = true)
        }
        return null
    }
}
