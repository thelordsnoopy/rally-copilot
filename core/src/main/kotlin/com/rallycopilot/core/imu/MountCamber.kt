package com.rallycopilot.core.imu

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.sqrt

/** Minimal 3-vector for phone-frame sensor maths. */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Double) = Vec3(x * k, y * k, z * k)
    fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun norm() = sqrt(x * x + y * y + z * z)
    fun unit(): Vec3 { val n = norm(); return if (n < 1e-9) this else Vec3(x / n, y / n, z / n) }
    fun isFinite() = x.isFinite() && y.isFinite() && z.isFinite()
}

/**
 * Silent mount self-alignment: finds the CAR-FORWARD direction in phone coordinates.
 *
 * The phone knows "down" (gravity) but not "forward" — the mount could sit at any
 * angle. The trick: when the car firmly accelerates or brakes, the accelerometer
 * shows where that swing points in phone-space. Accumulate those events and the
 * forward AXIS falls out — no calibration screen, nothing for the driver to do.
 *
 * THE AXIS AND ITS SIGN ARE LEARNED SEPARATELY, and that split is the whole design.
 * The original version flipped each event by the sign of dv/dt so accelerating and
 * braking events could share one signed sum. Replaying drive 42 showed why that
 * never aligned: the per-sample dv/dt sign — derived from OBD/GPS speed that lags
 * the accelerometer by most of a second — agreed with the event's true direction
 * only 54% of the time. A coin flip. The event DIRECTIONS lay on one beautiful
 * line (median 0.91 projection onto the principal axis); the signed sum of
 * coin-flipped vectors was rubble (coherence 0.17 against the 0.75 gate).
 *
 * So now:
 *  - THE AXIS ignores sign entirely: each event is folded into the hemisphere of
 *    the running sum before adding. Same trace, same events: coherence 0.76.
 *  - THE SIGN comes from physics that lag cannot flip: over a whole 2 s window,
 *    the speed change and the mean accelerometer projection along the axis must
 *    agree in sign. Each window with clear signal casts one vote; the majority
 *    fixes which end of the axis is forward (79% per-window agreement on the
 *    same trace, and votes accumulate).
 *
 * Alignment is trusted only while folded events agree (coherence) AND the
 * polarity vote is decisive; a knocked or re-clamped phone degrades coherence and
 * drops alignment until fresh events rebuild it.
 */
