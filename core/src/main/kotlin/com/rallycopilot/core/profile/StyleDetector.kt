package com.rallycopilot.core.profile

import com.rallycopilot.core.model.DriverProfile

/**
 * Detects whether the driver is actually pressing on, so that normal driving with the
 * app open never pollutes calibration. This is the difference between "the model knows
 * my limits" and "the model thinks my limit is the Tesco run".
 *
 * Works on a rolling window of samples. Uses everything available and degrades
 * gracefully: with OBD it reads rpm headroom, shift points and pedal; GPS-only it
 * falls back to longitudinal/lateral acceleration. Each signal votes 0..1; the
 * blended score crosses in/out of SPIRITED with hysteresis so it doesn't flicker.
 *
 * Two profile-level gates sit ON TOP of the instantaneous score, because a brief
 * pull LOOKS spirited on every instantaneous signal without meaning anything:
 *  - PACE: once enough of the drive has been seen to trust it, your rolling window
 *    is compared against your own drive-long moving average — only genuinely
 *    quicker-than-your-normal pace votes spirited (heavily weighted).
 *  - SUSTAIN: the verdict flips only after the commitment holds UNBROKEN for
 *    [Params.sustainMs]. Stopping, crawling, or braking hard nowhere near a known
 *    corner breaks the run and the clock restarts. One overtake logs nothing.
 *
 * Tuned around a diesel road car (BMW 320d E90: idle ~800, redline ~4800, normal
 * upshifts under ~2200 rpm, spirited ones past ~3000) but every threshold is a
 * parameter, not a constant baked into logic.
 */
