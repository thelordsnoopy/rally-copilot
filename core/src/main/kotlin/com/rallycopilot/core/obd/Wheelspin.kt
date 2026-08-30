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
        /**
         * Below this, no verdict — m/s. Not because the arithmetic breaks, but
         * because of the clutch: pulling away in first, the disc is still slipping
         * and rpm/speed legitimately reads far over the gear ratio. Drives 52-55
         * showed ratios of 508, 512 and 482 above 2.5 m/s against a real first
         * gear near 440, and every one of them was a normal pull-away. Above
         * 4 m/s the clutch is home and the ratio tells the truth.
         */
        val minSpeedMps: Double = 4.0,
        /**
         * Gears that must be fitted before the tallest ratio may be called "first".
         * This is the one that matters. [GearInference] clusters rpm/speed samples,
         * and on a short town drive it finds three or four of a six-speed box — so
         * `max(ratios)` is second or third gear, and every legitimate pull-away in
         * first reads as impossible. Drive 52 believed first gear was 233 when the
         * car was really turning 434, and produced 87 samples of phantom wheelspin
         * in the first minute because of it.
         */
        val minGearsLearned: Int = 5,
        /**
         * First gear stands well clear of second — typically 1.5-1.8x on a manual.
         * A tallest ratio sitting right next to its neighbour is not the bottom of
         * the box, it is the bottom of what has been SEEN so far.
         */
        val firstGearStep: Double = 1.20,
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
        /** No usable evidence: too slow, no rpm, or the gearbox is not yet
         *  learned well enough for "faster than first gear" to mean anything. */
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
        val sorted = ratios.sortedDescending()
        val first = sorted.firstOrNull() ?: 0.0
        val second = sorted.getOrNull(1) ?: 0.0
        // Is the tallest ratio actually FIRST GEAR, or just the lowest gear this
        // drive happened to use? Without that distinction the whole test inverts:
        // an honest pull-away becomes "a ratio no gear can explain".
        val firstIsTrustworthy = ratios.size >= params.minGearsLearned &&
            second > 0.0 && first / second >= params.firstGearStep
        if (rpm == null || rpm < params.minRpm || speedMps < params.minSpeedMps ||
            !firstIsTrustworthy
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
