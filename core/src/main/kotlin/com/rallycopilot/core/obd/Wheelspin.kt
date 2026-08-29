package com.rallycopilot.core.obd

/**
 * Are the driven wheels turning faster than the car is moving?
 *
 * The same argument as [com.rallycopilot.core.imu.SlipEstimator]'s impossible
 * radius, applied to the drivetrain instead of the chassis. Engine rpm divided by
 * road speed is the gear ratio, and in a given gear it is a constant — the app
 * already learns the set of them per car ([GearInference]). Every ratio the car
 * can physically be in is therefore known, and the highest is first gear.
 *
 * So a measured rpm/speed ABOVE first gear cannot be a gear. The engine is turning
 * faster than any gear would have it turn at that road speed, which means the
 * wheels are outrunning the ground: wheelspin.
 *
 * WHY IT EARNS ITS PLACE. The yaw-vs-course method is weakest exactly where a
 * welded diff is strongest — power-on, wheels spinning, car still pointing more or
 * less where it is going. The rotation may be small while the traction is gone.
 * This sees that case directly, from two numbers the app already has.
 *
 * WHAT IT CANNOT TELL APART. A clutch pedal. Dip the clutch and rev, and rpm rises
 * with road speed unchanged — arithmetically identical to wheelspin. The defences
 * are a sustain window (a heel-and-toe blip is brief) and a demand that the car is
 * not slowing down (a rev-match happens under braking). Neither is proof, which is
 * why [Verdict.SUSPECTED] is a suspicion and this class does not yet gate learning:
 * it earns its way in on the next traces, or the CAN probe finds real wheel speeds
 * and makes the whole question moot.
 */
class WheelspinDetector(private val params: Params = Params()) {

    data class Params(
        /**
         * How far above the tallest learned ratio counts. Clutch take-up, tyre
         * wear and the ratio fit itself are all worth a few percent; 15% is past
         * all of them and still well inside a real spin, where the ratio runs 30%
         * or more over.
         */
        val overRatio: Double = 1.15,
        /** Below this there is no meaningful road speed to divide by, m/s. */
        val minSpeedMps: Double = 2.5,
        /** Below this rpm the engine is not driving anything. */
        val minRpm: Int = 1200,
        /** Must persist this long: a downshift blip is shorter, ms. */
        val sustainMs: Long = 400,
        /** ...and the car must not be braking hard, m/s². A rev-match under
         *  braking is the other thing that looks exactly like this. */
        val maxDecelMps2: Double = 2.0,
        /** How long a detected spin keeps the flag up after it ends, ms. */
        val holdMs: Long = 1_500,
    )

    enum class Verdict {
        /** No usable evidence: too slow, no rpm, gearing not learned yet. */
        UNKNOWN,
        /** rpm and speed agree with a gear the car actually has. */
        HOOKED_UP,
        /** rpm implies a wheel speed no gear can explain. */
        SUSPECTED,
    }

    data class State(
        val verdict: Verdict,
        /** Measured rpm / speed. */
        val ratio: Double,
        /** The tallest ratio the car is known to have (first gear). */
        val firstGearRatio: Double,
        /** How much faster the wheels are turning than the ground, as a fraction:
         *  0.30 = the wheels are doing 30% more than the car. Null when unknown. */
        val excess: Double?,
        val spinning: Boolean,
    )

    var state = State(Verdict.UNKNOWN, 0.0, 0.0, null, false)
        private set

    /** True at any point since the last reset — read and cleared by the collector. */
    var spunSinceReset = false
        private set

    fun resetSpin() { spunSinceReset = false }

    private var overSince = 0L
    private var holdUntil = 0L

    /**
     * [rpm] engine speed, [speedMps] the best GROUND speed available — GPS for
     * preference, since a car's own speedometer is derived from the very wheels
     * in question. [accelMps2] signed longitudinal acceleration. [ratios] the
     * learned gear ratios (rpm per m/s), tallest first gear included.
     */
    fun tick(
        tMs: Long,
        rpm: Int?,
        speedMps: Double,
        accelMps2: Double,
        ratios: List<Double>,
    ): State {
        val first = ratios.maxOrNull() ?: 0.0
        if (rpm == null || rpm < params.minRpm || speedMps < params.minSpeedMps ||
            ratios.size < 3 || first <= 0.0
        ) {
            overSince = 0L
            state = State(Verdict.UNKNOWN, 0.0, first, null, tMs < holdUntil)
            return state
        }
        val ratio = rpm / speedMps
        val over = ratio > first * params.overRatio && accelMps2 > -params.maxDecelMps2
        if (over) {
            if (overSince == 0L) overSince = tMs
            if (tMs - overSince >= params.sustainMs) {
                holdUntil = tMs + params.holdMs
                spunSinceReset = true
            }
        } else {
            overSince = 0L
        }
        val spinning = tMs < holdUntil
        state = State(
            verdict = if (over) Verdict.SUSPECTED else Verdict.HOOKED_UP,
            ratio = ratio,
            firstGearRatio = first,
            excess = (ratio / first) - 1.0,
            spinning = spinning,
        )
        return state
    }
}
