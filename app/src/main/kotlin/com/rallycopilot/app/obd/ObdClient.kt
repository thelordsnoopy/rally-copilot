package com.rallycopilot.app.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import com.rallycopilot.core.engine.VehicleData
import com.rallycopilot.core.obd.Elm327
import com.rallycopilot.core.obd.GearInference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * ELM327 over classic Bluetooth SPP. Owns the socket and the polling loop;
 * all byte-level parsing lives in :core (Elm327). Everything degrades to null
 * when the dongle is absent — the app never requires it.
 *
 * Two separate failures live here and must never be conflated:
 *
 *  - the BLUETOOTH LINK to the dongle, which either opens or does not, and
 *  - the OBD CONVERSATION with the car, which can fail while the link is perfect
 *    (ignition off, ECU asleep, wrong protocol for this car).
 *
 * Only a link failure closes the socket. A car that will not answer keeps the
 * link open and keeps hunting for a protocol over it — dropping and re-pairing
 * Bluetooth every few seconds achieves nothing except making the dongle blink.
 */
@SuppressLint("MissingPermission") // BLUETOOTH_CONNECT checked by caller before connect()
class ObdClient(private val scope: CoroutineScope) : VehicleData {

    /** What the OBD link is doing, in words the settings screen can show. */
    enum class State { OFF, NO_DEVICE, CONNECTING, HANDSHAKING, LIVE, NO_DATA, RETRYING, FAILED }

    @Volatile var state: State = State.OFF
        private set
    /** Why the last attempt failed, for the settings screen. */
    @Volatile var lastError: String? = null
        private set
    @Volatile var attempts: Int = 0
        private set
    /** What the handshake is doing right now, so a slow dongle looks slow, not stuck. */
    @Volatile private var detail: String? = null
    /** The protocol that actually worked, once one does. */
    @Volatile var protocolLabel: String? = null
        private set

    /** Rolling command/response log — the only way to diagnose a stubborn car. */
    private val logLines = ArrayDeque<String>()
    fun diagnosticLog(): List<String> = synchronized(logLines) { logLines.toList() }
    private fun log(line: String) = synchronized(logLines) {
        logLines += line
        while (logLines.size > 60) logLines.removeFirst()
    }

    /** Human-readable one-liner: exactly what is happening with the dongle. */
    val statusText: String
        get() = when (state) {
            State.OFF -> "not in use"
            State.NO_DEVICE -> lastError ?: "no dongle paired - pair the ELM327 in Bluetooth settings"
            State.CONNECTING -> "connecting to dongle..."
            State.HANDSHAKING -> "talking to the car" + (detail?.let { " - $it" } ?: "...")
            State.LIVE -> "live" + (protocolLabel?.let { " on $it" } ?: "") +
                (vin?.let { " - VIN $it" } ?: "")
            State.NO_DATA -> "dongle connected, car not answering" +
                (lastError?.let { " - $it" } ?: "") + " - still trying"
            State.RETRYING -> "reconnecting (attempt $attempts)" + (lastError?.let { " - $it" } ?: "")
            State.FAILED -> lastError ?: "failed"
        }

    /** Wall-clock budget for one full protocol sweep before starting it over. */
    private val sppUuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    @Volatile private var socket: BluetoothSocket? = null
    /** Socket still inside connect(); disconnect() must be able to close this too. */
    @Volatile private var connecting: BluetoothSocket? = null
    /** Bumped on every connect/disconnect; a stale coroutine sees the mismatch. */
    @Volatile private var generation = 0
    private var job: Job? = null
    val gearInference = GearInference()

    @Volatile private var speedKph: Int? = null
    @Volatile private var rpmV: Int? = null
    @Volatile private var throttleV: Double? = null
    @Volatile private var coolantV: Int? = null
    @Volatile private var batteryVv: Double? = null
    @Volatile private var ambientV: Int? = null
    @Volatile private var mapV: Int? = null
    @Volatile private var fuelV: Double? = null
    @Volatile var connected = false
        private set

