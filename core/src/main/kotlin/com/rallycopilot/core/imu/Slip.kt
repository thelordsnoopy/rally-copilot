package com.rallycopilot.core.imu

import kotlin.math.abs
import kotlin.math.max

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
 * TIMING. The course rate is a trailing measurement: d(bearing)/dt over the last
 * GNSS second describes the path HALF A SECOND AGO on average, plus fix latency.
 * The gyro is instantaneous. Compared raw, yaw leads course through every corner
 * entry and the estimator reads phantom oversteer, then phantom understeer on
 * exit — drive 42 spent 344 samples "sliding" on a dry, gentle drive that way.
 * So the verdict compares the course rate against the yaw rate from
 * [Params.yawDelayMs] ago. Replayed against that trace, the delay plus the
 * both-rates gate cut the false slides from 344 samples to 3.
 *
 * Pure maths, no Android: the app supplies the two rates.
 */
class SlipEstimator(private val params: Params = Params()) {

    data class Params(
        /** Below this there is no meaningful cornering to compare, m/s. */
        val minSpeedMps: Double = 6.0,
        /**
         * BOTH rates must exceed this before a comparison means anything, rad/s.
         * A GNSS bearing is only good to a handful of degrees, so on a near-straight
         * road the ratio of two small noisy numbers is meaningless.
         */
        val minTurnRateRadS: Double = 0.09, // ~5 deg/s
        /**
         * A body rotation this strong with NO course rotation is a spin in
         * progress, not a number to be shy about, rad/s.
         */
        val spinYawRadS: Double = 0.35, // ~20 deg/s
        /**
         * The disagreement must involve real cornering load: speed × turn rate is
         * the lateral acceleration the faster of the two rates implies, m/s².
         * Below this the "slide" would be one a shopping trolley could hold.
         */
        val minLatAccelMps2: Double = 1.2,
        /** Smoothing on both rates, per sample at ~10 Hz. */
        val emaAlpha: Double = 0.25,
        /**
         * Compare course rate against the yaw from this long ago. The course rate
         * is an average over the previous GNSS second (centred ~500 ms in the
         * past) arriving with fix latency on top; the gyro is now.
         */
        val yawDelayMs: Long = 700,
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

    /** Recent yaw EMA history so the verdict can look [Params.yawDelayMs] back. */
    private val yawHist = ArrayDeque<Pair<Long, Double>>()

    var state = State(0.0, 0.0, 1.0, Verdict.UNKNOWN, false)
        private set

    /** True at any point during the last corner. Read and cleared by the collector. */
    var slidSinceReset = false
        private set

    fun resetSlide() { slidSinceReset = false }

    /**
     * [yawRateRadS] signed body rotation about the vertical, [courseRateRadS] signed
     * rotation of the velocity vector. Signs MUST share a convention (positive =
     * anticlockwise seen from above); opposite signs while both turning hard reads
     * as a spin. Drive 42 proved how much that matters: the app layer fed yaw with
     * the sign inverted and every ordinary corner became "OVERSTEER".
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

        yawHist.addLast(tMs to yawEma)
        // ~64 entries covers 6 s at 10 Hz — far more than the delay needs.
        while (yawHist.size > 64) yawHist.removeFirst()
        val targetT = tMs - params.yawDelayMs
        var delayedYaw = yawHist.first().second
        for ((t, v) in yawHist) {
            if (t <= targetT) delayedYaw = v else break
        }

        val y = abs(delayedYaw)
        val c = abs(courseEma)
        val verdict = when {
            speedMps < params.minSpeedMps -> Verdict.UNKNOWN
            // Strong body rotation the path never shows: a spin in progress.
            y >= params.spinYawRadS && c < params.minTurnRateRadS -> Verdict.OVERSTEER
            // BOTH must be turning: one small noisy number over another is not a ratio.
            y < params.minTurnRateRadS || c < params.minTurnRateRadS -> Verdict.UNKNOWN
            // Opposite directions mid-corner is a spin, not a ratio worth taking.
            delayedYaw * courseEma < 0 -> Verdict.OVERSTEER
            // No real cornering load: nothing slides at trolley speeds.
            speedMps * max(y, c) < params.minLatAccelMps2 -> Verdict.UNKNOWN
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
