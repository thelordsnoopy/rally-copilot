package com.rallycopilot.core.imu

import kotlin.math.abs

/**
 * Is the car going where it is pointing?
 *
 * Two independent measurements of "how much did this car turn": the gyroscope's
 * yaw (body rotation about the vertical) and successive GNSS bearings (rotation
 * of the velocity vector — the direction actually travelled). On a gripping car
 * they are the same angle. When they diverge, the car is sliding; the difference
 * is the sideslip angle, which is the textbook definition rather than anything
 * invented here.
 *
 * WHY THIS MATTERS TO THE APP. Learned pace comes from `aLat = v² / R`, where R is
 * the MAP's radius — an assumption that the car followed the road's curve. A car
 * with a welded diff on worn rear tyres may be doing nothing of the sort, and a
 * corner "taken at 0.9 g" while sliding is not evidence of grip.
 *
 * TWO DETECTORS, LEARNED THE HARD WAY ACROSS DRIVES 42-47:
 *
 * 1. THE IMPOSSIBLE RADIUS (gyro only, instant). speed / |yawRate| is the radius
 *    of the circle the body's rotation implies. Below the tightest circle the car
 *    can PHYSICALLY describe — full lock — the tyres are not following the
 *    steering. This is the clause that sees a donut: drive 44's was 81°/s at
 *    7 mph, an implied radius of 2.4 m against a ~5.5 m turning circle, and the
 *    old estimator sat on UNKNOWN throughout because 7 mph was under its speed
 *    gate. It needs no GPS at all, which is not a convenience but a requirement:
 *    a 1 Hz bearing ALIASES on an 80°/s spin (the course rate read +0.083 rad/s,
 *    the wrong direction, for the donut's whole duration). Replayed over two dry
 *    drives it fires zero times — dry tarmac produces no impossible radii.
 *
 * 2. THE ANGLE COMPARISON (per GNSS fix window). The old design compared RATES —
 *    a smoothed gyro rate against a trailing course rate — and had to guess how
 *    far the course rate lagged (`yawDelayMs = 700`). Cross-correlation on a real
 *    drive measured the true lag at ~1,300 ms, so every corner entry read as
 *    phantom oversteer and every exit as phantom understeer: 5 flagged moments in
 *    drive 44, none of them the actual slides. This design deletes the guessed
 *    constant: integrate gyro yaw BETWEEN two fixes and compare that angle with
 *    the bearing change over the SAME window. Two angles over one interval — no
 *    lag to tune. Noise floor measured on real traces: |Δβ| p50 0.5°, p90 2-3°
 *    per window; real slides read 6-13°.
 *
 * WHY IT WORKS WHEN THE MOUNT DOES NOT: yaw is the gyro's component along the
 * vertical, known from gravity alone. A phone swivelling in its cradle rotates
 * about that same axis, so no mount alignment is needed.
 *
 * Pure maths, no Android: the app feeds gyro ticks and GNSS fixes.
 */
class SlipEstimator(private val params: Params = Params()) {

