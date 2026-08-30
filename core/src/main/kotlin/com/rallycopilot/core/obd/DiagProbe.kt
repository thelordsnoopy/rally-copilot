package com.rallycopilot.core.obd

/**
 * Asking the car's OTHER modules what they know.
 *
 * Standard OBD (mode 01) offers one vehicle speed and nothing about how the car is
 * behaving: no per-wheel speeds, no steering angle, no traction-control activity.
 * All of that exists on a BMW E-series — the DSC unit measures each wheel, plus
 * its own yaw rate and lateral acceleration — but only over BMW's diagnostic
 * addressing, not the OBD PIDs the app already polls.
 *
 * WHY THIS IS WORTH THE TROUBLE. Every slip signal the app has is inferred: the
 * gyro says the body rotated, GPS says the path did not, and a loose phone mount
 * biases the comparison by 17%. Four wheel speeds are not an inference. Rear
 * wheels turning faster than the fronts IS wheelspin, definitionally, and a
 * welded diff on worn rears produces exactly that. The car's own yaw sensor would
 * also settle the question the removed mount-alignment code never could.
 *
 * WHY IT IS A PROBE AND NOT A FEATURE. The addressing below is documented well
 * enough to try and not well enough to trust: the module addresses are the
 * conventional E-series ones, but which local identifiers a particular DSC build
 * answers, and what its bytes mean, is not something to guess at. So this sweeps
 * a bounded, READ-ONLY space and writes down everything that answers. The trace
 * is the deliverable; the feature comes after reading it.
 *
 * SAFETY. [isReadOnly] is enforced on every request before it goes near the bus:
 * only identifier reads and tester-present are permitted. No writes, no routine
 * control, no security access, no session changes, nothing that can actuate
 * anything. A probe that could move a brake caliper has no business existing.
 */
object DiagProbe {

    /**
     * BMW E-series diagnostic addresses. On D-CAN the tester sends from header
     * 0x6F1 with the target module as the first payload byte — which an ELM327
     * does with `ATSH 6F1` plus `ATCEA <addr>`.
     *
     * These are CANDIDATES to be confirmed by the probe, not established fact for
     * this particular car. [DME] is the control: it already answers over standard
     * OBD, so if the diagnostic addressing works at all it should answer here too.
     */
    data class Module(val addr: Int, val name: String, val hoping: String)

    val MODULES = listOf(
        Module(0x12, "DME", "engine — the control: this one is known to be alive"),
        Module(0x29, "DSC", "per-wheel speeds, yaw rate, lateral g, brake pressure"),
        Module(0x60, "KOMBI", "cluster: odometer, outside temp, fuel level"),
        Module(0x72, "SZL", "steering column — steering wheel angle"),
        Module(0x40, "CAS", "car access: terminal/ignition status"),
        Module(0x78, "IHKA", "climate — likely useless, cheap to ask"),
    )

    /**
     * Services this probe may send. Read-only by construction:
     *  0x1A readEcuIdentification, 0x21 readDataByLocalIdentifier (what BMW's
     *  E-series modules mostly speak), 0x22 readDataByIdentifier (UDS),
     *  0x3E testerPresent.
     * Everything else — 0x2E/0x2F writes, 0x31 routine control, 0x27 security
     * access, 0x10 session control, 0x11 reset — is refused.
     */
    val READ_SERVICES = setOf(0x1A, 0x21, 0x22, 0x3E)

    /** Is this request hex safe to send? First byte must be a read service. */
    fun isReadOnly(requestHex: String): Boolean {
        val bytes = hexBytes(requestHex)
        if (bytes.isEmpty()) return false
        return bytes[0] in READ_SERVICES
    }

    /** Parse a hex string ("21 A0", "2100", "62F190AB") into bytes. */
    fun hexBytes(s: String): List<Int> {
        val clean = s.filter { !it.isWhitespace() }
        if (clean.length % 2 != 0) return emptyList()
        return runCatching {
            clean.chunked(2).map { it.toInt(16) }
        }.getOrDefault(emptyList())
    }

    fun hex(b: Int): String = "%02X".format(b and 0xFF)

    // ------------------------------------------------------------------ replies