class MountAlignment(
    private val params: Params = Params(),
) {
    data class Params(
        /** Longitudinal events must be at least this strong, m/s2. */
        val minEventDvDt: Double = 1.5,
        /**
         * ...and no stronger than this. A road car does not accelerate or brake at
         * 1.5 g; a reading that says it did is a speed-source artefact, not a car.
         */
        val maxEventDvDt: Double = 8.0,
        /** ...and show at least this much horizontal accel in phone space. */
        val minHorizAccel: Double = 1.0,
        val minEvents: Int = 30,
        /**
         * Mean-resultant-length of the hemisphere-FOLDED events: 1.0 = a perfect
         * line. Calibrated on drive 42: real events measured 0.76; synthetic
         * garbage (uniform directions 0.64, two mixed axes 0.69) stays below.
         */
        val minCoherence: Double = 0.72,
        /** Folded coherence this low with real evidence means the axis is simply
         *  wrong (re-clamped mount): start again rather than fight history. */
        val resetCoherence: Double = 0.40,
        val maxEvents: Int = 400,
        /** Polarity vote window: speed change vs mean accel projection. */
        val polarityWindowMs: Long = 2_000,
        /** A window votes only when the speed clearly changed, m/s... */
        val polarityMinDvMps: Double = 1.5,
        /** ...and the mean projection clearly pointed somewhere, m/s². */
        val polarityMinProjMps2: Double = 0.4,
        /** Net votes needed before the sign is trusted. */
        val minPolarityVotes: Int = 5,
        /** Votes are clamped so an old majority can still be overturned. */
        val maxPolarityVotes: Int = 40,
    )

    private var sum = Vec3(0.0, 0.0, 0.0)
    private var n = 0
    /** Net polarity vote: positive = car-forward is along [sum], negative = against. */
    private var polarity = 0
    // Current polarity-vote window.
    private var windowStartT = 0L
    private var windowStartSpeed = 0.0
    private var windowProjSum = 0.0
    private var windowProjN = 0
    /** Long-EMA of the gravity direction: the car BODY's down axis in phone frame.
     *  Camber is a lean relative to this baseline — measuring against instantaneous
     *  gravity would read zero by construction. */
    private var gravityEma = Vec3(0.0, 0.0, 0.0)
    private var haveGravityEma = false

    val eventCount: Int get() = n
    val coherence: Double get() = if (n == 0) 0.0 else sum.norm() / n
    /** Net polarity votes, signed. Exposed for telemetry. */
    val polarityVotes: Int get() = polarity
    val isAligned: Boolean get() = n >= params.minEvents && coherence >= params.minCoherence &&
        kotlin.math.abs(polarity) >= params.minPolarityVotes

    /**
     * How much the phone is MOVING IN ITS MOUNT, degrees: a fast EMA of the angle
     * between instantaneous gravity and the slow body-down baseline.
     *
     * A rigid mount reads 2-5° (road pitch and camber). Chad's first traced drive
     * read 8-13° sustained with excursions past 34° — "a bit of play, an inch or
     * so" in the holder, which on a six-inch phone is tens of degrees of rotation.
     * No algorithm can align a frame that will not hold still, and coherence 0.087
     * over 344 events was that fact, not a software bug. This number exists so the
     * app can SAY "tighten the mount" instead of failing silently.
     */
    val wobbleDeg: Double get() = wobbleEmaDeg

    private var wobbleEmaDeg = 0.0

    /** Wobbling this hard, events are motion of the phone, not of the car. */
    val isStable: Boolean get() = wobbleEmaDeg < 8.0

    /** Car-forward unit vector in phone frame, or null until aligned. */
    val forward: Vec3? get() = if (isAligned) sum.unit() * (if (polarity >= 0) 1.0 else -1.0) else null

    /**
     * Feed every IMU sample. [linearAccel] and [gravity] in phone frame,
     * [dvdtMps2] = signed speed derivative from GPS/OBD, [speedMps] the fused
     * speed itself (polarity votes compare its change across a window), [tMs]
     * wall time for the vote windows.
     *
     * CONVENTION: [gravity] is the PHYSICAL gravity vector — it points DOWN
     * (flat phone on a table: (0, 0, −9.81)). Android's TYPE_GRAVITY reports the
     * opposite sign; the app layer negates it before calling here.
     */
    fun tick(tMs: Long, linearAccel: Vec3, gravity: Vec3, dvdtMps2: Double, speedMps: Double) {
        // One NaN would poison the EMAs forever — reject non-finite samples outright.
        if (!linearAccel.isFinite() || !gravity.isFinite() ||
            !dvdtMps2.isFinite() || !speedMps.isFinite()) return
        // Body-down baseline updates on EVERY sample (slow EMA ~ tens of seconds),
        // so brief leans through corners barely move it.
        gravityEma = if (!haveGravityEma) gravity.also { haveGravityEma = true }
        else gravityEma + (gravity - gravityEma) * 0.002
        // Track mount wobble: instantaneous gravity vs the slow baseline, degrees.
        val cosA = (gravity.unit().dot(gravityEma.unit())).coerceIn(-1.0, 1.0)
        val angDeg = Math.toDegrees(kotlin.math.acos(cosA))
        wobbleEmaDeg += (angDeg - wobbleEmaDeg) * 0.01

        val up = gravity.unit() * -1.0 // gravity points down; up is the negative
        val horiz = linearAccel - up * linearAccel.dot(up)

        // ---- Polarity vote window: every sample contributes its raw projection ----
        if (n > 0) {
            if (windowStartT == 0L) {
                windowStartT = tMs; windowStartSpeed = speedMps
                windowProjSum = 0.0; windowProjN = 0
            }
            windowProjSum += horiz.dot(sum.unit())
            windowProjN++
            if (tMs - windowStartT >= params.polarityWindowMs) {
                val dv = speedMps - windowStartSpeed
                val meanProj = if (windowProjN > 0) windowProjSum / windowProjN else 0.0
                if (abs(dv) >= params.polarityMinDvMps &&
                    abs(meanProj) >= params.polarityMinProjMps2
                ) {
                    val vote = if ((dv > 0) == (meanProj > 0)) 1 else -1
                    polarity = (polarity + vote)
                        .coerceIn(-params.maxPolarityVotes, params.maxPolarityVotes)
                }
                windowStartT = tMs; windowStartSpeed = speedMps
                windowProjSum = 0.0; windowProjN = 0
            }
        }

        // ---- Axis accumulation: sign-agnostic, hemisphere-folded ----
        if (abs(dvdtMps2) < params.minEventDvDt) return
        if (abs(dvdtMps2) > params.maxEventDvDt) return
        // A wobbling phone contributes phone motion, not car motion. Do not learn
        // from it — and do not slowly poison the accumulated direction either.
        if (!isStable) return
        if (horiz.norm() < params.minHorizAccel) return
        var dir = horiz.unit()
        // Fold into the hemisphere of the running sum: braking and accelerating
        // both sharpen the LINE, and no lagging speed sign can scatter it.
        if (n > 0 && dir.dot(sum) < 0) dir *= -1.0
        sum += dir
        n++
        // Forget slowly so a re-clamped mount re-learns rather than fighting history.
        if (n > params.maxEvents) { sum *= 0.5; n /= 2 }
        // Coherence this low with real evidence is not noise, it is a contradicted
        // axis — the mount moved. Start over.
        if (n >= params.minEvents && coherence < params.resetCoherence) {
            sum = Vec3(0.0, 0.0, 0.0); n = 0; polarity = 0; windowStartT = 0L
        }
    }

    /** Car-left unit vector in phone frame (bodyUp x forward), or null until aligned. */
    fun left(): Vec3? {
        val f = forward ?: return null
        if (!haveGravityEma) return null
        val bodyUp = gravityEma.unit() * -1.0
        return bodyUp.cross(f).unit()
    }
}