class StyleDetector(
    private val params: Params = Params(),
) {
    data class Params(
        val windowMs: Long = 20_000,
        val idleRpm: Double = 800.0,
        val redlineRpm: Double = 4800.0,
        /** Upshift rpm below this reads as relaxed; above [shiftRpmSpirited] as pressing on. */
        val shiftRpmNormal: Double = 2200.0,
        val shiftRpmSpirited: Double = 3200.0,
        /** p90 pedal position considered relaxed / committed. */
        val pedalNormal: Double = 0.35,
        val pedalSpirited: Double = 0.75,
        /** p90 |longitudinal accel| m/s2 relaxed / committed (hard braking counts). */
        val accelNormal: Double = 1.2,
        val accelSpirited: Double = 3.0,
        /** p90 lateral g as a fraction of the learned profile's THREE-band target. */
        val latUseNormal: Double = 0.45,
        val latUseSpirited: Double = 0.85,
        /** Score thresholds with hysteresis: enter spirited above, leave below. */
        val enterSpirited: Double = 0.55,
        val exitSpirited: Double = 0.40,
        /** Window pace vs your drive-long moving average: 1.05x reads normal, 1.30x committed. */
        val paceRatioNormal: Double = 1.05,
        val paceRatioSpirited: Double = 1.30,
        /** ---- fast gate (what the co-driver SPEAKS on) ---- */
        /** Short window the fast gate judges on — responds within a corner or two. */
        val fastWindowMs: Long = 6_000,
        /** Fast gate turns on above this score and off below it. Deliberately eager:
         *  being late to start calling is worse than a few notes on a brisk B-road. */
        val fastOn: Double = 0.42,
        val fastOff: Double = 0.28,
        /** Keep talking this long after the pace drops, so a single slow corner or a
         *  village on a good road does not chop the co-driver off mid-flow. */
        val fastHangoverMs: Long = 45_000,
        /** The pace vote joins only after this much MOVING time — the average needs to mean something. */
        val paceWarmupMs: Long = 120_000,
        /** Commitment must hold unbroken this long before the verdict flips to spirited. */
        val sustainMs: Long = 20_000,
        /** Hard braking further than this from any known corner is not "necessary" braking. */
        val necessaryBrakeNearCornerM: Double = 70.0,
        /** Decel treated as a deliberate brake, m/s². */
        val brakeDecelMps2: Double = 2.5,
        /** Stopping or crawling always breaks the run. */
        val breakSpeedMps: Double = 2.0,
    )

    data class Sample(
        val tMs: Long,
        val speedMps: Double,
        val rpm: Int?,
        val pedal01: Double?,
        val gear: Int?,
        val aLatMps2: Double?,   // v^2/R when in a known corner, else null
        /** Distance to the nearest known corner (|ahead|), for judging whether a
         *  brake was necessary. Null = no horizon data — braking is then not judged. */
        val nearestCornerM: Double? = null,
    )

    private val window = ArrayDeque<Sample>()
    private val shiftRpms = ArrayDeque<Double>()   // rpm observed just before each upshift
    private var lastGear: Int? = null
    private var lastRpm: Int? = null
    /** Rpm at the last sample where a gear was actually engaged. Gear inference goes
     *  null through the clutch gap while revs fall — capturing rpm when the NEW gear
     *  registers would record the post-shift value, ~25-30% low, and chronically
     *  suppress the spirited verdict that gates all learning. */
    private var lastEngagedRpm: Int? = null
    private var spirited = false
    private var spiritedMs = 0L
    private var totalMs = 0L
    private var lastT = 0L
    /** Drive-long moving-speed average (slow, ~5-minute time constant) — "my pace". */
    private var movingAvg = 0.0
    private var movingMs = 0L
    /** When the current UNBROKEN committed stretch began; -1 = not committed. */
    private var committedSinceMs = -1L
    private var pressingOn = false
    private var pressingLastTrueMs = Long.MIN_VALUE / 2

    /**
     * The STRICT verdict: sustained, unbroken, quicker-than-your-own-average driving.
     * This is the one that gates LEARNING — it must never be fooled by a brief pull,
     * because a polluted profile is permanent.
     */
    val isSpirited: Boolean get() = spirited

    /**
     * The FAST verdict: are you pressing on *right now*? Reacts within a corner or
     * two and hangs on through a village. This is what gates SPEAKING — the co-driver
     * should start calling the moment the road gets good, long before there is enough
     * evidence to safely train the model from it.
     */
    val isPressingOn: Boolean get() = pressingOn

    /** 0..1 blended score for the current window (for the HUD/debug). */
    var score: Double = 0.0
        private set

    /** 0..1 score over the short fast window. */
    var fastScore: Double = 0.0
        private set

    /** Fraction of the drive so far spent spirited. */
    val spiritedFraction: Double get() = if (totalMs == 0L) 0.0 else spiritedMs.toDouble() / totalMs

    fun tick(sample: Sample, profile: DriverProfile) {
        // Track upshift rpm: gear increased -> remember the rpm from BEFORE the shift
        // (the last sample with the old gear engaged), not the fallen post-shift revs.
        val g = sample.gear
        val r = sample.rpm
        if (g != null && lastGear != null && g == lastGear!! + 1) {
            (lastEngagedRpm ?: lastRpm)?.let {
                shiftRpms += it.toDouble()
                while (shiftRpms.size > 12) shiftRpms.removeFirst()
            }
        }
        if (g != null) lastGear = g
        if (r != null) lastRpm = r
        if (g != null && r != null) lastEngagedRpm = r

        val prev = window.lastOrNull()

        // Drive-long moving average: time-weighted slow EMA, moving samples only.
        if (sample.speedMps > 3.0 && prev != null) {
            val dtMs = (sample.tMs - prev.tMs).coerceIn(0, 5000)
            movingMs += dtMs
            val alpha = dtMs / 300_000.0
            movingAvg = if (movingAvg == 0.0) sample.speedMps
            else movingAvg + (sample.speedMps - movingAvg) * alpha
        }

        window += sample
        while (window.isNotEmpty() && sample.tMs - window.first().tMs > params.windowMs) {
            window.removeFirst()
        }
        if (window.size < 5) return

        score = blend(profile, window)
        val was = spirited

        // ---- unbroken-commitment gate ----
        // A run breaks on a stop/crawl, or on hard braking with no corner anywhere
        // near to explain it (traffic, junctions, hesitation — not pressing on).
        val dtS = prev?.let { (sample.tMs - it.tMs) / 1000.0 } ?: 0.0
        val decel = if (prev != null && dtS in 0.05..3.0) (prev.speedMps - sample.speedMps) / dtS else 0.0
        val unnecessaryBrake = decel > params.brakeDecelMps2 &&
            sample.nearestCornerM != null && sample.nearestCornerM > params.necessaryBrakeNearCornerM
        val broke = sample.speedMps < params.breakSpeedMps || unnecessaryBrake
        // The clock runs on a SHORT (5 s) recency score, not the full 20 s window:
        // the window's decay tail would otherwise let a 10-second pull read as half
        // a minute of commitment. Hysteresis: reset needs a real break or drop.
        val recentSamples = window.filter { it.tMs >= sample.tMs - 5_000 }
        val recent = if (recentSamples.size >= 5) blend(profile, recentSamples) else score
        val committed = recent > (if (committedSinceMs >= 0) params.exitSpirited else params.enterSpirited)
        if (broke || !committed) committedSinceMs = -1
        else if (committedSinceMs < 0) committedSinceMs = sample.tMs
        spirited = committedSinceMs >= 0 && sample.tMs - committedSinceMs >= params.sustainMs

        // ---- fast gate: what the co-driver actually speaks on ----
        // No sustain requirement and no pace-vs-average warm-up: this must come alive
        // as soon as the driving does. Crawling always silences it immediately.
        val fastSamples = window.filter { it.tMs >= sample.tMs - params.fastWindowMs }
        fastScore = if (fastSamples.size >= 4) blend(profile, fastSamples) else 0.0
        val crawling = sample.speedMps < params.breakSpeedMps
        val fastNow = !crawling && fastScore > (if (pressingOn) params.fastOff else params.fastOn)
        if (fastNow) pressingLastTrueMs = sample.tMs
        pressingOn = when {
            crawling && sample.tMs - pressingLastTrueMs > 10_000 -> false
            fastNow -> true
            // Hang on through a village or one slow corner rather than cutting out.
            else -> sample.tMs - pressingLastTrueMs < params.fastHangoverMs
        }

        if (lastT != 0L) {
            val dt = sample.tMs - lastT
            if (dt in 1..5000) {
                totalMs += dt
                if (was || spirited) spiritedMs += dt
            }
        }
        lastT = sample.tMs
    }

    private fun blend(profile: DriverProfile, samples: List<Sample>): Double {
        val votes = ArrayList<Pair<Double, Double>>(6) // (vote, weight)

        // Longitudinal acceleration from speed deltas — always available.
        val accels = samples.windowed(2).mapNotNull { (a, b) ->
            val dt = (b.tMs - a.tMs) / 1000.0
            if (dt in 0.05..3.0) kotlin.math.abs(b.speedMps - a.speedMps) / dt else null
        }
        if (accels.isNotEmpty()) {
            votes += rate(p90(accels), params.accelNormal, params.accelSpirited) to 1.0
        }

        // Lateral-g utilisation vs the learned profile — the most direct signal.
        val lats = samples.mapNotNull { it.aLatMps2 }
        if (lats.isNotEmpty()) {
            val target = profile.aLatFor(com.rallycopilot.core.model.SeverityBand.THREE)
            if (target > 0) votes += rate(p90(lats) / target, params.latUseNormal, params.latUseSpirited) to 1.4
        }

        // Pedal commitment (OBD).
        val pedals = samples.mapNotNull { it.pedal01 }
        if (pedals.size >= 5) {
            votes += rate(p90(pedals), params.pedalNormal, params.pedalSpirited) to 1.2
        }

        // Rpm headroom used (OBD).
        val rpms = samples.mapNotNull { it.rpm?.toDouble() }
        if (rpms.size >= 5) {
            val used = (p90(rpms) - params.idleRpm) / (params.redlineRpm - params.idleRpm)
            votes += rate(used, 0.30, 0.65) to 1.0
        }

        // Where do you shift? Persistent across the drive, very telling on a manual.
        if (shiftRpms.size >= 2) {
            val med = shiftRpms.sorted()[shiftRpms.size / 2]
            votes += rate(med, params.shiftRpmNormal, params.shiftRpmSpirited) to 1.3
        }

        // Pace vs YOUR OWN average — the profile signal, heaviest weight. A pull can
        // spike accel, revs, and pedal for ten seconds; only a genuinely held
        // quicker-than-your-normal pace moves this vote.
        if (movingMs >= params.paceWarmupMs && movingAvg > 3.0) {
            val moving = samples.filter { it.speedMps > 3.0 }
            if (moving.isNotEmpty()) {
                val mean = moving.sumOf { it.speedMps } / moving.size
                votes += rate(mean / movingAvg, params.paceRatioNormal, params.paceRatioSpirited) to 1.6
            }
        }

        val totalW = votes.sumOf { it.second }
        return if (totalW == 0.0) 0.0 else votes.sumOf { it.first * it.second } / totalW
    }

    /** Map a value onto 0..1 between a relaxed and a committed threshold. */
    private fun rate(v: Double, lo: Double, hi: Double): Double =
        ((v - lo) / (hi - lo)).coerceIn(0.0, 1.0)

    private fun p90(values: List<Double>): Double {
        val s = values.sorted()
        return s[((s.size - 1) * 0.9).toInt()]
    }
}