    /**
     * Everything this car said it supports, decoded into names — written to the
     * black box once per drive so there is a record of what the car offers rather
     * than only what the app happened to ask for.
     */
    @Volatile var sensorReport: List<Map<String, Any?>> = emptyList()
        private set
    /** Most recent value of each swept sensor, by PID. */
    private val latestSweep = java.util.concurrent.ConcurrentHashMap<Int, Double>()
    fun sweptValues(): Map<Int, Double> = latestSweep.toMap()
    /**
     * Called for every swept reading: the PID, the RAW response, and the decoded
     * value when the catalogue knows how. Raw is always passed even when the app
     * cannot make sense of it — an unknown PID's bytes are still the car telling
     * us something, and a trace can be read later by someone who can work it out.
     */
    @Volatile var onSensor: ((pid: Int, raw: String, value: Double?) -> Unit)? = null

    private var sweepList: List<Int> = emptyList()
    private var sweepIdx = 0
    /** The bitmap queries themselves are not sensors; never sweep them. */
    private val SUPPORT_BITMAPS = setOf(0x00, 0x20, 0x40, 0x60, 0x80, 0xA0, 0xC0)

    /** Name every supported PID, note whether we can read it, and build the sweep. */
    private fun buildSensorReport(pids: Set<Int>): List<Map<String, Any?>> {
        val cat = com.rallycopilot.core.obd.SensorCatalog
        // Already polled by the drive proper — no point asking twice a second.
        val alreadyPolled = setOf(0x0C, 0x0D, 0x05, 0x0B, 0x2F, 0x46, 0x11, 0x49, 0x04)
        // Sweep EVERYTHING the car claims, decodable or not. A PID the catalogue
        // has never heard of is exactly the one worth capturing raw.
        sweepList = pids.sorted().filter { it !in alreadyPolled && it !in SUPPORT_BITMAPS }
        sweepIdx = 0
        return pids.sorted().map { pid ->
            val s = cat.BY_PID[pid]
            mapOf(
                "pid" to "0x%02X".format(pid),
                "name" to (s?.name ?: "unknown to the catalogue"),
                "unit" to s?.unit,
                "readable" to (s?.decodable ?: false),
                "swept" to (pid in sweepList),
                "useful" to s?.useful,
            )
        }
    }
    /** VIN read over mode 09 at connect, when the ECU offers it. */
    @Volatile var vin: String? = null
        private set

    /**
     * Record that no attempt will be made, and why. Without this a missing dongle,
     * a denied permission and Bluetooth simply being off all looked identical:
     * nothing happened and the status stayed "not in use".
     */
    fun reportUnavailable(reason: String) {
        state = State.NO_DEVICE
        lastError = reason
    }

    /** Find a likely ELM327 among bonded devices (named OBD/ELM/V-Link etc.). */
    fun findBonded(): String? {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return null
        return adapter.bondedDevices.firstOrNull { d ->
            val n = (d.name ?: "").lowercase()
            "obd" in n || "elm" in n || "vlink" in n || "v-link" in n || "obdii" in n
        }?.address
    }

