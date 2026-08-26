package com.rallycopilot.core.obd

/**
 * Pure ELM327 / OBD-II protocol helpers: command strings, response parsing, gear
 * inference. The Android layer owns the Bluetooth socket; this owns the bytes.
 */
object Elm327 {

    // Init sequence for a cold ELM327.
    val INIT = listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0")

    object Pid {
        const val SPEED = "010D"
        const val RPM = "010C"
        const val THROTTLE = "0111"
        const val COOLANT = "0105"
        const val VOLTAGE = "ATRV"
    }

    /** Strip ELM chatter: echoes, prompts, whitespace. */
    fun clean(raw: String): String =
        raw.replace(">", "").replace("\r", " ").replace("\n", " ").trim()

    /**
     * Parse a mode-01 response. E.g. request 010D → response "41 0D 3C" (or "410D3C").
     * Returns the data bytes after the 41+pid header, or null on NO DATA / garbage.
     */
    fun dataBytes(pid: String, raw: String): IntArray? {
        val c = clean(raw).replace(" ", "").uppercase()
        if (c.contains("NODATA") || c.contains("ERROR") || c.contains("?")) return null
        val header = "41" + pid.substring(2)
        val idx = c.indexOf(header)
        if (idx < 0) return null
        val hex = c.substring(idx + header.length)
        if (hex.isEmpty() || hex.length % 2 != 0) return null
        return try {
            IntArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16) }
        } catch (_: NumberFormatException) { null }
    }

    fun speedKph(raw: String): Int? = dataBytes(Pid.SPEED, raw)?.getOrNull(0)

    fun rpm(raw: String): Int? {
        val d = dataBytes(Pid.RPM, raw) ?: return null
        if (d.size < 2) return null
        return (d[0] * 256 + d[1]) / 4
    }

    fun throttle01(raw: String): Double? =
        dataBytes(Pid.THROTTLE, raw)?.getOrNull(0)?.let { it / 255.0 }

    fun coolantC(raw: String): Int? =
        dataBytes(Pid.COOLANT, raw)?.getOrNull(0)?.let { it - 40 }

    /** ATRV → "12.6V" */
    fun batteryV(raw: String): Double? =
        Regex("([0-9]+\\.?[0-9]*)V?").find(clean(raw))?.groupValues?.get(1)?.toDoubleOrNull()
}

/**
 * Learns gear ratios from (rpm, speed) samples by 1-D clustering of rpm/speed, then
 * infers current gear. Ratio unit is rpm per m/s — proportional to overall gearing.
 */
class GearInference {
    private val samples = ArrayList<Double>()
    private var ratios: List<Double> = emptyList()

    val learnedRatios: List<Double> get() = ratios

    fun addSample(rpm: Int, speedMps: Double) {
        if (rpm < 1200 || speedMps < 3.0) return // idle/clutch-in noise
        samples += rpm / speedMps
        if (samples.size % 200 == 0) refit()
    }

    fun refit() {
        if (samples.size < 100) return
        val sorted = samples.sorted()
        // Greedy 1-D clustering: split where the relative jump between neighbours exceeds 12%.
        val clusters = ArrayList<MutableList<Double>>()
        var cur = mutableListOf(sorted.first())
        for (v in sorted.drop(1)) {
            if (v / cur.last() > 1.12) { clusters += cur; cur = mutableListOf() }
            cur += v
        }
        clusters += cur
        ratios = clusters
            .filter { it.size >= sorted.size / 25 } // ignore tiny noise clusters
            .map { it.sorted()[it.size / 2] }
            .sortedDescending() // highest ratio = 1st gear
            .take(8)
    }

    /** Current gear (1-based), or null if unknown or between gears. */
    fun currentGear(rpm: Int, speedMps: Double): Int? {
        if (ratios.isEmpty() || rpm < 1200 || speedMps < 3.0) return null
        val r = rpm / speedMps
        val idx = ratios.indices.minByOrNull { kotlin.math.abs(ratios[it] - r) } ?: return null
        return if (kotlin.math.abs(ratios[idx] - r) / ratios[idx] < 0.08) idx + 1 else null
    }

    /** Gear you would want at [vTargetMps], picking the gear that puts rpm nearest [idealRpm]. */
    fun gearForSpeed(vTargetMps: Double, idealRpm: Double = 3500.0): Int? {
        if (ratios.isEmpty() || vTargetMps < 1.0) return null
        val idx = ratios.indices.minByOrNull { kotlin.math.abs(ratios[it] * vTargetMps - idealRpm) }
            ?: return null
        return idx + 1
    }
}

/** Coolant / voltage health watch with hysteresis so it warns once, calmly. */
class HealthWatch(
    private val coolantWarnC: Int = 108,
    private val voltageWarnV: Double = 12.0,
) {
    private var coolantWarned = false
    private var voltageWarned = false

    /** Returns a warning clip key when a threshold is newly crossed, else null. */
    fun check(coolantC: Int?, batteryV: Double?): String? {
        if (coolantC != null) {
            if (coolantC >= coolantWarnC && !coolantWarned) { coolantWarned = true; return "warn_temps" }
            if (coolantC < coolantWarnC - 6) coolantWarned = false
        }
        if (batteryV != null) {
            if (batteryV < voltageWarnV && !voltageWarned) { voltageWarned = true; return "warn_battery" }
            if (batteryV > voltageWarnV + 0.4) voltageWarned = false
        }
        return null
    }
}
