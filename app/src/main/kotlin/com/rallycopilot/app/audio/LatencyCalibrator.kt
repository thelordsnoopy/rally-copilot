package com.rallycopilot.app.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import kotlin.math.roundToInt

/**
 * Measures how long the car actually takes to make a sound.
 *
 * Every pacenote is scheduled backwards from the corner: the note has to FINISH a
 * beat before the braking point, so the whole chain needs a number for how late
 * the audio is. That number was a guess (Bluetooth SBC to a head unit is
 * documented as 150-250 ms), and a guess is a poor thing to hang corner timing on
 * when the phone has a microphone and the car has speakers.
 *
 * So: play a short chirp through the same audio path the co-driver uses, listen
 * for it, and time the round trip. Once per drive, before setting off.
 *
 * The chirp is deliberately a 2.2 -> 3.6 kHz sweep. High enough to sit above
 * engine and road noise, short enough to be a "zip" rather than a nuisance, and
 * band-limited so the detector can ignore everything a car normally produces.
 */
object LatencyCalibrator {

    private const val SAMPLE_RATE = 44_100
    private const val CHIRP_MS = 130
    private const val LEAD_SILENCE_MS = 250     // noise floor is measured in here
    private const val LISTEN_MS = 1_400
    /** Sanity bounds. Anything outside these is a bad measurement, not a fast car. */
    private const val MIN_PLAUSIBLE_MS = 30L
    private const val MAX_PLAUSIBLE_MS = 900L
    /**
     * Bluetooth cannot physically be this quick: A2DP encode + buffer + head unit
     * decode is 100 ms at the very best, 150-250 ms typically. Drive 42 "measured"
     * 77 ms on a bluetooth route — which is the phone's OWN speaker's number, and
     * the tell for what actually happens: starting the microphone makes many
     * devices SUSPEND A2DP (echo avoidance), so the chirp quietly falls back to
     * the phone speaker while the route check, taken before recording began,
     * still says bluetooth. That 77 then timed every call of the drive.
     */
    private const val MIN_PLAUSIBLE_BLUETOOTH_MS = 110L

    data class Result(
        val latencyMs: Long?,
        val message: String,
        /** Ambient cabin loudness while listening, 0..1. Useful for auto-volume. */
        val noiseLevel: Double = 0.0,
    )

    /** The "cool noise": a short rising sweep with a fast attack and soft tail. */
    fun chirp(): ShortArray = com.rallycopilot.core.audio.ChirpDetect.chirp(SAMPLE_RATE, CHIRP_MS)

    fun hasMic(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Measure up to [attempts] times and take the MEDIAN of whatever succeeded.
     *
     * One shot at drive start can land on a pothole, a passing lorry or the door
     * shutting. Three cheap looks and a median is far harder to fool than a single
     * reading, and this number is subtracted from every braking point of the drive.
     */
    fun measureBest(
        context: Context, attrs: AudioAttributes,
        attempts: Int = 3, expectBluetooth: Boolean = false,
    ): Result {
        val good = ArrayList<Long>(attempts)
        var last: Result? = null
        repeat(attempts) {
            val r = measure(context, attrs, expectBluetooth)
            last = r
            r.latencyMs?.let { good += it }
            // A clear win early is enough; do not keep chirping at the driver.
            if (good.size >= 2 && good.max() - good.min() <= 40) return@repeat
        }
        if (good.isEmpty()) return last ?: Result(null, "measurement failed")
        good.sort()
        val median = good[good.size / 2]
        return Result(median, "measured $median ms (best of ${good.size})", last?.noiseLevel ?: 0.0)
    }

    /**
     * Blocking measurement — call off the main thread. Returns null latency (with a
     * reason) rather than a wrong number whenever the measurement is not trustworthy.
     */
    fun measure(context: Context, attrs: AudioAttributes, expectBluetooth: Boolean = false): Result {
        if (!hasMic(context)) return Result(null, "microphone permission not granted")

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return Result(null, "no usable microphone")

        val recorder = try {
            @Suppress("MissingPermission")
            AudioRecord(
                // UNPROCESSED where available: AGC and noise suppression would fight
                // the very thing being measured.
                MediaRecorder.AudioSource.UNPROCESSED,
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, SAMPLE_RATE) // ~1 s of headroom
            ).takeIf { it.state == AudioRecord.STATE_INITIALIZED }
                ?: @Suppress("MissingPermission") AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuf, SAMPLE_RATE)
                )
        } catch (_: Exception) {
            return Result(null, "microphone unavailable")
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { recorder.release() }
            return Result(null, "microphone unavailable")
        }

        val tone = chirp()
        val track = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(tone.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        return try {
            runCatching { track.setVolume(AudioTrack.getMaxVolume()) }
            track.write(tone, 0, tone.size)
            val total = SAMPLE_RATE * (LEAD_SILENCE_MS + LISTEN_MS) / 1000
            val captured = ShortArray(total)
            recorder.startRecording()

            // Record the lead-in first: it establishes the noise floor, and the chirp
            // must not begin until the recorder is genuinely running.
            var filled = 0
            val leadSamples = SAMPLE_RATE * LEAD_SILENCE_MS / 1000
            while (filled < leadSamples) {
                val n = recorder.read(captured, filled, leadSamples - filled)
                if (n <= 0) break
                filled += n
            }

            val playAtSample = filled
            track.play()
            // Where is the chirp ACTUALLY going? The route was checked before the
            // recorder started, and starting the recorder is exactly what makes
            // some devices suspend A2DP and fall back to the phone speaker.
            var routed: android.media.AudioDeviceInfo? = track.routedDevice

            while (filled < total) {
                val n = recorder.read(captured, filled, total - filled)
                if (n <= 0) break
                filled += n
                track.routedDevice?.let { routed = it }
            }
            recorder.stop()

            val routedType = routed?.type
            val onBluetooth = routedType == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                routedType == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            if (expectBluetooth && routedType != null && !onBluetooth) {
                return Result(
                    null,
                    "chirp fell back to the phone (route type $routedType) while the mic " +
                        "recorded — not the car's latency",
                )
            }
            analyse(captured, filled, playAtSample, expectBluetooth)
        } catch (e: Exception) {
            Result(null, e.message?.take(50) ?: "measurement failed")
        } finally {
            runCatching { track.stop() }
            runCatching { track.release() }
            runCatching { recorder.release() }
        }
    }

    private fun analyse(buf: ShortArray, valid: Int, playAtSample: Int, expectBluetooth: Boolean): Result {
        // Detection lives in :core so it can be tested against real cabin noise —
        // see ChirpTests. The old broadband version worked parked and failed twice
        // on the move, which meant corner timing ran on a remembered number.
        val d = com.rallycopilot.core.audio.ChirpDetect.detect(buf, valid, playAtSample, SAMPLE_RATE)
        val onset = d.onsetSamples
            ?: return Result(null, d.reason, d.noiseLevel)
        val ms = (onset.toDouble() / SAMPLE_RATE * 1000).roundToInt().toLong()
        val minPlausible = if (expectBluetooth) MIN_PLAUSIBLE_BLUETOOTH_MS else MIN_PLAUSIBLE_MS
        if (ms < minPlausible || ms > MAX_PLAUSIBLE_MS) {
            return Result(
                null,
                "measured ${ms} ms, implausible for this route — ignoring",
                d.noiseLevel,
            )
        }
        return Result(ms, "measured ${ms} ms", d.noiseLevel)
    }

}