    enum class Answer {
        /** The ELM said nothing useful: NO DATA, timeout, CAN ERROR. */
        SILENT,
        /** The module answered "I will not": service not supported, out of range. */
        REFUSED,
        /** The module answered with data. */
        DATA,
    }

    data class Reply(
        val answer: Answer,
        /** Data bytes AFTER the echoed service and identifier, when [answer] is DATA. */
        val payload: List<Int> = emptyList(),
        /** The whole response frame, service echo included. */
        val frame: List<Int> = emptyList(),
        /** Negative response code, when [answer] is REFUSED. */
        val nrc: Int? = null,
        /** What the ELM itself said, when it complained. */
        val elm: String? = null,
    )

    /**
     * Read one ELM327 response. The BMW addressing echoes the tester's address
     * back as the first byte, so a reply to `21 A0` from the DSC looks like
     * `F1 xx 61 A0 <data...>` — the service echo is the request service + 0x40.
     */
    fun parseReply(raw: String, requestHex: String): Reply {
        val text = raw.uppercase()
        Elm327.elmError(text)?.let { return Reply(Answer.SILENT, elm = it) }
        if ("NO DATA" in text || "STOPPED" in text || "?" in text) {
            return Reply(Answer.SILENT, elm = text.trim().replace('\r', ' ').ifEmpty { null })
        }
        // Strip line noise and keep hex pairs only.
        val bytes = hexBytes(text.filter { it.isDigit() || it in 'A'..'F' })
        if (bytes.isEmpty()) return Reply(Answer.SILENT)

        val req = hexBytes(requestHex)
        if (req.isEmpty()) return Reply(Answer.SILENT, frame = bytes)
        val service = req[0]

        // A negative response is 7F <service> <nrc>, wherever it sits in the frame.
        for (i in 0 until bytes.size - 2) {
            if (bytes[i] == 0x7F && bytes[i + 1] == service) {
                return Reply(Answer.REFUSED, frame = bytes, nrc = bytes[i + 2])
            }
        }
        // A positive response echoes service + 0x40, then the identifier bytes.
        val echo = service + 0x40
        val at = bytes.indexOfFirst { it == echo }
        if (at < 0) return Reply(Answer.SILENT, frame = bytes)
        // Identifier length: 0x22 uses two bytes, 0x21/0x1A one, 0x3E none.
        val idLen = when (service) {
            0x22 -> 2
            0x21, 0x1A -> 1
            else -> 0
        }
        val from = at + 1 + idLen
        val payload = if (from <= bytes.size) bytes.drop(from) else emptyList()
        return Reply(Answer.DATA, payload = payload, frame = bytes)
    }

    /** Negative response codes worth naming in a log. */
    fun nrcText(nrc: Int): String = when (nrc) {
        0x10 -> "general reject"
        0x11 -> "service not supported"
        0x12 -> "sub-function not supported"
        0x13 -> "wrong message length"
        0x22 -> "conditions not correct"
        0x31 -> "request out of range"
        0x33 -> "security access denied"
        0x78 -> "busy, response pending"
        else -> "NRC 0x${hex(nrc)}"
    }

    // ------------------------------------------------------- what do the bytes mean

    /**
     * A candidate reading of four consecutive 16-bit fields as wheel speeds.
     * [scaleKphPerBit] is the divisor tried; [spreadKph] is how far apart the four
     * values are, which is the whole point — four wheels of a car going in a
     * straight line agree to within a couple of km/h, and that agreement is what
     * identifies the field. A slide is when they stop agreeing.
     */
    data class WheelGuess(
        val offset: Int,
        val bigEndian: Boolean,
        val scaleKphPerBit: Double,
        val kph: List<Double>,
    ) {
        val meanKph: Double get() = kph.average()
        val spreadKph: Double get() = (kph.maxOrNull() ?: 0.0) - (kph.minOrNull() ?: 0.0)
    }

    /** Scales seen on BMW wheel-speed fields; the probe tries each. */
    private val SCALES = listOf(1.0, 0.5, 0.25, 0.125, 0.0625, 0.1, 0.01)

