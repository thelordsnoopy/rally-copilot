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
    )

    data class Sample(
        val tMs: Long,
        val speedMps: Double,
        val rpm: Int?,
        val pedal01: Double?,
        val gear: Int?,
        val aLatMps2: Double?,   // v^2/R when in a known corner, else null
    )

    private val window = ArrayDeque<Sample>()
    private val shiftRpms = ArrayDeque<Double>()   // rpm observed just before each upshift
    private var lastGear: Int? = null
    private var lastRpm: Int? = null
    private var spirited = false
    private var spiritedMs = 0L
    private var totalMs = 0L
    private var lastT = 0L

    /** Whether the current window reads as spirited driving. */
    val isSpirited: Boolean get() = spirited

    /** 0..1 blended score for the current window (for the HUD/debug). */
    var score: Double = 0.0
        private set

    /** Fraction of the drive so far spent spirited. */
    val spiritedFraction: Double get() = if (totalMs == 0L) 0.0 else spiritedMs.toDouble() / totalMs

    fun tick(sample: Sample, profile: DriverProfile) {
        // Track upshift rpm: gear increased -> remember the rpm we shifted at.
        val g = sample.gear
        val r = sample.rpm
        if (g != null && lastGear != null && g == lastGear!! + 1 && lastRpm != null) {
            shiftRpms += lastRpm!!.toDouble()
            while (shiftRpms.size > 12) shiftRpms.removeFirst()
        }
        if (g != null) lastGear = g
        if (r != null) lastRpm = r

        window += sample
        while (window.isNotEmpty() && sample.tMs - window.first().tMs > params.windowMs) {
            window.removeFirst()
        }
        if (window.size < 5) return

        score = blend(profile)
        val was = spirited
        spirited = if (spirited) score > params.exitSpirited else score > params.enterSpirited

        if (lastT != 0L) {
            val dt = sample.tMs - lastT
            if (dt in 1..5000) {
                totalMs += dt
                if (was || spirited) spiritedMs += dt
            }
        }
        lastT = sample.tMs
    }

    private fun blend(profile: DriverProfile): Double {
        val votes = ArrayList<Pair<Double, Double>>(4) // (vote, weight)

        // Longitudinal acceleration from speed deltas — always available.
        val accels = window.windowed(2).mapNotNull { (a, b) ->
            val dt = (b.tMs - a.tMs) / 1000.0
            if (dt in 0.05..3.0) kotlin.math.abs(b.speedMps - a.speedMps) / dt else null
        }
        if (accels.isNotEmpty()) {
            votes += rate(p90(accels), params.accelNormal, params.accelSpirited) to 1.0
        }

        // Lateral-g utilisation vs the learned profile — the most direct signal.
        val lats = window.mapNotNull { it.aLatMps2 }
        if (lats.isNotEmpty()) {
            val target = profile.aLatFor(com.rallycopilot.core.model.SeverityBand.THREE)
            if (target > 0) votes += rate(p90(lats) / target, params.latUseNormal, params.latUseSpirited) to 1.4
        }

        // Pedal commitment (OBD).
        val pedals = window.mapNotNull { it.pedal01 }
        if (pedals.size >= 5) {
            votes += rate(p90(pedals), params.pedalNormal, params.pedalSpirited) to 1.2
        }

        // Rpm headroom used (OBD).
        val rpms = window.mapNotNull { it.rpm?.toDouble() }
        if (rpms.size >= 5) {
            val used = (p90(rpms) - params.idleRpm) / (params.redlineRpm - params.idleRpm)
            votes += rate(used, 0.30, 0.65) to 1.0
        }

        // Where do you shift? Persistent across the drive, very telling on a manual.
        if (shiftRpms.size >= 2) {
            val med = shiftRpms.sorted()[shiftRpms.size / 2]
            votes += rate(med, params.shiftRpmNormal, params.shiftRpmSpirited) to 1.3
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