    /**
     * PID cache is keyed by car identity: "vin:<VIN>" when the ECU reports one (cache
     * follows the car), else "mac:<dongle address>" (cache follows the dongle).
     */
    fun connect(
        address: String,
        loadCache: (String) -> Set<Int>? = { null },
        saveCache: (String, Set<Int>) -> Unit = { _, _ -> },
        /** Gear calibration for this car, keyed the same way as the PID cache. */
        loadGears: (String) -> String? = { null },
        saveGears: (String, String) -> Unit = { _, _ -> },
        /** The protocol that worked last time, so the sweep is paid for once. */
        loadProto: (String) -> String? = { null },
        saveProto: (String, String) -> Unit = { _, _ -> },
    ) {
        disconnect()
        val myGen = ++generation
        attempts = 0
        lastError = null
        state = State.CONNECTING
        job = scope.launch(Dispatchers.IO) {
          // Outer loop = the BLUETOOTH link. Only an IO failure gets us back here.
          while (isActive && generation == myGen) {
            attempts++
            var s: BluetoothSocket? = null
            try {
                val adapter = BluetoothAdapter.getDefaultAdapter()
                if (adapter == null) {
                    state = State.NO_DEVICE; lastError = "no Bluetooth adapter"; return@launch
                }
                if (attempts > 1) state = State.RETRYING else state = State.CONNECTING
                val device = adapter.getRemoteDevice(address)
                s = device.createRfcommSocketToServiceRecord(sppUuid)
                connecting = s
                if (generation != myGen) return@launch
                s.connect()
                connecting = null
                if (generation != myGen) return@launch
                socket = s
                state = State.HANDSHAKING
                log("--- bluetooth link open (attempt $attempts) ---")
                val out = s.outputStream
                val inp = s.inputStream

                /**
                 * One command in flight at a time. Drains stale bytes BEFORE writing —
                 * a response that outlived its deadline otherwise leaves its tail plus
                 * the '>' prompt in the stream and every later command reads the
                 * PREVIOUS command's answer. The drain is BOUNDED: a chatty clone can
                 * hold available() above zero forever.
                 */
                var owedPrompt = false
                fun cmd(c: String, timeoutMs: Long = 900): String {
                    // A command that hit its deadline did NOT consume its answer: the
                    // ELM is still going to send it, plus the '>' that ends it. The
                    // drain below only removes what has ALREADY landed, so when the
                    // reply is merely late — the common case, since the deadline is
                    // what we ran out of — available() reads zero, the drain falls
                    // straight through, and the previous command's answer is returned
                    // as this one's. Measured over drives 56-58 that mis-attributed
                    // one sweep reply in five: 0x0F and 0x21 both "answered" 410B.
                    // The only way back into step is to wait for the prompt we are
                    // owed and throw away everything up to it.
                    if (owedPrompt) {
                        val until = System.currentTimeMillis() + 400
                        while (System.currentTimeMillis() < until) {
                            if (inp.available() > 0) {
                                val ch = inp.read()
                                if (ch < 0) break
                                if (ch.toChar() == '>') break
                            } else Thread.sleep(2)
                        }
                        owedPrompt = false
                    }
                    val drainUntil = System.currentTimeMillis() + 250
                    var drained = 0
                    while (inp.available() > 0 && drained < 4096 &&
                        System.currentTimeMillis() < drainUntil
                    ) {
                        if (inp.read() < 0) break
                        drained++
                    }
                    out.write((c + "\r").toByteArray())
                    out.flush()
                    owedPrompt = true
                    val sb = StringBuilder()
                    val deadline = System.currentTimeMillis() + timeoutMs
                    while (System.currentTimeMillis() < deadline) {
                        while (inp.available() > 0) {
                            val ch = inp.read()
                            if (ch < 0) break
                            if (ch.toChar() == '>') {
                                owedPrompt = false
                                log("$c -> ${sb.toString().trim().replace('\r', ' ')}")
                                return sb.toString()
                            }
                            sb.append(ch.toChar())
                        }
                        Thread.sleep(5)
                    }
                    log("$c -> [timeout] ${sb.toString().trim().replace('\r', ' ')}")
                    return sb.toString()
                }

                // ---- dongle setup (no protocol yet) ----
                for ((i, init) in Elm327.INIT.withIndex()) {
                    detail = "setup ${i + 1}/${Elm327.INIT.size}"
                    cmd(init, if (init == "ATZ") 2500 else 1200)
                    Thread.sleep(60)
                }
                // Generous per-request timing while hunting; tightened once it works.
                cmd(Elm327.SLOW_TIMING, 1200)

                val macKey = "mac:$address"
                var cacheKey = macKey

                /**
                 * Ask the car for supported PIDs on a given protocol. A real answer
                 * proves the protocol; the ELM's own complaint is captured verbatim
                 * because it is the single most useful diagnostic the hardware gives.
                 */
                fun tryProtocol(p: Elm327.Protocol): Set<Int>? {
                    detail = "trying ${p.label}"
                    cmd(p.cmd, 1500)
                    Thread.sleep(120)
                    val raw = cmd(Elm327.Pid.SUPPORTED_01_20, p.probeMs)
                    val pids = Elm327.supportedPids(0x00, raw)
                    if (pids.isNotEmpty()) return pids
                    Elm327.elmError(raw)?.let { lastError = it }
                    return null
                }

                // ---- find a protocol the car answers on ----
                var supported: Set<Int>? = null
                val remembered = loadProto(macKey)
                if (remembered != null) {
                    Elm327.PROTOCOL_SWEEP.firstOrNull { it.cmd == remembered }?.let { p ->
                        supported = tryProtocol(p)
                        if (supported != null) protocolLabel = p.label
                    }
                }
                if (supported == null) {
                    for (p in Elm327.PROTOCOL_SWEEP) {
                        if (!isActive || generation != myGen) return@launch
                        supported = tryProtocol(p)
                        if (supported != null) {
                            protocolLabel = p.label
                            runCatching { saveProto(macKey, p.cmd) }
                            break
                        }
                    }
                }

                if (supported == null) {
                    // The LINK is fine — the car simply is not answering on anything.
                    // Keep the socket and keep trying; do NOT drop Bluetooth, which
                    // only makes the dongle blink and slows down the moment the
                    // ignition finally comes on.
                    connected = false
                    state = State.NO_DATA
                    detail = null
                    log("no protocol answered; holding the link and retrying")
                    var quietRounds = 0
                    while (isActive && generation == myGen && supported == null) {
                        delay(5_000)
                        quietRounds++
                        detail = "retry $quietRounds"
                        for (p in Elm327.PROTOCOL_SWEEP) {
                            if (!isActive || generation != myGen) return@launch
                            supported = tryProtocol(p)
                            if (supported != null) {
                                protocolLabel = p.label
                                runCatching { saveProto(macKey, p.cmd) }
                                break
                            }
                        }
                    }
                    if (supported == null) return@launch
                }

                // ---- the car is talking ----
                state = State.HANDSHAKING
                detail = "reading VIN"
                vin = Elm327.vin(cmd(Elm327.Pid.VIN, 2500))
                cacheKey = vin?.let { "vin:$it" } ?: macKey
                gearInference.restore(loadGears(cacheKey))
                loadCache(cacheKey)?.takeIf { it.isNotEmpty() }?.let { cached ->
                    supported = supported!! + cached
                }
                // Ask for the WHOLE supported-PID space. The app used to query two
                // of the seven bitmaps, so everything from 0x21-0x40 and past 0x60
                // was invisible — engine oil temperature and the torque pair among
                // them. Each bitmap also advertises whether the next one exists, so
                // a car that stops at 0x40 costs one extra request, not six.
                for ((base, req) in com.rallycopilot.core.obd.SensorCatalog.SUPPORT_QUERIES) {
                    if (base == 0x00) continue // already asked, that is how we got here
                    val more = Elm327.supportedPids(base, cmd(req, 3000))
                    if (more.isEmpty()) break
                    supported = supported!! + more
                }
                runCatching { saveCache(cacheKey, supported!!) }
                // Which protocol did the ELM actually settle on? "auto-detect" is
                // what we asked for, not what it found, and the difference decides
                // whether a car can be talked to any other way. ATDPN answers with
                // the number ("6", or "A6" when auto-detect chose it).
                runCatching {
                    val n = cmd("ATDPN", 900).trim().replace("\r", "").removePrefix("A")
                    if (n.isNotEmpty()) {
                        Elm327.PROTOCOL_SWEEP.firstOrNull { it.cmd == "ATSP$n" }?.let {
                            protocolLabel = it.label
                        } ?: run { protocolLabel = "protocol $n" }
                    }
                }
                // Publish the car's own sensor list: this is the answer to "what does
                // this car actually know", and it is worth recording once per drive.
                sensorReport = buildSensorReport(supported!!)

                val pids = supported!!
                // On the E90 320d PID 0x11 is meaningless; 0x49 (accel pedal D) is the
                // real signal. Pick the best one the ECU actually reports.
                val pedalPid = Elm327.bestPedalPid(pids)
                // No pedal PID at all? Engine load is a workable proxy for commitment —
                // but a diesel cruises near 30-40% load, which would sit right on the
                // style detector's "relaxed" threshold. Remap so idle-ish load reads 0.
                val loadFallback = pedalPid == null && 0x04 in pids
                fun pedalFromLoad(load01: Double?): Double? =
                    load01?.let { ((it - 0.15) / 0.70).coerceIn(0.0, 1.0) }
                val hasAmbient = 0x46 in pids
                val hasMap = 0x0B in pids
                val hasFuel = 0x2F in pids

                cmd(Elm327.FAST_TIMING, 1200) // snappy polling now the protocol is known
                connected = true
                state = State.LIVE
                lastError = null
                detail = null
                log("LIVE on ${protocolLabel}, ${pids.size} PIDs")

                var dryCycles = 0
                var slowTick = 0
                while (isActive && generation == myGen && socket === s) {
                    speedKph = Elm327.speedKph(cmd(Elm327.Pid.SPEED))
                    rpmV = Elm327.rpm(cmd(Elm327.Pid.RPM))
                    throttleV = when {
                        pedalPid != null -> Elm327.percent01(pedalPid, cmd(pedalPid))
                        loadFallback -> pedalFromLoad(
                            Elm327.percent01(Elm327.Pid.ENGINE_LOAD, cmd(Elm327.Pid.ENGINE_LOAD))
                        )
                        else -> null
                    }
                    if (hasMap) mapV = Elm327.mapKpa(cmd(Elm327.Pid.MAP_KPA))
                    val r = rpmV; val sp = speedKph
                    if (r != null && sp != null) gearInference.addSample(r, sp / 3.6)
                    // The car can stop answering with the link still open (ignition
                    // off). Notice it, but hold the link and wait rather than
                    // tearing Bluetooth down.
                    if (r == null && sp == null) dryCycles++ else dryCycles = 0
                    if (dryCycles >= 40) {
                        connected = false
                        state = State.NO_DATA
                        lastError = "stopped answering"
                        log("car went quiet; holding the link")
                        dryCycles = 0
                        delay(3_000)
                        // Re-probe on the SAME socket; the ECU may just have been asleep.
                        if (Elm327.supportedPids(0x00, cmd(Elm327.Pid.SUPPORTED_01_20, 6000)).isNotEmpty()) {
                            connected = true
                            state = State.LIVE
                            lastError = null
                            log("car back")
                        }
                        continue
                    }
                    // ---- background sensor sweep ----
                    // One extra PID per cycle, round-robin over everything the car
                    // says it supports that the drive does not already poll. At ~8
                    // cycles a second this walks a 40-PID list in five seconds and
                    // costs one request in nine, so speed and rpm keep their pace.
                    if (sweepList.isNotEmpty()) {
                        val pid = sweepList[sweepIdx % sweepList.size]
                        sweepIdx++
                        val raw = cmd("01%02X".format(pid), 400)
                        val v = com.rallycopilot.core.obd.SensorCatalog.decode(pid, raw)
                        if (v != null) latestSweep[pid] = v
                        // Log it either way: a PID the catalogue cannot decode is
                        // still worth its bytes.
                        onSensor?.invoke(pid, raw.trim(), v)
                    }
                    if (slowTick++ % 20 == 0) { // slow-changing values every ~20 cycles
                        coolantV = Elm327.coolantC(cmd(Elm327.Pid.COOLANT))
                        batteryVv = Elm327.batteryV(cmd(Elm327.Pid.VOLTAGE))
                        if (hasAmbient) ambientV = Elm327.ambientC(cmd(Elm327.Pid.AMBIENT))
                        if (hasFuel) fuelV = Elm327.fuelLevel01(cmd(Elm327.Pid.FUEL_LEVEL))
                        if (gearInference.consumeDirty()) {
                            runCatching { saveGears(cacheKey, gearInference.serialise()) }
                        }
                    }
                    delay(120)
                }
            } catch (e: Exception) {
                lastError = when (e) {
                    is SecurityException -> "Bluetooth permission denied"
                    is java.io.IOException -> e.message?.take(60) ?: "link dropped"
                    else -> e.message?.take(60) ?: e.javaClass.simpleName
                }
                log("link error: $lastError")
            } finally {
                runCatching {
                    val key = vin?.let { "vin:$it" } ?: "mac:$address"
                    if (gearInference.consumeDirty()) saveGears(key, gearInference.serialise())
                }
                runCatching { s?.close() }
                if (socket === s) socket = null
                if (connecting === s) connecting = null
                connected = false
                speedKph = null; rpmV = null; throttleV = null
            }
            if (!isActive || generation != myGen) break
            // Only a genuine link failure reaches here. Back off gently, capped.
            state = State.RETRYING
            delay(when {
                attempts < 3 -> 3_000L
                attempts < 6 -> 8_000L
                else -> 20_000L
            })
          }
        }
    }

    fun disconnect() {
        generation++
        state = State.OFF
        detail = null
        job?.cancel(); job = null
        try { connecting?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        connecting = null
        socket = null
        connected = false
    }

    // ---- VehicleData ----
    override fun obdSpeedMps(): Double? = speedKph?.let { it / 3.6 }
    override fun rpm(): Int? = rpmV
    override fun throttle01(): Double? = throttleV
    override fun coolantC(): Int? = coolantV
    override fun batteryV(): Double? = batteryVv
    override fun currentGear(): Int? {
        val r = rpmV ?: return null
        val s = speedKph ?: return null
        return gearInference.currentGear(r, s / 3.6)
    }
    override fun linkSilent(): Boolean = state == State.NO_DATA
    override fun ambientC(): Int? = ambientV
    override fun mapKpa(): Int? = mapV
    override fun fuelLevel01(): Double? = fuelV
}
