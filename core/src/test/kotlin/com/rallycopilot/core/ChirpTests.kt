package com.rallycopilot.core

import com.rallycopilot.core.audio.ChirpDetect
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * The chirp has to be found in a moving car, not just a parked one.
 *
 * Real traces: detection succeeded at a cabin noise level of 0.007 (stationary) and
 * failed twice at 0.039 and 0.042 (driving), leaving every corner call in those
 * drives timed off a number measured on a different day.
 */
class ChirpTests {

    private val rate = 44_100
    private val chirpMs = 130

    /**
     * A synthetic cabin: low-frequency engine and road roar plus broadband hiss,
     * which is what actually swamped the old broadband detector.
     */
    private fun cabin(n: Int, level: Double, seed: Int): DoubleArray {
        val rnd = Random(seed)
        val out = DoubleArray(n)
        var lp = 0.0
        for (i in 0 until n) {
            val white = rnd.nextDouble() * 2 - 1
            lp += (white - lp) * 0.02                     // rumble
            val engine = sin(2 * PI * 95.0 * i / rate) * 0.6 +
                sin(2 * PI * 190.0 * i / rate) * 0.3      // engine orders
            out[i] = (lp * 6.0 + engine + white * 0.25) * level * Short.MAX_VALUE
        }
        return out
    }

    /** Build a recording: lead-in of noise, then the chirp delayed by [delayMs]. */
    private fun recording(delayMs: Int, noiseLevel: Double, chirpGain: Double, seed: Int = 1):
        Pair<ShortArray, Int> {
        val leadMs = 250
        val totalMs = leadMs + 1_400
        val n = rate * totalMs / 1000
        val noise = cabin(n, noiseLevel, seed)
        val tone = ChirpDetect.chirp(rate, chirpMs)
        val playAt = rate * leadMs / 1000
        val arriveAt = playAt + rate * delayMs / 1000
        val buf = ShortArray(n)
        for (i in 0 until n) {
            var v = noise[i]
            val j = i - arriveAt
            if (j in tone.indices) v += tone[j] * chirpGain
            buf[i] = v.coerceIn(-32768.0, 32767.0).toInt().toShort()
        }
        return buf to playAt
    }

    private fun measure(delayMs: Int, noiseLevel: Double, chirpGain: Double): Long? {
        val (buf, playAt) = recording(delayMs, noiseLevel, chirpGain)
        val d = ChirpDetect.detect(buf, buf.size, playAt, rate)
        return d.onsetSamples?.let { (it.toDouble() / rate * 1000).toLong() }
    }

    @Test
    fun `finds the chirp in a parked car`() {
        val ms = measure(delayMs = 200, noiseLevel = 0.007, chirpGain = 0.5)
        assertNotNull("must detect when parked", ms)
        assertTrue("got ${ms}ms, wanted ~200", abs(ms!! - 200) <= 25)
    }

    /** The case that failed twice on the road. */
    @Test
    fun `finds the chirp at the cabin noise that broke the old detector`() {
        for (noise in listOf(0.039, 0.042, 0.06)) {
            val ms = measure(delayMs = 200, noiseLevel = noise, chirpGain = 0.5)
            assertNotNull("must detect at cabin noise $noise", ms)
            assertTrue("noise $noise gave ${ms}ms, wanted ~200", abs(ms!! - 200) <= 30)
        }
    }

    @Test
    fun `measures a range of real head unit delays`() {
        for (want in listOf(80, 150, 220, 416, 600)) {
            val ms = measure(delayMs = want, noiseLevel = 0.03, chirpGain = 0.5)
            assertNotNull("must detect a ${want}ms delay", ms)
            assertTrue("got ${ms}ms, wanted $want", abs(ms!! - want) <= 30)
        }
    }

    @Test
    fun `refuses to guess when the chirp genuinely is not there`() {
        val (buf, playAt) = recording(200, noiseLevel = 0.04, chirpGain = 0.0)
        val d = ChirpDetect.detect(buf, buf.size, playAt, rate)
        assertNull("silence must not produce a number", d.onsetSamples)
    }

    @Test
    fun `refuses when the car stereo is turned right down`() {
        val ms = measure(delayMs = 200, noiseLevel = 0.05, chirpGain = 0.01)
        assertNull("an inaudible chirp must be reported, not invented", ms)
    }

    @Test
    fun `reports the cabin noise level it saw`() {
        val (quiet, p1) = recording(200, 0.007, 0.5)
        val (loud, p2) = recording(200, 0.05, 0.5)
        val q = ChirpDetect.detect(quiet, quiet.size, p1, rate)
        val l = ChirpDetect.detect(loud, loud.size, p2, rate)
        assertTrue("a noisy cabin must read noisier: ${q.noiseLevel} vs ${l.noiseLevel}",
            l.noiseLevel > q.noiseLevel)
    }
}
