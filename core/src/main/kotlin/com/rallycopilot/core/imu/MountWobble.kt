package com.rallycopilot.core.imu

/**
 * How much the phone is MOVING IN ITS MOUNT, in degrees.
 *
 * A fast EMA of the angle between instantaneous gravity and a slow baseline of
 * where gravity has been sitting. A rigid mount reads 2-5° — road pitch and
 * camber. Chad's first traced drives read 8-15° sustained with excursions past
 * 37°: "a bit of play, an inch or so" in the holder, which on a six-inch phone is
 * tens of degrees of rotation.
 *
 * This number earned its keep. It is what told the driver to wedge the holder,
 * and drives 52-55 read a median of 2.6° afterwards — the one part of the IMU
 * story that produced a fix in the real world rather than another theory.
 *
 * WHAT USED TO LIVE HERE. A `MountAlignment` that tried to find which way the car
 * points in phone coordinates, so lateral g and road camber could be measured.
 * Across nineteen traced drives it never once produced a usable reading, and six
 * separate approaches to its final blocker — resolving which end of the learned
 * axis is forward — were each tested against the traces and each failed:
 *
 *   1. per-sample dv/dt sign          — 54% accurate, no better than a coin
 *   2. per-window dv/dt vote          — |votes| peaked at 3-5 against a need of 5
 *   3. the same, weighted by |dv|     — converged on one drive of three
 *   4. wobble-gated events only       — coherence 0.146 on 160 events
 *   5. cornering (a_lat vs yaw sign)  — axis disagreed 56-84° BETWEEN drives
 *   6. braking/acceleration asymmetry — 4 drives of 6 agreed; accumulating across
 *                                       drives oscillated around zero rather than
 *                                       converging
 *
 * The axis itself was found reliably in the end (coherence 0.70-0.91 once the
 * mount was wedged). Only its direction never resolved, and one unresolved bit
 * was enough to make the whole thing worthless. It was removed in v0.21.0 along
 * with the camber feature it fed, which had never fired.
 *
 * The lesson is worth keeping: the radius audit stopped needing any of it in
 * v0.17, when [SlipEstimator.drivenRadiusM] gave it a driven radius from GPS
 * course rate — a number that needs no alignment, no calibration and no
 * particular mounting angle. When a measurement keeps failing, the question
 * worth asking is whether something else already answers the question it was
 * asked to answer.
 */
class MountWobble {

    private var gravityEma = Vec3(0.0, 0.0, 0.0)
    private var haveBaseline = false
    private var wobbleEmaDeg = 0.0

    /** Degrees of movement in the mount. Rigid is 2-5. */
    val wobbleDeg: Double get() = wobbleEmaDeg

    /** Above this the phone is moving in its holder, not the car moving. */
    val isStable: Boolean get() = wobbleEmaDeg < STABLE_DEG

    /**
     * Feed every IMU sample. [gravity] is the PHYSICAL gravity vector in phone
     * frame — it points DOWN (flat phone on a table: (0, 0, −9.81)). Android's
     * TYPE_GRAVITY reports the opposite sign; the app negates it before calling.
     */
    fun tick(gravity: Vec3) {
        if (!gravity.isFinite() || gravity.norm() < 1e-6) return
        // The baseline moves over tens of seconds, so a lean through one corner
        // barely shifts it — which is what makes the difference readable.
        gravityEma = if (!haveBaseline) gravity.also { haveBaseline = true }
        else gravityEma + (gravity - gravityEma) * BASELINE_ALPHA
        val cosA = gravity.unit().dot(gravityEma.unit()).coerceIn(-1.0, 1.0)
        val angDeg = Math.toDegrees(kotlin.math.acos(cosA))
        wobbleEmaDeg += (angDeg - wobbleEmaDeg) * WOBBLE_ALPHA
    }

    companion object {
        const val STABLE_DEG = 8.0
        private const val BASELINE_ALPHA = 0.002
        private const val WOBBLE_ALPHA = 0.01
    }
}
