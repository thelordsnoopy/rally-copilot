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

    /**
     * What the advisor should do about a corner: multiply the mapped radius by this.
     * Always ≤ 1 — the audit tightens corners or says nothing at all.
     */
    data class Advice(val radiusFactor: Double)

    private var currentCornerId: Long = -1
    private var currentMapRadiusM: Double = 0.0
    private var tightestDrivenM: Double = Double.MAX_VALUE

    /**
     * Feed every engine tick. [cornerId]/[mapRadiusM] describe the corner the car is
     * currently INSIDE (null when on a straight).
     *
     * A pass closes when the car leaves the corner; a pass that never met the gates
     * simply records nothing.
     */
    fun tick(
        cornerId: Long?,
        mapRadiusM: Double?,
        speedMps: Double,
        gpsAccuracyM: Double?,
        /**
         * The radius the car actually drove, from speed and GNSS course rate.
         *
         * This is the auditor's only input now. It needs no mount alignment — which
         * matters, because alignment never once completed in nineteen traced drives
         * and was removed in v0.21.0 along with the lateral-g route that depended on
         * it. It measures the PATH rather than the road, so a driver straightening a
         * bend reads wider than the map; that only ever produces ratios above 1,
         * which this auditor refuses to act on anyway. What it catches is the case
         * that matters: a map claiming 200 m where the car cannot get round above 70.
         */
        drivenRadiusM: Double? = null,
    ) {
        if (cornerId != currentCornerId) {
            closePass()
            currentCornerId = cornerId ?: -1
            currentMapRadiusM = mapRadiusM ?: 0.0
        }
        if (cornerId == null) return
        if (gpsAccuracyM == null || gpsAccuracyM > params.maxGpsAccuracyM) return
        if (speedMps < params.minSpeedMps) return
        // The path the car actually described. No mount, no alignment, no calibration.
        if (drivenRadiusM != null && drivenRadiusM.isFinite() && drivenRadiusM > 1.0) {
            if (drivenRadiusM < tightestDrivenM) tightestDrivenM = drivenRadiusM
        }
    }

    /** Close out any in-progress pass (drive end, corner left, horizon lost). */
    fun closePass() {
        val id = currentCornerId
        val rMap = currentMapRadiusM
        val driven = tightestDrivenM
        currentCornerId = -1
        tightestDrivenM = Double.MAX_VALUE
        if (id < 0 || rMap <= 0.0) return
        if (driven >= Double.MAX_VALUE) return
        val rImplied = driven
        val ratio = (rImplied / rMap).coerceIn(params.minRatio, params.maxRatio)
        val prev = store.get(id)
        val ema = if (prev == null) ratio
        else prev.ratioEma * (1 - params.emaAlpha) + ratio * params.emaAlpha
        store.put(CornerAudit(id, (prev?.passes ?: 0) + 1, ema))
    }

    /**
     * What the stored evidence says the advisor should do. Null = trust the map.
     *
     * Only ever acts on repeated evidence that the road is TIGHTER than mapped.
     * A corner that measures gentler than the map claims is left exactly as it is:
     * partly because a driven line is always wider than the road's centreline, so
     * "gentler" is the expected reading rather than news, and partly because
     * raising a suggested speed from inferred sensor data is not a thing this app
     * does. Single-pass disagreements no longer say anything aloud — a co-driver is
     * either sure or quiet.
     */
    fun adviceFor(cornerId: Long): Advice? {
        val a = store.get(cornerId) ?: return null
        if (a.passes >= params.minPassesToCorrect && a.ratioEma < params.correctBelow) {
            return Advice(a.ratioEma.coerceAtLeast(params.correctionFloor))
        }
        return null
    }
}
