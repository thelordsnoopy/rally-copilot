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
    @Volatile var connected = false
        private set

    /** Find a likely ELM327 among bonded devices (named OBD/ELM/V-Link etc.). */
    fun findBonded(): String? {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return null
        return adapter.bondedDevices.firstOrNull { d ->
            val n = (d.name ?: "").lowercase()
            "obd" in n || "elm" in n || "vlink" in n || "v-link" in n || "obdii" in n
        }?.address
    }

    fun connect(address: String) {
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

                // Probe supported PIDs. If CAN (ATSP6) got nothing, fall back to auto
                // protocol detection once — covers pre-CAN cars and odd clones.
                var supported = Elm327.supportedPids(0x00, cmd(Elm327.Pid.SUPPORTED_01_20))
                if (supported.isEmpty()) {
                    cmd(Elm327.FALLBACK_PROTOCOL); Thread.sleep(120)
                    supported = Elm327.supportedPids(0x00, cmd(Elm327.Pid.SUPPORTED_01_20))
                }
                supported = supported + Elm327.supportedPids(0x40, cmd(Elm327.Pid.SUPPORTED_41_60))
                // On the E90 320d PID 0x11 is meaningless; 0x49 (accel pedal D) is the
                // real signal. Pick the best one the ECU actually reports.
                val pedalPid = Elm327.bestPedalPid(supported)
                connected = true

                var slowTick = 0
                while (isActive && socket === s) {
                    speedKph = Elm327.speedKph(cmd(Elm327.Pid.SPEED))
                    rpmV = Elm327.rpm(cmd(Elm327.Pid.RPM))
                    throttleV = pedalPid?.let { Elm327.percent01(it, cmd(it)) }
                    val r = rpmV; val sp = speedKph
                    if (r != null && sp != null) gearInference.addSample(r, sp / 3.6)
                    if (slowTick++ % 20 == 0) { // coolant/voltage every ~20 cycles
                        coolantV = Elm327.coolantC(cmd(Elm327.Pid.COOLANT))
                        batteryVv = Elm327.batteryV(cmd(Elm327.Pid.VOLTAGE))
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
}
