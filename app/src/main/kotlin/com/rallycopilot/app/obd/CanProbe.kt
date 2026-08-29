package com.rallycopilot.app.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.content.Context
import com.rallycopilot.core.obd.DiagProbe
import com.rallycopilot.core.obd.Elm327
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * The experiment that asks the car's other modules what they know.
 *
 * Standard OBD gives one speed and no idea what any individual wheel is doing.
 * The DSC unit knows all four, plus its own yaw rate and lateral g; the steering
 * column knows the wheel angle. None of that is reachable over the PIDs the app
 * polls — it needs BMW's diagnostic addressing, and which identifiers answer on
 * a given car is not something to guess at from a desk.
 *
 * So this sweeps a bounded READ-ONLY space and writes down everything, exactly the
 * way the black box turned "the map cut off somewhere" into a fixed bug. The
 * output is a JSONL file pulled off the phone the same way:
 *
 *     adb pull /sdcard/Android/data/com.rallycopilot.app/files/canprobe
 *
 * RUN IT TWICE. Parked, it finds which modules answer and which identifiers
 * return data — but every wheel speed reads zero, so nothing can be told apart.
 * Rolling, it re-reads only what answered and interleaves the car's own OBD speed,
 * so a field that tracks road speed on four consecutive 16-bit values gives itself
 * away. [DiagProbe.findWheelSpeeds] does that sum on every reply as it arrives.
 *
 * IT OWNS THE DONGLE while it runs, which is the same one-connection rule that has
 * bitten this app before: the caller must refuse to start it during a drive, and
 * must not open the settings test link alongside it.
 */
@SuppressLint("MissingPermission") // BLUETOOTH_CONNECT checked by the caller
class CanProbe(private val scope: CoroutineScope, private val context: Context) {

    enum class Phase { IDLE, CONNECTING, DISCOVERING, SWEEPING, WATCHING, DONE, FAILED }

    @Volatile var phase: Phase = Phase.IDLE
        private set
    @Volatile var progress: String = ""
        private set
    /** Modules that answered anything at all. */
    @Volatile var alive: List<String> = emptyList()
        private set
    /** Identifiers that returned data, as "DSC 21A0". */
    @Volatile var answering: List<String> = emptyList()
        private set
    /** Best wheel-speed candidate seen so far, in words. */
    @Volatile var wheelSpeedHit: String? = null
        private set
    @Volatile var file: File? = null
        private set

    private val sppUuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var generation = 0
    private var job: Job? = null
    private var out: java.io.BufferedWriter? = null

    val running: Boolean get() = phase != Phase.IDLE && phase != Phase.DONE && phase != Phase.FAILED

    private fun rec(kind: String, fields: Map<String, Any?>) {
        val o = JSONObject()
        o.put("t", System.currentTimeMillis())
        o.put("k", kind)
        for ((k, v) in fields) o.put(k, v ?: JSONObject.NULL)
        runCatching { out?.write(o.toString()); out?.write("\n"); out?.flush() }
    }

    fun stop() {
        generation++
        phase = if (phase == Phase.FAILED) Phase.FAILED else Phase.DONE
        job?.cancel(); job = null
        runCatching { socket?.close() }; socket = null
        runCatching { out?.flush(); out?.close() }; out = null
    }

