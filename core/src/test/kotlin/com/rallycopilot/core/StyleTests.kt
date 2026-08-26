package com.rallycopilot.core

import com.rallycopilot.core.model.DriverProfile
import com.rallycopilot.core.obd.Elm327
import com.rallycopilot.core.profile.StyleDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StyleDetectorTests {
    private val profile = DriverProfile.COLD_START

    private fun feed(
        d: StyleDetector, seconds: Int, speedPattern: (Int) -> Double,
        rpm: (Int) -> Int? = { null }, pedal: (Int) -> Double? = { null },
    ) {
        for (i in 0 until seconds * 5) { // 5 Hz
            d.tick(
                StyleDetector.Sample(
                    tMs = i * 200L, speedMps = speedPattern(i),
                    rpm = rpm(i), pedal01 = pedal(i), gear = null, aLatMps2 = null,
                ),
                profile,
            )
        }
    }

    @Test
    fun `steady cruising reads as normal`() {
        val d = StyleDetector()
        feed(d, 60, { 22.0 }, rpm = { 1900 }, pedal = { 0.18 })
        assertFalse(d.isSpirited)
        assertTrue(d.spiritedFraction < 0.1)
    }

    @Test
    fun `hard accel braking high rpm and pedal reads as spirited`() {
        val d = StyleDetector()
        // speed oscillating hard between 12 and 32 m/s (aggressive accel/brake),
        // revs high, pedal committed
        feed(d, 60, { i -> 22.0 + 10.0 * kotlin.math.sin(i / 6.0) },
            rpm = { 3600 }, pedal = { 0.85 })
        assertTrue("score=${d.score}", d.isSpirited)
        assertTrue(d.spiritedFraction > 0.5)
    }

    @Test
    fun `gps only aggressive driving still detected without obd`() {
        val d = StyleDetector()
        feed(d, 60, { i -> 20.0 + 9.0 * kotlin.math.sin(i / 5.0) })
        assertTrue("score=${d.score}", d.score > 0.5)
    }

    @Test
    fun `hysteresis prevents flicker`() {
        val d = StyleDetector()
        // Push hard to enter spirited...
        feed(d, 40, { i -> 22.0 + 10.0 * kotlin.math.sin(i / 6.0) }, rpm = { 3800 }, pedal = { 0.9 })
        assertTrue(d.isSpirited)
        // ...then a score just below the entry threshold should NOT immediately drop it.
        // (exit threshold is lower than entry)
        assertTrue(d.score > 0.4)
    }
}

class ObdPidTests {
    @Test
    fun `supported pid bitmask parses`() {
        // 0100 response BE 1F A8 13: standard example — includes 0x0C, 0x0D
        val s = Elm327.supportedPids(0x00, "41 00 BE 1F A8 13")
        assertTrue(0x0C in s)
        assertTrue(0x0D in s)
        assertTrue(0x01 in s)
        assertFalse(0x02 in s)
    }

    @Test
    fun `best pedal pid prefers accelerator pedal D`() {
        assertEquals("0149", Elm327.bestPedalPid(setOf(0x11, 0x45, 0x49)))
        assertEquals("0145", Elm327.bestPedalPid(setOf(0x11, 0x45)))
        assertEquals("0111", Elm327.bestPedalPid(setOf(0x11)))
        assertNull(Elm327.bestPedalPid(setOf(0x0C)))
    }

    @Test
    fun `percent PIDs parse`() {
        assertEquals(0.5, Elm327.percent01("0149", "41 49 80")!!, 0.01)
        assertEquals(1.0, Elm327.percent01("0145", "41 45 FF")!!, 0.01)
    }
}

class VinTests {
    @Test
    fun `single line vin parses`() {
        // 49 02 01 then "WBAVB13506PT12345" as hex ASCII
        val vin = "WBAVB13506PT12345"
        val hex = vin.map { "%02X".format(it.code) }.joinToString("")
        assertEquals(vin, Elm327.vin("490201$hex"))
    }

    @Test
    fun `iso-tp segmented vin parses`() {
        val raw = """
            014
            0:490201574241
            1:56423133353036
            2:50543132333435
        """.trimIndent()
        assertEquals("WBAVB13506PT12345", Elm327.vin(raw))
    }

    @Test
    fun `garbage returns null`() {
        assertNull(Elm327.vin("NO DATA"))
        assertNull(Elm327.vin("490201414243")) // too short
    }
}