    data class Params(
        /**
         * Tightest circle the car can describe with the wheels on full lock, m.
         * An E90 3-series turns in ~10.9 m kerb to kerb (5.5 m radius); 5.0 leaves
         * margin for gyro noise. A body rotation implying less than this is a
         * slide by construction.
         */
        val minTurnRadiusM: Double = 5.0,
        /** The impossible-radius clause needs SOME speed — a phone rotated while
         *  the car is parked implies radius zero. m/s. */
        val spinMinSpeedMps: Double = 2.5,
        /** ...and a real rotation, not gyro noise, rad/s (~9°/s). */
        val spinMinYawRadS: Double = 0.15,
        /** Impossible radius must persist this long, ms. Half of a wheel-jerk. */
        val spinSustainMs: Long = 300,
        /**
         * The angle comparison needs a usable bearing. Below ~10 mph a GNSS
         * bearing is rubble: drive 47 logged a ±36° equal-and-opposite pair at
         * 9 mph that was a bearing glitch, not motion. The impossible-radius
         * clause owns the low-speed regime. m/s.
         */
        val windowMinSpeedMps: Double = 4.5,
        /** Sideslip change over one fix window that counts as a slide, degrees.
         *  Real slides measured 6.6-13.2°; two dry drives' worst noise was 10.6°
         *  under a wobbling mount — this sits above the p99 (~7-9°) and accepts
         *  missing a mild scrub over poisoning the learning loop. */
        val windowSlipDeg: Double = 9.0,
        /** A usable fix window, seconds. */
        val windowMinDtS: Double = 0.5,
        val windowMaxDtS: Double = 2.0,
        /** How long a window verdict keeps [State.sliding] true, ms. The window
         *  is trailing (it describes the last GNSS second), so the hold covers
         *  the present without inventing a longer slide than was measured. */
        val windowHoldMs: Long = 1_200,
        /** Trailing course-rate floor for [drivenRadiusM], rad/s (~5°/s). */
        val minTurnRateRadS: Double = 0.09,
        /** Below this, no driven radius: the ratio of noise to noise. m/s. */
        val minSpeedMps: Double = 6.0,
    )

    enum class Verdict {
        /** Not enough signal to say anything. */
        UNKNOWN,
        /** Body rotation and path agree: the car is gripping. */
        NEUTRAL,
        /** Turning less than the path demands — pushing wide. */
        UNDERSTEER,
        /** Rotating more than the path is turning. */
        OVERSTEER,
    }

    data class State(
        /** Smoothed yaw rate, for telemetry, rad/s. */
        val yawRateRadS: Double,
        /** Trailing course rate from the last fix pair, rad/s. */
        val courseRateRadS: Double,
        /** Sideslip change over the last closed window, degrees. NaN before one. */
        val dbetaDeg: Double,
        /** Radius the body rotation implies right now, m. Null when not turning. */
        val impliedRadiusM: Double?,
        val verdict: Verdict,
        /** True while a slide is in progress (impossible radius live, or within
         *  [Params.windowHoldMs] of a window that measured one). */
        val sliding: Boolean,
    )

    var state = State(0.0, 0.0, Double.NaN, null, Verdict.UNKNOWN, false)
        private set

    /** True at any point during the last corner. Read and cleared by the collector. */
    var slidSinceReset = false
        private set

    fun resetSlide() { slidSinceReset = false }

    // -------- gyro-side state --------
    private var yawEma = 0.0
    private var haveYaw = false
    private var lastYawT = 0L
    /** Yaw angle integrated since the last fix, radians. */
    private var yawIntegralRad = 0.0
    private var integralSamples = 0
    /** When the implied radius first went impossible, 0 = it is not. */
    private var spinSince = 0L
    private var spinActive = false

    // -------- fix-side state --------
    private var lastFixT = 0L
    private var lastBearingDeg = Double.NaN
    private var courseRate = 0.0
    private var windowVerdict = Verdict.UNKNOWN
    private var windowVerdictUntil = 0L
    private var lastDbetaDeg = Double.NaN

    /**
     * Feed every gyro sample (~10 Hz). [yawRateRadS] signed body rotation about
     * the vertical, positive anticlockwise seen from above. [speedMps] the fused
     * speed. Returns the current state.
     */
    fun onYaw(tMs: Long, yawRateRadS: Double, speedMps: Double): State {
        if (!yawRateRadS.isFinite() || !speedMps.isFinite()) return state
        if (!haveYaw) { yawEma = yawRateRadS; haveYaw = true } else {
            yawEma += (yawRateRadS - yawEma) * 0.25
        }
        // Integrate the RAW rate — smoothing an integrand only defers the angle.
        if (lastYawT != 0L) {
            val dt = (tMs - lastYawT) / 1000.0
            if (dt in 0.0..0.5) { yawIntegralRad += yawRateRadS * dt; integralSamples++ }
        }
        lastYawT = tMs

        // THE IMPOSSIBLE RADIUS. No GPS, no alignment, no window: if the body is
        // rotating faster than the front wheels could ever make it, it is sliding.
        val y = abs(yawRateRadS)
        val implied = if (y > 1e-6) speedMps / y else null
        val impossible = speedMps >= params.spinMinSpeedMps &&
            y >= params.spinMinYawRadS &&
            implied != null && implied < params.minTurnRadiusM
        if (impossible) {
            if (spinSince == 0L) spinSince = tMs
            if (tMs - spinSince >= params.spinSustainMs) spinActive = true
        } else {
            spinSince = 0L
            spinActive = false
        }
        if (spinActive) slidSinceReset = true

        publish(tMs, implied)
        return state
    }

