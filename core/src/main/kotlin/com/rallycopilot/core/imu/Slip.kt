package com.rallycopilot.core.imu

import kotlin.math.abs

/**
 * Is the car going where it is pointing?
 *
 * Two independent measurements of "how fast is this car turning":
 *
 *  - YAW RATE, from the gyroscope: how fast the car BODY is rotating.
 *  - COURSE RATE, from successive GNSS bearings: how fast the car's VELOCITY
 *    VECTOR is rotating — the direction it is actually travelling.
 *
 * On a gripping car these are the same number. When they diverge, the car is
 * sliding: the body is rotating faster than the path is turning (oversteer,
 * rotation) or slower (understeer, running wide). That difference is the sideslip
 * rate, and it is the textbook definition rather than anything invented here.
 *
 * WHY THIS MATTERS TO THE APP. Learned pace comes from `aLat = v² / R`, where R is
 * the MAP's radius — an assumption that the car followed the road's curve. A car
 * with a welded diff on worn rear tyres may be doing nothing of the sort, and a
 * corner "taken at 0.9 g" while sliding is not evidence of grip. Since the learning
 * loop has no absolute ceiling by the user's decision, a slide teaching the model
 * that a corner is quick is precisely the drift that guardrails exist to stop.
 *
 * WHY IT WORKS WHEN THE MOUNT DOES NOT. Yaw rate is the gyroscope's component along
 * the vertical, and vertical is known from gravity alone. Rotating the phone about
 * that same vertical axis — the holder swivelling in its cradle — does not change
 * the vertical component at all. So unlike [MountAlignment], which needs to find
 * car-forward in phone coordinates, this needs no alignment, no calibration, and no
 * particular mounting angle.
 *
 * Pure maths, no Android: the app supplies the two rates.
 */
class SlipEstimator(private val params: Params = Params()) {

    data class Params(
        /** Below this there is no meaningful cornering to compare, m/s. */
        val minSpeedMps: Double = 6.0,
        /**
         * Both rates must exceed this before a comparison means anything, rad/s.
         * A GNSS bearing is only good to a handful of degrees, so on a near-straight
         * road the ratio of two small noisy numbers is meaningless.
         */
        val minTurnRateRadS: Double = 0.09, // ~5 deg/s
        /** Smoothing on both rates, per sample at ~10 Hz. */
        val emaAlpha: Double = 0.25,
        /** Body rotating this much MORE than the path is turning: oversteer. */
        val oversteerRatio: Double = 1.30,
        /** ...and this much less: understeer, running wide. */
        val understeerRatio: Double = 0.70,
        /** A verdict must persist this long before it counts as a slide, ms. */
        val sustainMs: Long = 500,
    )

    enum class Verdict {
        /** Not enough speed or not enough turning to say anything. */
        UNKNOWN,
        /** Body rotation and path curvature agree: the car is gripping. */
        NEUTRAL,
        /** Turning less than the road demands — pushing wide. */
        UNDERSTEER,
        /** Turning more than the path is going — rotating. */
        OVERSTEER,
    }

    data class State(
        val yawRateRadS: Double,
        val courseRateRadS: Double,
        val ratio: Double,
        val verdict: Verdict,
        /** True once a non-neutral verdict has persisted past [Params.sustainMs]. */
        val sliding: Boolean,
    )

    private var yawEma = 0.0
    private var courseEma = 0.0
    private var have = false
    private var verdictSince = 0L
    private var currentVerdict = Verdict.UNKNOWN

    var state = State(0.0, 0.0, 1.0, Verdict.UNKNOWN, false)
        private set

    /** True at any point during the last corner. Read and cleared by the collector. */
    var slidSinceReset = false
        private set

    fun resetSlide() { slidSinceReset = false }

    /**
     * [yawRateRadS] signed body rotation about the vertical, [courseRateRadS] signed
     * rotation of the velocity vector. Signs must share a convention; only their
     * relative size and agreement are used.
     */
    fun tick(tMs: Long, yawRateRadS: Double, courseRateRadS: Double, speedMps: Double): State {
        if (!yawRateRadS.isFinite() || !courseRateRadS.isFinite() || !speedMps.isFinite()) {
            return state
        }
        if (!have) {
            yawEma = yawRateRadS; courseEma = courseRateRadS; have = true
        } else {
            yawEma += (yawRateRadS - yawEma) * params.emaAlpha
            courseEma += (courseRateRadS - courseEma) * params.emaAlpha
        }

        val y = abs(yawEma)
        val c = abs(courseEma)
        val verdict = when {
            speedMps < params.minSpeedMps -> Verdict.UNKNOWN
            // BOTH must be turning: one small noisy number over another is not a ratio.
            y < params.minTurnRateRadS && c < params.minTurnRateRadS -> Verdict.UNKNOWN
            // Opposite directions mid-corner is a spin, not a ratio worth taking.
            yawEma * courseEma < 0 && y > params.minTurnRateRadS && c > params.minTurnRateRadS ->
                Verdict.OVERSTEER
            c < 1e-6 -> Verdict.UNKNOWN
            y / c >= params.oversteerRatio -> Verdict.OVERSTEER
            y / c <= params.understeerRatio -> Verdict.UNDERSTEER
            else -> Verdict.NEUTRAL
        }
        val ratio = if (c > 1e-6) y / c else 1.0

        if (verdict != currentVerdict) {
            currentVerdict = verdict
            verdictSince = tMs
        }
        val sliding = (verdict == Verdict.OVERSTEER || verdict == Verdict.UNDERSTEER) &&
            tMs - verdictSince >= params.sustainMs
        if (sliding) slidSinceReset = true

        state = State(yawEma, courseEma, ratio, verdict, sliding)
        return state
    }

    /**
     * The radius the car ACTUALLY drove, metres, from speed and course rate — the
     * path through the corner rather than the road's centreline. Null when not
     * turning enough to measure. Independent of the map, and of the mount.
     */
    fun drivenRadiusM(speedMps: Double): Double? {
        val c = abs(courseEma)
        if (!have || c < params.minTurnRateRadS || speedMps < params.minSpeedMps) return null
        return speedMps / c
    }
}
