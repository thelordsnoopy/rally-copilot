package com.rallycopilot.core.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Generating and finding the audio-latency chirp.
 *
 * Pure maths, no Android, so the detection can actually be tested against noise
 * instead of hoped about. The app owns the microphone and the speaker; this owns
 * the question "where in this recording is the chirp".
 *
 * WHY IT WAS REWRITTEN. The first version high-passed by simple first-differencing
 * and measured broadband energy. First-differencing passes everything above roughly
 * a kilohertz, so tyre roar, wind and the engine all landed in the measurement
 * alongside the chirp. It worked parked (cabin noise 0.007) and failed on the move
 * (0.039, 0.042) — two of three real attempts came back "couldn't hear the chirp",
 * and every corner call in those drives was then timed off a remembered number from
 * a different day. A band-pass restricted to the chirp's own 2.2–3.6 kHz throws away
 * the noise that was drowning it.
 */
object ChirpDetect {

    const val F_START = 2_200.0
    const val F_END = 3_600.0

    /** A rising sweep with a fast attack and a soft tail: reads as a "zip". */
    fun chirp(sampleRate: Int, ms: Int, amplitude: Double = 0.95): ShortArray {
        val n = sampleRate * ms / 1000
        val out = ShortArray(n)
        var phase = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / n
            val f = F_START + (F_END - F_START) * t
            phase += 2 * PI * f / sampleRate
            val env = if (t < 0.04) t / 0.04 else exp(-3.0 * (t - 0.04))
            out[i] = (sin(phase) * env * amplitude * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    data class Detection(
        /** Samples from the moment playback started to the chirp's onset. */
        val onsetSamples: Int?,
        val reason: String,
        /** Cabin loudness 0..1, from the pre-playback lead-in. */
        val noiseLevel: Double,
        /** Peak-to-noise ratio achieved in the chirp band, dB. */
        val snrDb: Double,
    )

    /**
     * Band-pass to the chirp's own frequencies, then look for the energy burst.
     *
     * [playAtSample] is where playback began; everything before it is the noise
     * floor to beat. The band-pass is a two-pole resonator applied forwards, which
     * is cheap enough to run on a phone at drive start and sharp enough to cut the
     * low-frequency energy that dominates a moving car.
     */
    fun detect(
        buf: ShortArray,
        valid: Int,
        playAtSample: Int,
        sampleRate: Int,
        minSnrDb: Double = 8.0,
    ): Detection {
        val block = sampleRate / 400            // 2.5 ms
        if (valid < playAtSample + block * 8) {
            return Detection(null, "recording too short", 0.0, 0.0)
        }
        val filtered = bandPass(buf, valid, sampleRate)

        val blocks = valid / block
        val energy = DoubleArray(blocks)
        for (b in 0 until blocks) {
            var sum = 0.0
            val start = b * block
            for (i in start until start + block) sum += filtered[i] * filtered[i]
            energy[b] = sum / block
        }

        val floorBlocks = playAtSample / block
        if (floorBlocks < 4) return Detection(null, "no quiet period to compare against", 0.0, 0.0)
        val floorSorted = energy.copyOfRange(0, floorBlocks).sortedArray()
        // Median, not mean: one door slam in the lead-in must not raise the bar.
        val noiseFloor = floorSorted[floorSorted.size / 2].coerceAtLeast(1e-6)
        val noiseLevel = (sqrt(noiseFloor) / Short.MAX_VALUE * 12).coerceIn(0.0, 1.0)

        var peak = 0.0
        for (b in floorBlocks until blocks) if (energy[b] > peak) peak = energy[b]
        val snrDb = 10.0 * kotlin.math.log10(peak / noiseFloor)
        if (snrDb < minSnrDb) {
            return Detection(null, "couldn't hear the chirp — is the volume up?", noiseLevel, snrDb)
        }

        // Onset = first block clearly above the floor, not the loudest block: the
        // peak may sit a few milliseconds into the sweep, and it is the ARRIVAL
        // being timed.
        val threshold = maxOf(noiseFloor * 4.0, peak * 0.20)
        var onset = -1
        for (b in floorBlocks until blocks) {
            if (energy[b] >= threshold) { onset = b; break }
        }
        if (onset < 0) return Detection(null, "chirp not found in the recording", noiseLevel, snrDb)
        return Detection(onset * block - playAtSample, "measured", noiseLevel, snrDb)
    }

    /**
     * Two-pole resonator centred on the chirp band. Narrow enough to reject road
     * and engine noise, wide enough to pass a sweep from 2.2 to 3.6 kHz.
     */
    private fun bandPass(buf: ShortArray, valid: Int, sampleRate: Int): DoubleArray {
        val centre = (F_START + F_END) / 2.0
        val bandwidth = (F_END - F_START) * 1.3
        val r = 1.0 - PI * (bandwidth / sampleRate)
        val theta = 2 * PI * centre / sampleRate
        val a1 = -2.0 * r * cos(theta)
        val a2 = r * r
        // Unity-ish gain at centre so the numbers stay in a sane range.
        val gain = (1 - r) * sqrt(1 - 2 * r * cos(2 * theta) + r * r)
        val out = DoubleArray(valid)
        var y1 = 0.0
        var y2 = 0.0
        for (i in 0 until valid) {
            val y = gain * buf[i] - a1 * y1 - a2 * y2
            out[i] = y
            y2 = y1
            y1 = y
        }
        return out
    }
}