    /**
     * Hunt [payload] for four consecutive 16-bit values that could be the four
     * wheel speeds at a known [referenceKph] (the car's own OBD speed, read in the
     * same session). Requires all four within [tolKph] of the reference and of
     * each other — which is why this must be run while ROLLING. Parked, every
     * candidate reads zero and nothing can be told apart.
     */
    fun findWheelSpeeds(
        payload: List<Int>,
        referenceKph: Double,
        tolKph: Double = 4.0,
    ): List<WheelGuess> {
        if (referenceKph < 8.0) return emptyList() // parked or crawling: no evidence
        val out = ArrayList<WheelGuess>()
        for (off in 0..(payload.size - 8)) {
            for (be in listOf(true, false)) {
                val raw = (0 until 4).map { i ->
                    val a = payload[off + i * 2]
                    val b = payload[off + i * 2 + 1]
                    if (be) (a shl 8) or b else (b shl 8) or a
                }
                if (raw.any { it == 0xFFFF }) continue // classic "signal invalid"
                for (scale in SCALES) {
                    val kph = raw.map { it * scale }
                    if (kph.any { it > 320 }) continue
                    val guess = WheelGuess(off, be, scale, kph)
                    if (guess.spreadKph <= tolKph &&
                        kotlin.math.abs(guess.meanKph - referenceKph) <= tolKph
                    ) out += guess
                }
            }
        }
        return out
    }

    /**
     * Steering angle: one 16-bit signed field that reads near zero going straight
     * and swings by hundreds of degrees lock to lock. Identified by CHANGE rather
     * than by value, so the caller passes what the same offset read a moment ago.
     */
    data class AngleGuess(val offset: Int, val bigEndian: Boolean, val scaleDegPerBit: Double, val deg: Double)

    private val ANGLE_SCALES = listOf(0.5, 0.1, 0.04375, 0.0625, 1.0)

    fun findSteeringAngle(payload: List<Int>, straightAheadRef: List<Int>?): List<AngleGuess> {
        val out = ArrayList<AngleGuess>()
        for (off in 0..(payload.size - 2)) {
            for (be in listOf(true, false)) {
                val a = payload[off]; val b = payload[off + 1]
                val u = if (be) (a shl 8) or b else (b shl 8) or a
                val signed = if (u > 0x7FFF) u - 0x10000 else u
                // Must have MOVED since the straight-ahead reference, or there is
                // nothing to distinguish it from any other counter.
                if (straightAheadRef != null && off + 1 < straightAheadRef.size) {
                    val ra = straightAheadRef[off]; val rb = straightAheadRef[off + 1]
                    val ru = if (be) (ra shl 8) or rb else (rb shl 8) or ra
                    val rs = if (ru > 0x7FFF) ru - 0x10000 else ru
                    if (kotlin.math.abs(signed - rs) < 20) continue
                }
                for (scale in ANGLE_SCALES) {
                    val deg = signed * scale
                    if (kotlin.math.abs(deg) in 5.0..800.0) out += AngleGuess(off, be, scale, deg)
                }
            }
        }
        return out
    }

    // ------------------------------------------------------------------ the sweep

    /** One request the probe will make. */
    data class Step(
        val module: Module,
        val requestHex: String,
        val why: String,
    ) {
        init { require(isReadOnly(requestHex)) { "probe refuses non-read request $requestHex" } }
    }

    /**
     * Phase 1 — which modules are even there. One cheap read each; anything other
     * than silence proves the addressing works and the module is awake.
     */
    fun discoverySteps(): List<Step> = MODULES.map {
        Step(it, "1A80", "is anything home at 0x${hex(it.addr)}? (read ECU identification)")
    } + MODULES.map {
        Step(it, "2100", "does 0x${hex(it.addr)} speak the older read-by-local-id service?")
    }

    /**
     * Phase 2 — sweep the local identifier space of a module that answered. 256
     * requests at ~120 ms is about half a minute per module, which is a tolerable
     * price for a map of everything it will tell us.
     */
    fun sweepSteps(module: Module, from: Int = 0x00, to: Int = 0xFF): List<Step> =
        (from..to).map { lid ->
            Step(module, "21${hex(lid)}", "local identifier 0x${hex(lid)}")
        }

    /** Phase 3 — re-read only what answered, fast, so the bytes can be watched
     *  against a changing road speed. */
    fun watchSteps(answering: List<Step>): List<Step> = answering
}
