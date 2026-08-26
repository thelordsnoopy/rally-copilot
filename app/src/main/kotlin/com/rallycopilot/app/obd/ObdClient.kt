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

    private val sppUuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private var socket: BluetoothSocket? = null
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
    ) {
        disconnect()
        job = scope.launch(Dispatchers.IO) {
            try {
                val adapter = BluetoothAdapter.getDefaultAdapter() ?: return@launch
                val device = adapter.getRemoteDevice(address)
                val s = device.createRfcommSocketToServiceRecord(sppUuid)
                s.connect()
                socket = s
                val out = s.outputStream
                val inp = s.inputStream

                fun cmd(c: String): String {
                    out.write((c + "\r").toByteArray())
                    out.flush()
                    val sb = StringBuilder()
                    val deadline = System.currentTimeMillis() + 900
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

                for (init in Elm327.INIT) { cmd(init); Thread.sleep(80) }

                // Car identity first: VIN via mode 09 when supported. The PID cache then
                // follows the CAR (swap dongles freely); dongle MAC is the fallback key.
                vin = Elm327.vin(cmd(Elm327.Pid.VIN))
                val cacheKey = vin?.let { "vin:" + it } ?: ("mac:" + address)

                var supported = loadCache(cacheKey)
                if (supported.isNullOrEmpty()) {
                    var scanned = Elm327.supportedPids(0x00, cmd(Elm327.Pid.SUPPORTED_01_20))
                    if (scanned.isEmpty()) {
                        cmd(Elm327.FALLBACK_PROTOCOL); Thread.sleep(120)
                        scanned = Elm327.supportedPids(0x00, cmd(Elm327.Pid.SUPPORTED_01_20))
                        // Re-try VIN on the fallback protocol too.
                        if (vin == null) {
                            vin = Elm327.vin(cmd(Elm327.Pid.VIN))
                        }
                    }
                    scanned = scanned + Elm327.supportedPids(0x40, cmd(Elm327.Pid.SUPPORTED_41_60))
                    if (scanned.isNotEmpty()) {
                        saveCache(vin?.let { "vin:" + it } ?: ("mac:" + address), scanned)
                    }
                    supported = scanned
                }
                // On the E90 320d PID 0x11 is meaningless; 0x49 (accel pedal D) is the
                // real signal. Pick the best one the ECU actually reports.
                val pedalPid = Elm327.bestPedalPid(supported)
                // No pedal PID at all? Engine load is a workable proxy for commitment.
                val loadFallback = pedalPid == null && 0x04 in supported
                val hasAmbient = 0x46 in supported
                val hasMap = 0x0B in supported
                val hasFuel = 0x2F in supported
                connected = true

                var slowTick = 0
                while (isActive && socket === s) {
                    speedKph = Elm327.speedKph(cmd(Elm327.Pid.SPEED))
                    rpmV = Elm327.rpm(cmd(Elm327.Pid.RPM))
                    throttleV = when {
                        pedalPid != null -> Elm327.percent01(pedalPid, cmd(pedalPid))
                        loadFallback -> Elm327.percent01(Elm327.Pid.ENGINE_LOAD, cmd(Elm327.Pid.ENGINE_LOAD))
                        else -> null
                    }
                    if (hasMap) mapV = Elm327.mapKpa(cmd(Elm327.Pid.MAP_KPA))
                    val r = rpmV; val sp = speedKph
                    if (r != null && sp != null) gearInference.addSample(r, sp / 3.6)
                    if (slowTick++ % 20 == 0) { // slow-changing values every ~20 cycles
                        coolantV = Elm327.coolantC(cmd(Elm327.Pid.COOLANT))
                        batteryVv = Elm327.batteryV(cmd(Elm327.Pid.VOLTAGE))
                        if (hasAmbient) ambientV = Elm327.ambientC(cmd(Elm327.Pid.AMBIENT))
                        if (hasFuel) fuelV = Elm327.fuelLevel01(cmd(Elm327.Pid.FUEL_LEVEL))
                    }
                    delay(120)
                }
            } catch (_: Exception) {
                // fall through to disconnect state
            } finally {
                connected = false
                speedKph = null; rpmV = null; throttleV = null
            }
        }
    }

    fun disconnect() {
        job?.cancel(); job = null
        try { socket?.close() } catch (_: Exception) {}
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
    override fun ambientC(): Int? = ambientV
    override fun mapKpa(): Int? = mapV
    override fun fuelLevel01(): Double? = fuelV
}
