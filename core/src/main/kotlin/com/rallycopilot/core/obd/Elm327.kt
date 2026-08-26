package com.rallycopilot.core.obd

/**
 * Pure ELM327 / OBD-II protocol helpers: command strings, response parsing, gear
 * inference. The Android layer owns the Bluetooth socket; this owns the bytes.
 */
object Elm327 {

    /**
     * Init for a cold ELM327. ATSP6 = ISO 15765-4 CAN 11-bit/500k — the protocol a
     * 2006 BMW E90 actually speaks; the client falls back to ATSP0 (auto) if the
     * first PID probe returns nothing. ATAT1/ATST32 keep polling snappy.
     */
    val INIT = listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATST32", "ATSP6")
    const val FALLBACK_PROTOCOL = "ATSP0"

    object Pid {
        const val SUPPORTED_01_20 = "0100"
        const val SUPPORTED_41_60 = "0140"
        const val SPEED = "010D"
        const val RPM = "010C"
        const val THROTTLE = "0111"        // often meaningless on diesels
        const val REL_THROTTLE = "0145"
        const val ACCEL_PEDAL_D = "0149"   // the real pedal signal on the E90 diesel
        const val ENGINE_LOAD = "0104"
        const val COOLANT = "0105"
        const val MAP_KPA = "010B"
        const val FUEL_LEVEL = "012F"
        const val AMBIENT = "0146"
        const val VOLTAGE = "ATRV"
    }

    /**
     * Parse a supported-PIDs bitmask response (0100/0120/0140…). Returns the set of
     * supported PID numbers within that range, e.g. 0x0C, 0x0D for a 0100 query.
     */
    fun supportedPids(basePid: Int, raw: String): Set<Int> {
        val pidHex = "01%02X".format(basePid)
        val d = dataBytes(pidHex, raw) ?: return emptySet()
        if (d.size < 4) return emptySet()
        val out = HashSet<Int>()
        for (byteIdx in 0 until 4) {
            for (bit in 0 until 8) {
                if (d[byteIdx] and (0x80 shr bit) != 0) out += basePid + byteIdx * 8 + bit + 1
            }
        }
        return out
    }

    /**
     * Choose the best pedal/throttle PID this ECU supports: accelerator pedal D
     * beats relative throttle beats absolute throttle. Null if none.
     */
    fun bestPedalPid(supported: Set<Int>): String? = when {
        0x49 in supported -> Pid.ACCEL_PEDAL_D
        0x45 in supported -> Pid.REL_THROTTLE
        0x11 in supported -> Pid.THROTTLE
        else -> null
    }

    /** Generic single-byte percentage PID (0x11, 0x45, 0x49, 0x04): A / 255. */
    fun percent01(pid: String, raw: String): Double? =
        dataBytes(pid, raw)?.getOrNull(0)?.let { it / 255.0 }

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

    fun ambientC(raw: String): Int? =
        dataBytes(Pid.AMBIENT, raw)?.getOrNull(0)?.let { it - 40 }

    fun mapKpa(raw: String): Int? = dataBytes(Pid.MAP_KPA, raw)?.getOrNull(0)

    fun fuelLevel01(raw: String): Double? =
        dataBytes(Pid.FUEL_LEVEL, raw)?.getOrNull(0)?.let { it / 255.0 }

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

/** Coolant / voltage / ice health watch with hysteresis so each warns once, calmly. */
class HealthWatch(
    private val coolantWarnC: Int = 108,
    private val voltageWarnV: Double = 12.0,
    /** Road frost risk well above freezing — bridges and shade freeze first. */
    private val iceWarnC: Int = 4,
) {
    private var coolantWarned = false
    private var voltageWarned = false
    private var iceWarned = false

    /** Returns a warning clip key when a threshold is newly crossed, else null. */
    fun check(coolantC: Int?, batteryV: Double?, ambientC: Int? = null): String? {
        if (coolantC != null) {
            if (coolantC >= coolantWarnC && !coolantWarned) { coolantWarned = true; return "warn_temps" }
            if (coolantC < coolantWarnC - 6) coolantWarned = false
        }
        if (batteryV != null) {
            if (batteryV < voltageWarnV && !voltageWarned) { voltageWarned = true; return "warn_battery" }
            if (batteryV > voltageWarnV + 0.4) voltageWarned = false
        }
        if (ambientC != null) {
            if (ambientC <= iceWarnC && !iceWarned) { iceWarned = true; return "warn_ice" }
            if (ambientC > iceWarnC + 2) iceWarned = false
        }
        return null
    }
}
