package com.rallycopilot.app.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

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
    private const val F_START = 2_200.0
    private const val F_END = 3_600.0
    /** Sanity bounds. Anything outside these is a bad measurement, not a fast car. */
    private const val MIN_PLAUSIBLE_MS = 30L
    private const val MAX_PLAUSIBLE_MS = 900L

    data class Result(
        val latencyMs: Long?,
        val message: String,
        /** Ambient cabin loudness while listening, 0..1. Useful for auto-volume. */
        val noiseLevel: Double = 0.0,
    )

    /** The "cool noise": a short rising sweep with a fast attack and soft tail. */
    fun chirp(): ShortArray {
        val n = SAMPLE_RATE * CHIRP_MS / 1000
        val out = ShortArray(n)
        var phase = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / n
            val f = F_START + (F_END - F_START) * t
            phase += 2 * PI * f / SAMPLE_RATE
            // Fast attack, exponential decay — reads as a "zip", and the sharp
            // onset is exactly what makes it easy to time.
            val env = if (t < 0.04) t / 0.04 else Math.exp(-3.0 * (t - 0.04))
            // Near full scale: the chirp has to be heard by the phone's own
            // microphone from across the cabin, over engine and road noise, and a
            // measurement that fails is a guessed number in every corner call.
            out[i] = (sin(phase) * env * 0.95 * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    fun hasMic(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Blocking measurement — call off the main thread. Returns null latency (with a
     * reason) rather than a wrong number whenever the measurement is not trustworthy.
     */
    fun measure(context: Context, attrs: AudioAttributes): Result {
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

            while (filled < total) {
                val n = recorder.read(captured, filled, total - filled)
                if (n <= 0) break
                filled += n
            }
            recorder.stop()

            analyse(captured, filled, playAtSample)
        } catch (e: Exception) {
            Result(null, e.message?.take(50) ?: "measurement failed")
        } finally {
            runCatching { track.stop() }
            runCatching { track.release() }
            runCatching { recorder.release() }
        }
    }

    /**
     * Find the chirp by its energy in the 2-4 kHz band. A matched filter would be
     * more elegant, but a band-limited envelope is cheap, and the chirp's sharp
     * attack makes onset detection reliable even over a running engine.
     */
    private fun analyse(buf: ShortArray, valid: Int, playAtSample: Int): Result {
        val block = SAMPLE_RATE / 400          // 2.5 ms blocks
        if (valid < playAtSample + block * 8) return Result(null, "recording too short")

        // Band-pass by simple differencing (kills low-frequency engine rumble) and
        // measure energy per block.
        val blocks = (valid - 1) / block
        val energy = DoubleArray(blocks)
        for (b in 0 until blocks) {
            var sum = 0.0
            val start = b * block + 1
            for (i in start until start + block) {
                val d = (buf[i] - buf[i - 1]).toDouble()   // high-pass
                sum += d * d
            }
            energy[b] = sum / block
        }

        val floorBlocks = playAtSample / block
        if (floorBlocks < 4) return Result(null, "no quiet period to compare against")
        val floorSorted = energy.copyOfRange(0, floorBlocks).sortedArray()
        val noiseFloor = floorSorted[floorSorted.size / 2].coerceAtLeast(1.0)
        val peak = energy.copyOfRange(floorBlocks, blocks).maxOrNull() ?: 0.0
        val noiseLevel = (Math.sqrt(noiseFloor) / Short.MAX_VALUE * 12).coerceIn(0.0, 1.0)

        // Need the chirp to stand clearly above the cabin. Six times the noise floor
        // in energy terms is about 8 dB — modest, but enough to be unambiguous.
        if (peak < noiseFloor * 6.0) {
            return Result(null, "couldn't hear the chirp — is the volume up?", noiseLevel)
        }
        val threshold = maxOf(noiseFloor * 6.0, peak * 0.25)
        var onset = -1
        for (b in floorBlocks until blocks) {
            if (energy[b] >= threshold) { onset = b; break }
        }
        if (onset < 0) return Result(null, "chirp not found in the recording", noiseLevel)

        val ms = ((onset * block - playAtSample).toDouble() / SAMPLE_RATE * 1000).roundToInt().toLong()
        if (ms < MIN_PLAUSIBLE_MS || ms > MAX_PLAUSIBLE_MS) {
            return Result(null, "measured ${ms} ms, which is out of range — ignoring", noiseLevel)
        }
        return Result(ms, "measured ${ms} ms", noiseLevel)
    }
}
