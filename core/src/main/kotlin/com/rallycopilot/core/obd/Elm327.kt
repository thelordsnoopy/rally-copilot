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
    val INIT = listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATAT1")
    const val FALLBACK_PROTOCOL = "ATSP0"

    /** Snappy per-request timeout, set only AFTER a protocol is known to work. */
    const val FAST_TIMING = "ATST32"
    /** Generous per-request timeout, for use while hunting for the protocol. */
    const val SLOW_TIMING = "ATSTFF"

    /**
     * Protocols to try, in order, when we do not already know what the car speaks.
     * Auto-detect goes first because the ELM's own search handles most cars; the
     * explicit entries exist because a clone that fumbles auto-detect will often
     * connect happily when told exactly what to use.
     *
     * The timeout matters enormously: an ELM327 protocol search can take five to
     * ten seconds (the 5-baud ISO inits are slow by specification), so giving the
     * first request after a protocol change a short deadline guarantees a false
     * "the car isn't answering".
     */
    data class Protocol(val cmd: String, val label: String, val probeMs: Long)

    val PROTOCOL_SWEEP = listOf(
        Protocol("ATSP0", "auto-detect", 15_000),
        Protocol("ATSP6", "CAN 11-bit 500k", 6_000),
        Protocol("ATSP7", "CAN 29-bit 500k", 6_000),
        Protocol("ATSP8", "CAN 11-bit 250k", 6_000),
        Protocol("ATSP9", "CAN 29-bit 250k", 6_000),
        Protocol("ATSP5", "KWP2000 fast", 10_000),
        Protocol("ATSP4", "KWP2000 5-baud", 12_000),
        Protocol("ATSP3", "ISO 9141-2", 12_000),
        Protocol("ATSP1", "J1850 PWM", 8_000),
        Protocol("ATSP2", "J1850 VPW", 8_000),
    )

    /**
     * The ELM's own complaint, if the reply is one. These strings are the single
     * most useful diagnostic the hardware produces and were previously discarded.
     */
    fun elmError(raw: String): String? {
        val c = clean(raw).uppercase()
        return when {
            "UNABLE TO CONNECT" in c -> "UNABLE TO CONNECT (no protocol matched the car)"
            "CAN ERROR" in c -> "CAN ERROR (bus wiring or wrong CAN speed)"
            "BUS INIT" in c && "ERROR" in c -> "BUS INIT ERROR"
            "BUS ERROR" in c -> "BUS ERROR"
            "BUS BUSY" in c -> "BUS BUSY"
            "STOPPED" in c -> "STOPPED"
            "NO DATA" in c -> "NO DATA (car heard the request, did not answer)"
            c.trim() == "?" -> "dongle did not understand the command"
            else -> null
        }
    }

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
        const val VIN = "0902"
    }

    /**
     * Parse a mode-09 VIN response. Multi-frame over CAN, so the ELM emits either a
     * single line or ISO-TP segments prefixed "0:", "1:", "2:" (plus an optional
     * length line like "014"). Strategy: strip segment markers, join the hex, find
     * the 49 02 01 header, decode the rest as ASCII, and sanity-check the result
     * against VIN shape (17 chars, no I/O/Q).
     */
    fun vin(raw: String): String? {
        val joined = raw.replace("\r", "\n").split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("") { line ->
                // drop "0:", "1:" ISO-TP segment markers and bare length lines like "014"
                val noMarker = line.replace(Regex("^[0-9A-Fa-f]:"), "")
                if (noMarker.replace(" ", "").matches(Regex("^[0-9A-Fa-f]{3}$"))) "" else noMarker
            }
            .replace(" ", "").uppercase()
        val idx = joined.indexOf("490201")
        if (idx < 0) return null
        val hex = joined.substring(idx + 6)
        val sb = StringBuilder()
        var i = 0
        while (i + 1 < hex.length && sb.length < 17) {
            val b = hex.substring(i, i + 2).toIntOrNull(16) ?: break
            if (b in 0x30..0x5A) sb.append(b.toChar()) // printable VIN charset region
            i += 2
        }
        val v = sb.toString()
        return if (v.length == 17 && v.none { it in "IOQ" }) v else null
    }

    /**
     * Parse a supported-PIDs bitmask response (0100/0120/0140…). Returns the set of
     * supported PID numbers within that range, e.g. 0x0C, 0x0D for a 0100 query.
     */
    fun supportedPids(basePid: Int, raw: String): Set<Int> {
        // Multiple ECUs may answer a supported-PIDs query (with headers off they are
        // indistinguishable). Union every responder's mask — taking only the first
        // could cache a narrower ECU's mask and hide PIDs the engine ECU supports.
        val pidHex = "01%02X".format(basePid)
        val c = clean(raw).replace(" ", "").uppercase()
        if (c.contains("NODATA") || c.contains("ERROR") || c.contains("?")) return emptySet()
        val header = "41" + pidHex.substring(2)
        val out = HashSet<Int>()
        var idx = c.indexOf(header)
        while (idx >= 0) {
            val hex = c.substring(idx + header.length)
            if (hex.length >= 8) {
                val d = try {
                    IntArray(4) { hex.substring(it * 2, it * 2 + 2).toInt(16) }
                } catch (_: NumberFormatException) { null }
                if (d != null) {
                    for (byteIdx in 0 until 4) {
                        for (bit in 0 until 8) {
                            if (d[byteIdx] and (0x80 shr bit) != 0) out += basePid + byteIdx * 8 + bit + 1
                        }
                    }
                }
            }
            idx = c.indexOf(header, idx + header.length)
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

    /** Strip ELM chatter: echoes, prompts, whitespace, and the NUL bytes clones emit. */
    fun clean(raw: String): String =
        raw.replace("\u0000", "").replace(">", "").replace("\r", " ").replace("\n", " ").trim()

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
 * Learns the car's gearing from (rpm, speed) samples and infers gears.
 *
 * Ratio unit is rpm per m/s — proportional to overall gearing, so a gear's ratio
 * gives everything else: the speed it tops out at (redline / ratio), the speed
 * range it covers, and the revs it will land you on at any target speed.
 *
 * Everything here self-calibrates from driving and PERSISTS per car (keyed by VIN
 * upstream), so gear calls work from the first corner of the second drive rather
 * than re-learning from scratch every time.
 *
 * The rev band it aims for is learned too: the driver's own spirited upshift point
 * defines the top of the usable band, so "the right gear for this corner" means
 * the driver's own idea of the right gear, not a number baked in here.
 */
class GearInference(private val params: Params = Params()) {

    data class Params(
        val minRpm: Int = 1100,
        val minSpeedMps: Double = 3.0,
        /** Enough samples to trust a fit at all. ~15 s of driving at 4 Hz. */
        val minSamples: Int = 60,
        /** Relative gap between neighbouring ratios that separates two gears. */
        val clusterSplit: Double = 1.12,
        val idleRpm: Double = 800.0,
        val redlineDefault: Double = 4800.0,
        /** Fallback spirited upshift point until the driver's own is observed. */
        val shiftRpmDefault: Double = 3400.0,
        /** Corner exit target, as a fraction of the way from idle to the shift point.
         *  Low enough to be smooth, high enough to pull cleanly without a downshift. */
        val exitBandFraction: Double = 0.45,
        /** Never suggest a gear that would be bouncing off the limiter at target speed. */
        val maxExitFractionOfRedline: Double = 0.85,
    )

    // Written by the OBD polling thread, read by the engine thread (gear calls) —
    // every field crossing that boundary is volatile, and the mutable collections
    // never leave this class. `ratios` is only ever REPLACED, never mutated in place,
    // so a reader always sees a complete list.
    private val samples = ArrayList<Double>()
    @Volatile private var ratios: List<Double> = emptyList()
    @Volatile private var observedMaxRpm = 0.0
    private val upshiftRpms = ArrayDeque<Double>()
    /** Median upshift rpm, republished whenever [upshiftRpms] changes. */
    @Volatile private var shiftRpmCache = Double.NaN
    private var lastGearSeen: Int? = null
    private var lastRpmSeen: Int? = null
    @Volatile private var dirty = false

    val learnedRatios: List<Double> get() = ratios
    val gearCount: Int get() = ratios.size
    /** True once the gearing is known well enough to speak gear calls. */
    val isCalibrated: Boolean get() = ratios.size >= 3

    /** Estimated redline: the highest rpm actually seen, with a sane floor. */
    val redlineRpm: Double
        get() = maxOf(observedMaxRpm, params.redlineDefault * 0.75).coerceAtLeast(3000.0)

    /** The driver's own spirited upshift point (median), or the default until seen. */
    val shiftRpm: Double
        get() = shiftRpmCache.takeIf { !it.isNaN() } ?: params.shiftRpmDefault

    private fun republishShiftRpm() {
        shiftRpmCache = if (upshiftRpms.size >= 3) upshiftRpms.sorted()[upshiftRpms.size / 2]
        else Double.NaN
    }

    /** Revs to aim for coming out of a corner — learned from how this driver shifts. */
    val exitRpm: Double
        get() = params.idleRpm + params.exitBandFraction * (shiftRpm - params.idleRpm)

    /** Top speed in [gear] (1-based) at the redline, m/s. Null if not learned. */
    fun topSpeedOf(gear: Int): Double? =
        ratios.getOrNull(gear - 1)?.let { redlineRpm / it }

    /** Usable speed range of [gear]: from clean pull-away revs to the shift point. */
    fun speedRangeOf(gear: Int): ClosedFloatingPointRange<Double>? {
        val r = ratios.getOrNull(gear - 1) ?: return null
        return (params.idleRpm * 1.4 / r)..(shiftRpm / r)
    }

    /** Revs this gear would be turning at [speedMps]. */
    fun rpmAt(gear: Int, speedMps: Double): Double? =
        ratios.getOrNull(gear - 1)?.let { it * speedMps }

    fun addSample(rpm: Int, speedMps: Double) {
        if (rpm > observedMaxRpm && rpm < 8000) { observedMaxRpm = rpm.toDouble(); dirty = true }
        if (rpm < params.minRpm || speedMps < params.minSpeedMps) return
        samples += rpm / speedMps
        // Refit eagerly while still learning, then occasionally to track wear/tyres.
        val n = samples.size
        if (n == params.minSamples || (n < 400 && n % 40 == 0) || n % 200 == 0) refit()
        // Track the driver's upshift points: gear up, and remember the revs before it.
        val g = currentGear(rpm, speedMps)
        if (g != null && lastGearSeen != null && g == lastGearSeen!! + 1) {
            lastRpmSeen?.let {
                if (it > params.idleRpm * 1.5) {
                    upshiftRpms += it.toDouble()
                    while (upshiftRpms.size > 15) upshiftRpms.removeFirst()
                    republishShiftRpm()
                    dirty = true
                }
            }
        }
        if (g != null) lastGearSeen = g
        lastRpmSeen = rpm
    }

    fun refit() {
        if (samples.size < params.minSamples) return
        val sorted = samples.sorted()
        // Greedy 1-D clustering: split where the relative jump between neighbours exceeds 12%.
        val clusters = ArrayList<MutableList<Double>>()
        var cur = mutableListOf(sorted.first())
        for (v in sorted.drop(1)) {
            if (v / cur.last() > params.clusterSplit) { clusters += cur; cur = mutableListOf() }
            cur += v
        }
        clusters += cur
        val fitted = clusters
            .filter { it.size >= maxOf(3, sorted.size / 25) } // ignore tiny noise clusters
            .map { it.sorted()[it.size / 2] }
            .sortedDescending() // highest ratio = 1st gear
            .take(8)
        if (fitted.isNotEmpty() && fitted != ratios) { ratios = fitted; dirty = true }
    }

    /** Current gear (1-based), or null if unknown or between gears. */
    fun currentGear(rpm: Int, speedMps: Double): Int? {
        if (ratios.isEmpty() || rpm < params.minRpm || speedMps < params.minSpeedMps) return null
        val r = rpm / speedMps
        val idx = ratios.indices.minByOrNull { kotlin.math.abs(ratios[it] - r) } ?: return null
        return if (kotlin.math.abs(ratios[idx] - r) / ratios[idx] < 0.08) idx + 1 else null
    }

    /**
     * The gear to be in through a corner taken at [vTargetMps]: the one landing
     * nearest the learned corner-exit revs, never one that would sit above
     * [Params.maxExitFractionOfRedline] of the redline (you'd be shifting mid-corner).
     */
    fun gearForSpeed(vTargetMps: Double, idealRpm: Double = exitRpm): Int? {
        if (!isCalibrated || vTargetMps < 1.0) return null
        val ceiling = redlineRpm * params.maxExitFractionOfRedline
        val usable = ratios.indices.filter { ratios[it] * vTargetMps <= ceiling }
        val pool = usable.ifEmpty { listOf(ratios.indices.last()) } // very fast: top gear
        val idx = pool.minByOrNull { kotlin.math.abs(ratios[it] * vTargetMps - idealRpm) }
            ?: return null
        return idx + 1
    }

    // ---- persistence: the calibration follows the car, keyed by VIN upstream ----

    /** True when there is new calibration worth saving. */
    fun consumeDirty(): Boolean = dirty.also { dirty = false }

    fun serialise(): String = buildString {
        append(ratios.joinToString(",") { "%.3f".format(it) })
        append(";").append("%.0f".format(observedMaxRpm))
        append(";").append(upshiftRpms.joinToString(",") { "%.0f".format(it) })
    }

    fun restore(s: String?) {
        if (s.isNullOrBlank()) return
        val parts = s.split(";")
        parts.getOrNull(0)?.split(",")?.mapNotNull { it.trim().toDoubleOrNull() }
            ?.filter { it > 1.0 }?.sortedDescending()
            ?.let { if (it.isNotEmpty()) ratios = it }
        parts.getOrNull(1)?.trim()?.toDoubleOrNull()?.let { if (it in 1000.0..8000.0) observedMaxRpm = it }
        parts.getOrNull(2)?.split(",")?.mapNotNull { it.trim().toDoubleOrNull() }
            ?.forEach { if (it in 1000.0..8000.0) upshiftRpms += it }
        republishShiftRpm()
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
