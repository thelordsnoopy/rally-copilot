package com.rallycopilot.core.obd

/**
 * Every sensor the car can be asked about, what it means, and how to read it.
 *
 * The app has only ever polled the eight or nine values it needed — speed, rpm,
 * pedal, coolant, MAP, fuel, ambient, voltage — and quietly ignored everything
 * else the ECU offers. That was fine while the question was "how fast are we
 * going", and useless the moment the question became "what else does this car
 * already know that we are throwing away".
 *
 * So this is the reference: the standard OBD-II mode 01 space, with the real
 * decode for each, the units, and a note on whether it is worth anything HERE.
 * [DECODABLE] is the subset the app can turn into a number; the rest are listed
 * so a sweep can still record that the car supports them.
 *
 * WHAT IS ACTUALLY WORTH HAVING. Most of mode 01 is emissions plumbing. A
 * handful are genuinely useful to a co-driver and are not currently read:
 *
 *   0x5C  engine oil temperature — the honest "is the engine warm enough to
 *         push" signal. Coolant comes up in three minutes; oil takes fifteen,
 *         and it is oil that decides whether pushing is sensible.
 *   0x62  actual engine torque, and 0x61 driver's demand torque — what the
 *         DRIVER asked for versus what the engine delivered. Far better than
 *         throttle position for "was this corner speed the driver's choice or
 *         the car in front", which is exactly what the constrained test in
 *         ObservationCollector has to guess at today.
 *   0x33  barometric pressure — altitude, and therefore how much the engine is
 *         down on power on a hill climb.
 *   0x5E  fuel rate — litres per hour, so a drive can report what it cost.
 *
 * Nothing here is a guess about a particular car: these are the SAE J1979
 * standard definitions. Which of them a given ECU actually answers is a
 * different question, and only the car can settle it — hence the sweep.
 */
object SensorCatalog {

    /** How to turn response bytes into a number. */
    fun interface Decode {
        /** [b] the data bytes after the mode/PID echo. Null if not decodable. */
        fun value(b: IntArray): Double?
    }

    data class Sensor(
        /** Mode-01 PID number, e.g. 0x0C for engine rpm. */
        val pid: Int,
        val name: String,
        val unit: String,
        /** Data bytes the response carries. */
        val bytes: Int,
        /** Null when the value is a bitfield or status word, not a measurement. */
        val decode: Decode? = null,
        /** What it is worth to this app, when it is worth anything. */
        val useful: String? = null,
    ) {
        val request: String get() = "01%02X".format(pid)
        val decodable: Boolean get() = decode != null
    }

    private fun a(b: IntArray) = b.getOrNull(0)?.toDouble()
    private fun ab(b: IntArray): Double? {
        val x = b.getOrNull(0) ?: return null
        val y = b.getOrNull(1) ?: return null
        return (x * 256 + y).toDouble()
    }
    /** The classic percentage byte: 0-255 maps to 0-100%. */
    private fun pct(b: IntArray) = a(b)?.let { it * 100.0 / 255.0 }
    /** The classic temperature byte: offset by 40 so -40 C is zero. */
    private fun tempC(b: IntArray) = a(b)?.let { it - 40.0 }
    /** The classic signed-percentage byte, centred on 128. */
    private fun signedPct(b: IntArray) = a(b)?.let { (it - 128.0) * 100.0 / 128.0 }

