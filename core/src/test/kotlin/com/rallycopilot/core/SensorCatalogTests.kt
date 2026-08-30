package com.rallycopilot.core

import com.rallycopilot.core.obd.SensorCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sensor catalogue: what the car can be asked, and what the answers mean.
 * These are the SAE J1979 standard definitions, so the decodes are checkable
 * against known values rather than being a matter of opinion.
 */
class SensorCatalogTests {

    @Test
    fun `engine rpm decodes by the standard quarter-rev rule`() {
        // 0x0C: (256A + B) / 4. 0x1AF8 = 6904 -> 1726 rpm.
        assertEquals(1726.0, SensorCatalog.decode(0x0C, "41 0C 1A F8")!!, 0.01)
    }

    @Test
    fun `speed is a plain byte of km per hour`() {
        assertEquals(100.0, SensorCatalog.decode(0x0D, "41 0D 64")!!, 0.001)
        assertEquals(0.0, SensorCatalog.decode(0x0D, "41 0D 00")!!, 0.001)
    }

    @Test
    fun `temperatures carry the forty degree offset`() {
        // Coolant at 0x5A = 90 - 40 = 50 C; and the freezing point of the scale.
        assertEquals(50.0, SensorCatalog.decode(0x05, "41 05 5A")!!, 0.001)
        assertEquals(-40.0, SensorCatalog.decode(0x05, "41 05 00")!!, 0.001)
        // Oil temperature uses the same rule - one of the ones worth having.
        assertEquals(95.0, SensorCatalog.decode(0x5C, "41 5C 87")!!, 0.001)
    }

    @Test
    fun `percentages span the full byte`() {
        assertEquals(0.0, SensorCatalog.decode(0x49, "41 49 00")!!, 0.001)
        assertEquals(100.0, SensorCatalog.decode(0x49, "41 49 FF")!!, 0.001)
        assertEquals(50.0, SensorCatalog.decode(0x49, "41 49 80")!!, 0.4)
    }

    @Test
    fun `torque PIDs are signed around 125 so braking reads negative`() {
        // 0x62 actual engine torque: A - 125. Engine braking is a real negative.
        assertEquals(0.0, SensorCatalog.decode(0x62, "41 62 7D")!!, 0.001)
        assertEquals(75.0, SensorCatalog.decode(0x62, "41 62 C8")!!, 0.001)
        assertEquals(-25.0, SensorCatalog.decode(0x62, "41 62 64")!!, 0.001)
    }

    @Test
    fun `fuel rate is twenty counts to the litre per hour`() {
        assertEquals(50.0, SensorCatalog.decode(0x5E, "41 5E 03 E8")!!, 0.001)
    }

    @Test
    fun `control module voltage is millivolts`() {
        assertEquals(14.5, SensorCatalog.decode(0x42, "41 42 38 A4")!!, 0.001)
    }

    @Test
    fun `bitfields and status words decode to nothing, deliberately`() {
        // 0x03 is a fuel-system status word, not a measurement: refusing to
        // invent a number for it is the point.
        assertNull(SensorCatalog.decode(0x03, "41 03 02 00"))
        assertNull(SensorCatalog.decode(0x01, "41 01 00 07 E1 00"))
    }

    @Test
    fun `an unknown PID is named rather than silently dropped`() {
        assertNull(SensorCatalog.decode(0xFE, "41 FE 12"))
        assertTrue("unknown" in SensorCatalog.nameOf(0xFE))
        // ...but a known one gets its real name.
        assertEquals("Engine oil temperature", SensorCatalog.nameOf(0x5C))
    }

    @Test
    fun `a truncated or refused response yields nothing`() {
        assertNull(SensorCatalog.decode(0x0C, "NO DATA"))
        assertNull(SensorCatalog.decode(0x0C, "41 0C 1A")) // two bytes wanted, one given
        assertNull(SensorCatalog.decode(0x0C, ""))
    }

    @Test
    fun `the catalogue is internally consistent`() {
        // No duplicate PIDs, every decodable sensor declares its byte count, and
        // every request is a well-formed mode-01 query.
        assertEquals(SensorCatalog.ALL.size, SensorCatalog.ALL.map { it.pid }.toSet().size)
        for (s in SensorCatalog.ALL) {
            assertTrue("${s.name} needs a byte count", s.bytes >= 1)
            assertEquals("01%02X".format(s.pid), s.request)
            assertEquals(s, SensorCatalog.BY_PID[s.pid])
        }
    }

    @Test
    fun `the wanted list names sensors the app does not read yet`() {
        val wanted = SensorCatalog.WANTED.map { it.pid }.toSet()
        // Oil temperature, fuel rate and the torque pair are the prize.
        assertTrue(0x5C in wanted)
        assertTrue(0x5E in wanted)
        assertTrue(0x61 in wanted)
        assertTrue(0x62 in wanted)
        // Things already polled are not "wanted".
        assertTrue(0x0C !in wanted)
        assertTrue(0x0D !in wanted)
    }

    @Test
    fun `all seven supported-PID bitmaps are asked for`() {
        // The old code queried two of these, which is why oil temperature and the
        // torque pair were invisible for twenty drives.
        assertEquals(7, SensorCatalog.SUPPORT_QUERIES.size)
        assertEquals(listOf(0x00, 0x20, 0x40, 0x60, 0x80, 0xA0, 0xC0),
            SensorCatalog.SUPPORT_QUERIES.map { it.first })
        for ((base, req) in SensorCatalog.SUPPORT_QUERIES) {
            assertEquals("01%02X".format(base), req)
        }
    }

    @Test
    fun `the phone list covers the sensors the app actually depends on`() {
        val types = SensorCatalog.PHONE.map { it.type }.toSet()
        assertTrue("accelerometer", 1 in types)
        assertTrue("gyroscope - the slip story rests on it", 4 in types)
        assertTrue("gravity", 9 in types)
        assertNotNull(SensorCatalog.PHONE.first { it.type == 4 }.useful)
    }
}