    /**
     * [address] the dongle's MAC. [rolling] true once the car is moving: skips the
     * slow identifier sweep and watches what already answered, against road speed.
     */
    fun start(address: String, rolling: Boolean) {
        stop()
        val myGen = ++generation
        phase = Phase.CONNECTING
        progress = "connecting to the dongle"
        wheelSpeedHit = null
        job = scope.launch(Dispatchers.IO) {
            var s: BluetoothSocket? = null
            try {
                val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "canprobe")
                dir.mkdirs()
                // Keep the last few runs only; these files are small but endless.
                dir.listFiles()?.sortedBy { it.lastModified() }?.dropLast(5)?.forEach { it.delete() }
                val f = File(dir, "probe-${System.currentTimeMillis()}.jsonl")
                file = f
                out = f.bufferedWriter()

                val adapter = BluetoothAdapter.getDefaultAdapter()
                    ?: run { fail("no Bluetooth adapter"); return@launch }
                s = adapter.getRemoteDevice(address).createRfcommSocketToServiceRecord(sppUuid)
                s.connect()
                if (generation != myGen) return@launch
                socket = s
                val inp = s.inputStream
                val outS = s.outputStream

                fun cmd(c: String, timeoutMs: Long = 700): String {
                    val drainUntil = System.currentTimeMillis() + 200
                    var drained = 0
                    while (inp.available() > 0 && drained < 4096 &&
                        System.currentTimeMillis() < drainUntil
                    ) { if (inp.read() < 0) break; drained++ }
                    outS.write((c + "\r").toByteArray()); outS.flush()
                    val sb = StringBuilder()
                    val deadline = System.currentTimeMillis() + timeoutMs
                    while (System.currentTimeMillis() < deadline) {
                        while (inp.available() > 0) {
                            val ch = inp.read()
                            if (ch < 0) break
                            if (ch.toChar() == '>') return sb.toString()
                            sb.append(ch.toChar())
                        }
                        Thread.sleep(4)
                    }
                    return sb.toString()
                }

                rec("probe_start", mapOf("rolling" to rolling, "device" to android.os.Build.MODEL))

                // ---- dongle setup, then the standard OBD protocol ----
                phase = Phase.CONNECTING
                for (init in Elm327.INIT) { cmd(init, if (init == "ATZ") 2500 else 1200); Thread.sleep(50) }
                cmd("ATSP6", 1500)          // CAN 11-bit 500k — D-CAN on an E-series
                cmd("ATST32", 500)          // ~200 ms per request: a sweep needs pace
                val ping = cmd("0100", 2500)
                rec("obd_ping", mapOf("raw" to ping.trim()))

                /** Read the car's own road speed over standard OBD, km/h. */
                fun readSpeedKph(): Int? {
                    cmd("ATCEA", 300)       // clear extended addressing
                    cmd("ATSH7DF", 300)     // functional OBD header
                    val raw = cmd("010D", 900)
                    return Elm327.speedKph(raw)
                }

                /** Send one read-only diagnostic request to a module. */
                fun ask(step: DiagProbe.Step, timeoutMs: Long = 700): DiagProbe.Reply {
                    require(DiagProbe.isReadOnly(step.requestHex))
                    cmd("ATSH6F1", 300)
                    cmd("ATCEA" + DiagProbe.hex(step.module.addr), 300)
                    val raw = cmd(step.requestHex, timeoutMs)
                    val reply = DiagProbe.parseReply(raw, step.requestHex)
                    rec("ask", mapOf(
                        "mod" to step.module.name, "addr" to step.module.addr,
                        "req" to step.requestHex, "raw" to raw.trim().replace('\r', ' '),
                        "answer" to reply.answer.name,
                        "nrc" to reply.nrc, "why" to step.why,
                        "payload" to reply.payload.joinToString("") { DiagProbe.hex(it) },
                    ))
                    return reply
                }

                // ---- phase 1: who is home ----
                phase = Phase.DISCOVERING
                val live = LinkedHashMap<Int, DiagProbe.Module>()
                for ((i, step) in DiagProbe.discoverySteps().withIndex()) {
                    if (generation != myGen) return@launch
                    progress = "knocking on doors ${i + 1}/${DiagProbe.discoverySteps().size}"
                    val r = ask(step, 900)
                    // Even a refusal proves the module is there and listening.
                    if (r.answer != DiagProbe.Answer.SILENT) live[step.module.addr] = step.module
                }
                alive = live.values.map { "${it.name} (0x${DiagProbe.hex(it.addr)})" }
                rec("discovery", mapOf("alive" to alive.joinToString(", ")))
                if (live.isEmpty()) {
                    progress = "no module answered the BMW addressing — this car may not speak it"
                    phase = Phase.DONE
                    rec("probe_end", mapOf("result" to "no modules"))
                    stop(); return@launch
                }

                val hits = ArrayList<DiagProbe.Step>()

                if (!rolling) {
                    // ---- phase 2: what will each answering module tell us ----
                    phase = Phase.SWEEPING
                    for (m in live.values) {
                        val steps = DiagProbe.sweepSteps(m)
                        for ((i, step) in steps.withIndex()) {
                            if (generation != myGen) return@launch
                            progress = "${m.name}: identifier ${i + 1}/${steps.size}"
                            val r = ask(step, 600)
                            if (r.answer == DiagProbe.Answer.DATA && r.payload.isNotEmpty()) {
                                hits += step
                                answering = hits.map { "${it.module.name} ${it.requestHex}" }
                            }
                        }
                    }
                    rec("sweep_done", mapOf("hits" to answering.joinToString(", ")))
                    progress = "${hits.size} identifiers returned data — now drive and run it again"
                } else {
                    // ---- phase 3: watch the bytes move against road speed ----
                    phase = Phase.WATCHING
                    // Re-establish which identifiers answer, cheaply, then loop them.
                    for (m in live.values) {
                        for (step in DiagProbe.sweepSteps(m)) {
                            if (generation != myGen) return@launch
                            if (ask(step, 350).let {
                                    it.answer == DiagProbe.Answer.DATA && it.payload.isNotEmpty()
                                }
                            ) hits += step
                        }
                    }
                    answering = hits.map { "${it.module.name} ${it.requestHex}" }
                    rec("watch_begin", mapOf("count" to hits.size))
                    var round = 0
                    while (isActive && generation == myGen) {
                        val kph = readSpeedKph()
                        rec("speed", mapOf("kph" to kph))
                        round++
                        progress = "watching ${hits.size} identifiers at ${kph ?: "?"} km/h (round $round)"
                        for (step in hits) {
                            if (generation != myGen) return@launch
                            val r = ask(step, 400)
                            if (r.answer != DiagProbe.Answer.DATA) continue
                            if (kph != null) {
                                val guesses = DiagProbe.findWheelSpeeds(r.payload, kph.toDouble())
                                if (guesses.isNotEmpty()) {
                                    val g = guesses.first()
                                    val hit = "${step.module.name} ${step.requestHex} " +
                                        "@byte${g.offset} x${g.scaleKphPerBit} = " +
                                        g.kph.joinToString("/") { "%.0f".format(it) } + " km/h"
                                    wheelSpeedHit = hit
                                    rec("wheelspeed_candidate", mapOf(
                                        "mod" to step.module.name, "req" to step.requestHex,
                                        "offset" to g.offset, "bigEndian" to g.bigEndian,
                                        "scale" to g.scaleKphPerBit,
                                        "kph" to g.kph.joinToString(","),
                                        "refKph" to kph, "spread" to g.spreadKph,
                                    ))
                                }
                            }
                        }
                    }
                }
                rec("probe_end", mapOf("result" to "complete"))
                phase = Phase.DONE
                stop()
            } catch (e: Exception) {
                if (generation == myGen) fail(e.message ?: e.javaClass.simpleName)
            } finally {
                runCatching { s?.close() }
            }
        }
    }

    private fun fail(why: String) {
        progress = why
        phase = Phase.FAILED
        rec("probe_end", mapOf("result" to "failed", "why" to why))
        runCatching { out?.flush(); out?.close() }; out = null
    }
}