    val ALL: List<Sensor> = listOf(
        Sensor(0x00, "PIDs supported 01-20", "bitfield", 4),
        Sensor(0x01, "Monitor status since codes cleared", "bitfield", 4),
        Sensor(0x02, "Freeze frame trouble code", "code", 2),
        Sensor(0x03, "Fuel system status", "bitfield", 2),
        Sensor(0x04, "Calculated engine load", "%", 1, ::pct,
            "the app's pedal fallback when no accelerator PID exists"),
        Sensor(0x05, "Engine coolant temperature", "C", 1, ::tempC,
            "already used: the health watch warns on overheating"),
        Sensor(0x06, "Short term fuel trim, bank 1", "%", 1, ::signedPct),
        Sensor(0x07, "Long term fuel trim, bank 1", "%", 1, ::signedPct),
        Sensor(0x08, "Short term fuel trim, bank 2", "%", 1, ::signedPct),
        Sensor(0x09, "Long term fuel trim, bank 2", "%", 1, ::signedPct),
        Sensor(0x0A, "Fuel pressure", "kPa", 1, { b -> a(b)?.let { it * 3.0 } }),
        Sensor(0x0B, "Intake manifold absolute pressure", "kPa", 1, ::a,
            "already used: boost, and a rough proxy for how hard the engine works"),
        Sensor(0x0C, "Engine RPM", "rpm", 2, { b -> ab(b)?.let { it / 4.0 } },
            "already used: gear inference, shift calls, the wheelspin test"),
        Sensor(0x0D, "Vehicle speed", "km/h", 1, ::a,
            "already used: speed fusion. Comes from the DSC's wheel speeds"),
        Sensor(0x0E, "Timing advance", "deg before TDC", 1, { b -> a(b)?.let { it / 2.0 - 64.0 } }),
        Sensor(0x0F, "Intake air temperature", "C", 1, ::tempC,
            "cold dense air on a winter morning: grip is usually worse than the air suggests"),
        Sensor(0x10, "MAF air flow rate", "g/s", 2, { b -> ab(b)?.let { it / 100.0 } }),
        Sensor(0x11, "Throttle position", "%", 1, ::pct,
            "meaningless on this diesel - 0x49 is the real pedal"),
        Sensor(0x12, "Commanded secondary air status", "bitfield", 1),
        Sensor(0x13, "Oxygen sensors present", "bitfield", 1),
        Sensor(0x1C, "OBD standards this vehicle conforms to", "enum", 1),
        Sensor(0x1F, "Run time since engine start", "s", 2, ::ab,
            "how long the engine has been running - pairs with oil temp for warm-up"),
        Sensor(0x20, "PIDs supported 21-40", "bitfield", 4),
        Sensor(0x21, "Distance travelled with MIL on", "km", 2, ::ab),
        Sensor(0x22, "Fuel rail pressure (rel. to manifold vacuum)", "kPa", 2,
            { b -> ab(b)?.let { it * 0.079 } }),
        Sensor(0x23, "Fuel rail gauge pressure", "kPa", 2, { b -> ab(b)?.let { it * 10.0 } }),
        Sensor(0x2C, "Commanded EGR", "%", 1, ::pct),
        Sensor(0x2D, "EGR error", "%", 1, ::signedPct),
        Sensor(0x2E, "Commanded evaporative purge", "%", 1, ::pct),
        Sensor(0x2F, "Fuel tank level input", "%", 1, ::pct,
            "already used: range and the post-drive sheet"),
        Sensor(0x30, "Warm-ups since codes cleared", "count", 1, ::a),
        Sensor(0x31, "Distance since codes cleared", "km", 2, ::ab),
        Sensor(0x33, "Absolute barometric pressure", "kPa", 1, ::a,
            "altitude, and how much power the engine is down on climbing a hill"),
        Sensor(0x3C, "Catalyst temperature bank 1 sensor 1", "C", 2,
            { b -> ab(b)?.let { it / 10.0 - 40.0 } }),
        Sensor(0x40, "PIDs supported 41-60", "bitfield", 4),
        Sensor(0x42, "Control module voltage", "V", 2, { b -> ab(b)?.let { it / 1000.0 } },
            "alternator health; the app currently reads ATRV from the dongle instead"),
        Sensor(0x43, "Absolute load value", "%", 2, { b -> ab(b)?.let { it * 100.0 / 255.0 } }),
        Sensor(0x44, "Commanded air-fuel equivalence ratio", "ratio", 2,
            { b -> ab(b)?.let { it * 2.0 / 65536.0 } }),
        Sensor(0x45, "Relative throttle position", "%", 1, ::pct),
        Sensor(0x46, "Ambient air temperature", "C", 1, ::tempC,
            "already used: cold-weather grip warning, and the wet/dry guess"),
        Sensor(0x47, "Absolute throttle position B", "%", 1, ::pct),
        Sensor(0x49, "Accelerator pedal position D", "%", 1, ::pct,
            "already used: THE pedal signal on this car - what the driver asked for"),
        Sensor(0x4A, "Accelerator pedal position E", "%", 1, ::pct),
        Sensor(0x4C, "Commanded throttle actuator", "%", 1, ::pct),
        Sensor(0x4D, "Time run with MIL on", "min", 2, ::ab),
        Sensor(0x51, "Fuel type", "enum", 1),
        Sensor(0x5C, "Engine oil temperature", "C", 1, ::tempC,
            "WANTED: the honest warm-up signal. Coolant is up in 3 minutes, oil takes 15"),
        Sensor(0x5E, "Engine fuel rate", "L/h", 2, { b -> ab(b)?.let { it / 20.0 } },
            "WANTED: what the drive cost, and a good proxy for commitment"),
        Sensor(0x61, "Driver's demand engine torque", "%", 1, { b -> a(b)?.let { it - 125.0 } },
            "WANTED: what the DRIVER asked of the engine - better than pedal position"),
        Sensor(0x62, "Actual engine torque", "%", 1, { b -> a(b)?.let { it - 125.0 } },
            "WANTED: what the engine delivered. Demand minus actual is the car saying no"),
        Sensor(0x63, "Engine reference torque", "Nm", 2, ::ab,
            "scales 0x61/0x62 from percentages into real torque"),
    )