    /**
     * Feed every GNSS fix. [bearingDeg] compass bearing of travel (NaN when the
     * fix has none), [speedMps] the fix's speed. Closes the integration window:
     * the yaw angle the gyro accumulated since the previous fix is compared with
     * the bearing change over exactly that interval.
     */
    fun onFix(tMs: Long, bearingDeg: Double, speedMps: Double): State {
        val dtS = if (lastFixT == 0L) Double.NaN else (tMs - lastFixT) / 1000.0
        val prevBearing = lastBearingDeg
        val gyroAngle = yawIntegralRad
        val samples = integralSamples
        // The window restarts here whatever we decide about the one just closed.
        lastFixT = tMs
        lastBearingDeg = bearingDeg
        yawIntegralRad = 0.0
        integralSamples = 0

        if (dtS.isNaN() || dtS !in params.windowMinDtS..params.windowMaxDtS ||
            bearingDeg.isNaN() || prevBearing.isNaN() || samples < 3
        ) {
            courseRate = 0.0
            publish(tMs, null)
            return state
        }

        var d = bearingDeg - prevBearing
        while (d > 180) d -= 360
        while (d < -180) d += 360
        // Bearing grows clockwise; yaw is positive anticlockwise.
        val courseAngle = -Math.toRadians(d)
        courseRate = courseAngle / dtS

        if (speedMps >= params.windowMinSpeedMps) {
            val dbeta = Math.toDegrees(gyroAngle - courseAngle)
            lastDbetaDeg = dbeta
            if (abs(dbeta) >= params.windowSlipDeg) {
                // The body turned more than the path (same sign as the turn, or a
                // rotation the path never showed): oversteer. Turned less: understeer.
                val turnRef = if (abs(courseAngle) > 1e-3) courseAngle else gyroAngle
                windowVerdict = if (dbeta * turnRef >= 0) Verdict.OVERSTEER else Verdict.UNDERSTEER
                windowVerdictUntil = tMs + params.windowHoldMs
                slidSinceReset = true
            } else {
                // A measured, quiet window is positive evidence of grip.
                windowVerdict = Verdict.NEUTRAL
                windowVerdictUntil = tMs + params.windowHoldMs
            }
        }
        publish(tMs, null)
        return state
    }

    private fun publish(tMs: Long, impliedNow: Double?) {
        val windowLive = tMs < windowVerdictUntil
        val verdict = when {
            spinActive -> Verdict.OVERSTEER
            windowLive -> windowVerdict
            else -> Verdict.UNKNOWN
        }
        val sliding = spinActive ||
            (windowLive && (windowVerdict == Verdict.OVERSTEER || windowVerdict == Verdict.UNDERSTEER))
        state = State(yawEma, courseRate, lastDbetaDeg, impliedNow, verdict, sliding)
    }

    /**
     * The radius the car ACTUALLY drove, metres, from speed and the trailing
     * course rate — the path through the corner rather than the road's
     * centreline. Null when not turning enough to measure.
     */
    fun drivenRadiusM(speedMps: Double): Double? {
        val c = abs(courseRate)
        if (c < params.minTurnRateRadS || speedMps < params.minSpeedMps) return null
        return speedMps / c
    }
}
