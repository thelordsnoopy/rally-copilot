package com.rallycopilot.core

import com.rallycopilot.core.obd.DiagProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The probe that asks the car's other modules what they know. The addressing is a
 * guess to be tested on a real car; the PARSING and the SAFETY RULE are not, and
 * they are what these tests hold down.
 */
class DiagProbeTests {

    // ------------------------------------------------------------ safety first

    @Test
    fun `only read services may ever be sent`() {
        assertTrue(DiagProbe.isReadOnly("2100"))     // read by local identifier
        assertTrue(DiagProbe.isReadOnly("22F190"))   // read by identifier
        assertTrue(DiagProbe.isReadOnly("1A80"))     // read ECU identification
        assertTrue(DiagProbe.isReadOnly("3E00"))     // tester present
        // Everything that could CHANGE something in the car.
        assertFalse("write data", DiagProbe.isReadOnly("2EF190"))
        assertFalse("io control - can actuate", DiagProbe.isReadOnly("2F1234"))
        assertFalse("routine control", DiagProbe.isReadOnly("310112"))
        assertFalse("security access", DiagProbe.isReadOnly("2701"))
        assertFalse("session change", DiagProbe.isReadOnly("1003"))
        assertFalse("ecu reset", DiagProbe.isReadOnly("1101"))
        assertFalse("clear DTCs", DiagProbe.isReadOnly("14FFFFFF"))
        assertFalse("empty", DiagProbe.isReadOnly(""))
        assertFalse("garbage", DiagProbe.isReadOnly("zz"))
    }