    val BY_PID: Map<Int, Sensor> = ALL.associateBy { it.pid }

    /** The ones the app can turn into a number rather than merely note. */
    val DECODABLE: List<Sensor> = ALL.filter { it.decodable }

    /** Sensors worth having that the app does NOT currently read. */
    val WANTED: List<Sensor> = ALL.filter { it.useful?.startsWith("WANTED") == true }

    /**
     * The supported-PID bitmap requests. The app has only ever asked two of these
     * (0x00 and 0x40), so PIDs 0x21-0x40 and everything past 0x60 were invisible —
     * oil temperature and the torque pair among them.
     */
    val SUPPORT_QUERIES: List<Pair<Int, String>> = listOf(
        0x00 to "0100", 0x20 to "0120", 0x40 to "0140",
        0x60 to "0160", 0x80 to "0180", 0xA0 to "01A0", 0xC0 to "01C0",
    )

    /** Human-readable name for a PID, known or not. */
    fun nameOf(pid: Int): String = BY_PID[pid]?.name ?: "unknown PID 0x%02X".format(pid)

    /**
     * Decode one response for [pid]. Returns null when the PID is unknown, is a
     * bitfield rather than a measurement, or the response did not parse.
     */
    fun decode(pid: Int, raw: String): Double? {
        val s = BY_PID[pid] ?: return null
        val d = s.decode ?: return null
        val bytes = Elm327.dataBytes("01%02X".format(pid), raw) ?: return null
        if (bytes.size < s.bytes) return null
        return d.value(bytes)?.takeIf { it.isFinite() }
    }

    /**
     * Phone-side sensors worth recording once per drive, by Android type constant.
     * The app uses accelerometer, gravity and gyroscope; the rest are listed so a
     * drive can note what this handset actually has rather than assuming.
     */
    data class PhoneSensor(val type: Int, val name: String, val useful: String? = null)

    val PHONE: List<PhoneSensor> = listOf(
        PhoneSensor(1, "Accelerometer", "roughness, bumps, and the longitudinal axis"),
        PhoneSensor(4, "Gyroscope", "yaw rate: the whole slip story rests on this one"),
        PhoneSensor(9, "Gravity", "which way is down, and how much the mount wobbles"),
        PhoneSensor(10, "Linear acceleration", "accelerometer with gravity already removed"),
        PhoneSensor(2, "Magnetometer", "heading when stationary, where GPS has none"),
        PhoneSensor(6, "Barometer", "altitude changes - gradient without the map"),
        PhoneSensor(11, "Rotation vector", "fused orientation"),
        PhoneSensor(15, "Game rotation vector", "fused orientation without the compass"),
        PhoneSensor(5, "Light", null),
        PhoneSensor(13, "Ambient temperature", null),
    )
}