/**
 * Camber (sideways road lean) once the mount is aligned.
 *
 * Convention: POSITIVE camber = road leans to the car's LEFT (left side lower),
 * which helps a LEFT-hand corner and works against a RIGHT-hand one.
 *
 * Sampled only when the lateral acceleration is small — mid-corner the reading is
 * camber + body roll and the two can't be separated cheaply, so we simply don't
 * sample there. Entry/exit and straights give clean readings, and camber belongs
 * to the road, not the moment.
 */
class CamberEstimator(
    private val alignment: MountAlignment,
    private val params: Params = Params(),
) {
    data class Params(
        /** Only sample when |lateral accel| is below this, m/s2 (body roll negligible). */
        val maxLatAccel: Double = 1.2,
        val minSpeedMps: Double = 5.0,
        val emaAlpha: Double = 0.25,
    )

    private var ema = 0.0
    private var have = false

    /**
     * Feed every IMU sample; returns the current smoothed camber estimate in degrees
     * when a clean sample was possible, else null.
     */
    fun tick(linearAccel: Vec3, gravity: Vec3, speedMps: Double): Double? {
        if (!linearAccel.isFinite() || !gravity.isFinite() || !speedMps.isFinite()) return null
        if (speedMps < params.minSpeedMps) return null
        val leftAxis = alignment.left() ?: return null
        // Body accelerating sideways? Reading would be polluted — skip.
        if (abs(linearAccel.dot(leftAxis)) > params.maxLatAccel) return null
        val g = gravity.norm()
        if (g < 1e-3) return null
        // Gravity leaning toward car-left means the road banks left: positive camber.
        val lean = (gravity.dot(leftAxis) / g).coerceIn(-1.0, 1.0)
        val deg = Math.toDegrees(asin(lean))
        ema = if (have) ema + params.emaAlpha * (deg - ema) else deg.also { have = true }
        return ema
    }
}