    @Test
    fun `every planned step is read-only by construction`() {
        // Step's init block throws on anything else, so building the plan is the
        // assertion. A future edit that adds a write cannot compile past this.
        val steps = DiagProbe.discoverySteps() +
            DiagProbe.sweepSteps(DiagProbe.MODULES.first { it.name == "DSC" })
        assertTrue(steps.isNotEmpty())
        assertTrue(steps.all { DiagProbe.isReadOnly(it.requestHex) })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a write request cannot be made into a step at all`() {
        DiagProbe.Step(DiagProbe.MODULES.first(), "2EF190AA", "should be refused")
    }

    // ------------------------------------------------------------- reading replies

    @Test
    fun `a positive response yields its payload`() {
        // DSC answers 21 A0 with four 16-bit values. The reply echoes the tester
        // address, then service 0x61 (0x21 + 0x40), then the identifier.
        val raw = "F1 29 61 A0 0F A0 0F A2 0F 9E 0F A1"
        val r = DiagProbe.parseReply(raw, "21A0")
        assertEquals(DiagProbe.Answer.DATA, r.answer)
        assertEquals(8, r.payload.size)
        assertEquals(0x0F, r.payload[0])
        assertEquals(0xA0, r.payload[1])
    }

    @Test
    fun `a negative response is read as a refusal, not as data`() {
        val r = DiagProbe.parseReply("F1 29 7F 21 31", "21A0")
        assertEquals(DiagProbe.Answer.REFUSED, r.answer)
        assertEquals(0x31, r.nrc)
        assertEquals("request out of range", DiagProbe.nrcText(0x31))
    }

    @Test
    fun `silence and ELM complaints are not mistaken for answers`() {
        for (raw in listOf("NO DATA", "CAN ERROR", "UNABLE TO CONNECT", "?", "")) {
            val r = DiagProbe.parseReply(raw, "21A0")
            assertEquals("'$raw' must read as silence", DiagProbe.Answer.SILENT, r.answer)
        }
    }

    @Test
    fun `a two-byte identifier is stripped for UDS reads`() {
        // 22 F1 90 → 62 F1 90 <VIN bytes>
        val r = DiagProbe.parseReply("F1 12 62 F1 90 57 42 41", "22F190")
        assertEquals(DiagProbe.Answer.DATA, r.answer)
        assertEquals(listOf(0x57, 0x42, 0x41), r.payload)
    }

    // -------------------------------------------------------- finding wheel speeds

    @Test
    fun `four wheels agreeing at road speed are recognised`() {
        // 100 km/h on all four, encoded big-endian at 0.0625 km/h per bit, sitting
        // two bytes into the payload behind a counter.
        val enc = { kph: Double ->
            val v = (kph / 0.0625).toInt()
            listOf((v shr 8) and 0xFF, v and 0xFF)
        }
        val payload = listOf(0x01, 0x02) +
            enc(100.0) + enc(100.5) + enc(99.8) + enc(100.2)
        val guesses = DiagProbe.findWheelSpeeds(payload, referenceKph = 100.0)
        assertTrue("should find the field", guesses.isNotEmpty())
        val g = guesses.first { it.scaleKphPerBit == 0.0625 }
        assertEquals(2, g.offset)
        assertTrue(g.bigEndian)
        assertEquals(100.0, g.meanKph, 1.0)
        assertTrue("wheels should agree", g.spreadKph < 2.0)
    }

    @Test
    fun `parked, nothing can be identified - and it says so`() {
        val payload = List(16) { 0 }
        // This is the whole reason the probe must be run twice: at a standstill
        // every candidate field reads zero and matches everything.
        assertTrue(DiagProbe.findWheelSpeeds(payload, referenceKph = 0.0).isEmpty())
    }

    @Test
    fun `fields that disagree with road speed are rejected`() {
        val payload = listOf(0x00, 0x10, 0x00, 0x11, 0x00, 0x0F, 0x00, 0x12, 0x00, 0x10)
        // Those are ~16 raw: at every scale tried they are nowhere near 100 km/h.
        assertTrue(DiagProbe.findWheelSpeeds(payload, referenceKph = 100.0).isEmpty())
    }

    @Test
    fun `all-ones is treated as signal-invalid, not as a wheel speed`() {
        val payload = List(8) { 0xFF }
        assertTrue(DiagProbe.findWheelSpeeds(payload, referenceKph = 250.0).isEmpty())
    }

    // ------------------------------------------------------ finding steering angle

    @Test
    fun `a field that swung since straight-ahead is a steering candidate`() {
        val straight = listOf(0x00, 0x00, 0x12, 0x34)
        // +180 degrees at 0.5 deg/bit = 360 = 0x0168.
        val turned = listOf(0x01, 0x68, 0x12, 0x34)
        val guesses = DiagProbe.findSteeringAngle(turned, straight)
        assertNotNull(guesses.firstOrNull { it.offset == 0 && it.scaleDegPerBit == 0.5 })
        assertEquals(180.0, guesses.first { it.offset == 0 && it.scaleDegPerBit == 0.5 }.deg, 0.1)
    }

    @Test
    fun `a field that never moved is not a steering angle`() {
        val same = listOf(0x01, 0x68, 0x00, 0x00)
        assertTrue(DiagProbe.findSteeringAngle(same, same).isEmpty())
    }

    @Test
    fun `hex parsing survives the ELM's spacing and rejects odd lengths`() {
        assertEquals(listOf(0x21, 0xA0), DiagProbe.hexBytes("21 A0"))
        assertEquals(listOf(0x21, 0xA0), DiagProbe.hexBytes("21A0"))
        assertTrue(DiagProbe.hexBytes("21A").isEmpty())
        assertTrue(DiagProbe.hexBytes("ZZ").isEmpty())
    }

    @Test
    fun `the sweep covers the whole local identifier space once`() {
        val dsc = DiagProbe.MODULES.first { it.name == "DSC" }
        val steps = DiagProbe.sweepSteps(dsc)
        assertEquals(256, steps.size)
        assertEquals(256, steps.map { it.requestHex }.toSet().size)
        assertEquals("2100", steps.first().requestHex)
        assertEquals("21FF", steps.last().requestHex)
    }
}
