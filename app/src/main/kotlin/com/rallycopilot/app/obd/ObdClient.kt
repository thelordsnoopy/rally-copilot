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
    /** Human-readable one-liner: exactly what is happening with the dongle. */
    val statusText: String
        get() = when (state) {
            State.OFF -> "not in use"
            State.NO_DEVICE -> lastError ?: "no dongle paired - pair the ELM327 in Bluetooth settings"
            State.CONNECTING -> "connecting to dongle..."
            State.HANDSHAKING -> "talking to the car" + (detail?.let { " - $it" } ?: "...")
            State.LIVE -> "live" + (vin?.let { " - VIN $it" } ?: "")
            State.NO_DATA -> "dongle connected, but the car isn't answering - " +
                "check the ignition is on (engine running is safest)"
            State.RETRYING -> "retrying (attempt $attempts)" + (lastError?.let { " - last: $it" } ?: "")
            State.FAILED -> lastError ?: "failed"
        }

    /** The link came up but the ECU never answered — distinct from an IO failure. */
    private class SilentEcu : Exception("car not answering")

    /** Wall-clock budget for init + VIN + PID scan + probe, including one fallback. */
    private val SETUP_BUDGET_MS = 30_000L

    private val sppUuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    @Volatile private var socket: BluetoothSocket? = null
    /** Socket still inside connect(); disconnect() must be able to close this too,
     *  or a cancelled slow connect leaks an open RFCOMM link and the dongle stays
     *  unreachable until Bluetooth is toggled. */
    @Volatile private var connecting: BluetoothSocket? = null
    /** Bumped on every connect/disconnect; a stale connect coroutine sees the
     *  mismatch and closes its own socket instead of hijacking the field. */
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
     * [loadCache] fetches a remembered PID set for a key; [saveCache] persists one.
     */
    fun connect(
        address: String,
        loadCache: (String) -> Set<Int>? = { null },
        saveCache: (String, Set<Int>) -> Unit = { _, _ -> },
        /** Gear calibration for this car, keyed the same way as the PID cache. */
        loadGears: (String) -> String? = { null },
        saveGears: (String, String) -> Unit = { _, _ -> },
    ) {
        disconnect()
        val myGen = ++generation
        attempts = 0
        lastError = null
        state = State.CONNECTING
        job = scope.launch(Dispatchers.IO) {
          // Keep trying for the whole drive. A dongle can be plugged in late, the
          // ignition can come on after the app, and cheap clones routinely refuse
          // the first connect and take the second. Giving up after one attempt is
          // why this "just falls back to GPS".
          while (isActive && generation == myGen) {
            attempts++
            var s: BluetoothSocket? = null
            var silentEcu = false
            try {
                val adapter = BluetoothAdapter.getDefaultAdapter()
                if (adapter == null) { state = State.NO_DEVICE; lastError = "no Bluetooth adapter"; return@launch }
                state = State.CONNECTING
                val device = adapter.getRemoteDevice(address)
                s = device.createRfcommSocketToServiceRecord(sppUuid)
                connecting = s
                if (generation != myGen) return@launch // superseded before connect
                s.connect()
                connecting = null
                if (generation != myGen) return@launch // superseded during connect
                socket = s
                state = State.HANDSHAKING
                val out = s.outputStream
                val inp = s.inputStream

                // The whole handshake gets a wall-clock budget. Bluetooth streams cannot
                // be given read timeouts, so without this a dongle that goes quiet
                // mid-setup leaves the app sitting on "talking to the car" forever
                // instead of failing and retrying.
                val setupDeadline = System.currentTimeMillis() + SETUP_BUDGET_MS
                // ...but ONLY during setup. The same cmd() runs the polling loop for
                // the rest of the drive, where a fixed deadline would kill a perfectly
                // healthy link after thirty seconds.
                var setupPhase = true

                // One command in flight at a time. CRITICAL: drain stale bytes BEFORE
                // writing — a response that outlived its deadline (ATZ takes ~1 s on
                // genuine ELMs) otherwise leaves its tail + '>' in the stream and every
                // later command reads the PREVIOUS command's answer, forever.
                fun cmd(c: String, timeoutMs: Long = 900): String {
                    if (setupPhase && System.currentTimeMillis() > setupDeadline) {
                        throw java.io.IOException("dongle stopped responding during setup")
                    }
                    // BOUNDED drain: a chatty clone can hold available() above zero
                    // indefinitely, and an unbounded drain here hangs the connection
                    // on "talking to the car" with no way out.
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
                    val sb = StringBuilder()
                    val deadline = System.currentTimeMillis() + timeoutMs
                    while (System.currentTimeMillis() < deadline) {
                        while (inp.available() > 0) {
                            val ch = inp.read()
                            if (ch < 0) break
                            if (ch.toChar() == '>') return sb.toString()
                            sb.append(ch.toChar())
                        }
                        Thread.sleep(5)
                    }
                    return sb.toString()
                }

                // ATZ resets the chip and is the slowest command it has.
                for ((i, init) in Elm327.INIT.withIndex()) {
                    detail = "setup ${i + 1}/${Elm327.INIT.size}"
                    cmd(init, if (init == "ATZ") 2500 else 1200)
                    Thread.sleep(80)
                }

                // Car identity first: VIN via mode 09 when supported. The PID cache then
                // follows the CAR (swap dongles freely); dongle MAC is the fallback key.
                detail = "reading VIN"
                vin = Elm327.vin(cmd(Elm327.Pid.VIN, 1500))
                val cacheKey = vin?.let { "vin:" + it } ?: ("mac:" + address)
                // Gearing follows the car: restore it before the first sample so gear
                // calls work from the first corner, not after a minute of re-learning.
                gearInference.restore(loadGears(cacheKey))

                var supported = loadCache(cacheKey)
                if (supported.isNullOrEmpty()) {
                    detail = "asking what the car supports"
                    var scanned = Elm327.supportedPids(0x00, cmd(Elm327.Pid.SUPPORTED_01_20, 1500))
                    if (scanned.isEmpty()) {
                        cmd(Elm327.FALLBACK_PROTOCOL, 1200); Thread.sleep(120)
                        scanned = Elm327.supportedPids(0x00, cmd(Elm327.Pid.SUPPORTED_01_20, 1500))
                        // Re-try VIN on the fallback protocol too.
                        if (vin == null) {
                            vin = Elm327.vin(cmd(Elm327.Pid.VIN, 1500))
                        }
                    }
                    scanned = scanned + Elm327.supportedPids(0x40, cmd(Elm327.Pid.SUPPORTED_41_60, 1500))
                    if (scanned.isNotEmpty()) {
                        saveCache(vin?.let { "vin:" + it } ?: ("mac:" + address), scanned)
                    }
                    supported = scanned
                }
                // Does the car actually ANSWER? A completed handshake only proves the
                // dongle is talking. The ECU may be asleep, the forced protocol may be
                // wrong for this car, or a cached PID set may be stale — and declaring
                // "connected" here without checking is how the settings screen ends up
                // claiming live while no data ever arrives.
                fun probe(): Boolean {
                    detail = "reading engine data"
                    val sp = Elm327.speedKph(cmd(Elm327.Pid.SPEED, 1200))
                    val rp = Elm327.rpm(cmd(Elm327.Pid.RPM, 1200))
                    if (sp != null) speedKph = sp
                    if (rp != null) rpmV = rp
                    return sp != null || rp != null
                }

                var alive = probe()
                if (!alive) {
                    // Wrong protocol or a stale cache: drop to auto-detect and rescan
                    // from scratch rather than politely polling a silent bus forever.
                    detail = "trying protocol auto-detect"
                    cmd(Elm327.FALLBACK_PROTOCOL, 1500); Thread.sleep(200)
                    val rescanned = Elm327.supportedPids(0x00, cmd(Elm327.Pid.SUPPORTED_01_20, 1500)) +
                        Elm327.supportedPids(0x40, cmd(Elm327.Pid.SUPPORTED_41_60, 1500))
                    if (rescanned.isNotEmpty()) {
                        supported = rescanned
                        runCatching { saveCache(cacheKey, rescanned) }
                    }
                    alive = probe()
                }
                if (!alive) throw SilentEcu()

                // Derived AFTER the probe: a failed probe can rescan and replace the
                // supported set, and these flags must describe the set we ended up with.
                // On the E90 320d PID 0x11 is meaningless; 0x49 (accel pedal D) is the
                // real signal. Pick the best one the ECU actually reports.
                val pedalPid = Elm327.bestPedalPid(supported)
                // No pedal PID at all? Engine load is a workable proxy for commitment —
                // but a diesel cruises near 30-40% load, which would sit right on the
                // style detector's "relaxed" threshold. Remap so idle-ish load reads 0.
                val loadFallback = pedalPid == null && 0x04 in supported
                fun pedalFromLoad(load01: Double?): Double? =
                    load01?.let { ((it - 0.15) / 0.70).coerceIn(0.0, 1.0) }
                val hasAmbient = 0x46 in supported
                val hasMap = 0x0B in supported
                val hasFuel = 0x2F in supported

                connected = true
                state = State.LIVE
                lastError = null
                detail = null
                setupPhase = false

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
                    // The link can stay open while the car stops answering (ignition
                    // off, ECU asleep). Notice, rather than reporting "live" forever.
                    if (r == null && sp == null) dryCycles++ else dryCycles = 0
                    if (dryCycles >= 40) throw SilentEcu()
                    if (slowTick++ % 20 == 0) { // slow-changing values every ~20 cycles
                        coolantV = Elm327.coolantC(cmd(Elm327.Pid.COOLANT))
                        batteryVv = Elm327.batteryV(cmd(Elm327.Pid.VOLTAGE))
                        if (hasAmbient) ambientV = Elm327.ambientC(cmd(Elm327.Pid.AMBIENT))
                        if (hasFuel) fuelV = Elm327.fuelLevel01(cmd(Elm327.Pid.FUEL_LEVEL))
                        // Persist gearing as it improves (~every 2.5 s of polling).
                        if (gearInference.consumeDirty()) {
                            runCatching { saveGears(cacheKey, gearInference.serialise()) }
                        }
                    }
                    delay(120)
                }
            } catch (e: Exception) {
                if (e is SilentEcu) {
                    silentEcu = true
                } else {
                    lastError = when (e) {
                        is java.io.IOException -> e.message?.take(60) ?: "connect failed"
                        is SecurityException -> "Bluetooth permission denied"
                        else -> e.message?.take(60) ?: e.javaClass.simpleName
                    }
                }
            } finally {
                // Save whatever gearing was learned before the link dropped.
                runCatching {
                    val key = vin?.let { "vin:" + it } ?: ("mac:" + address)
                    if (gearInference.consumeDirty()) saveGears(key, gearInference.serialise())
                }
                runCatching { s?.close() }
                if (socket === s) socket = null
                if (connecting === s) connecting = null
                connected = false
                speedKph = null; rpmV = null; throttleV = null
            }
            if (!isActive || generation != myGen) break
            // Back off gently, capped, so a missing dongle costs almost nothing.
            // A silent ECU keeps its own status: "retrying" would hide the one fact
            // that actually tells the driver what to do (turn the ignition on).
            state = if (silentEcu) State.NO_DATA else State.RETRYING
            if (silentEcu) lastError = null
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
