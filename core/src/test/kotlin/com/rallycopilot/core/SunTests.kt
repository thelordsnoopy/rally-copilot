package com.rallycopilot.core

import com.rallycopilot.core.sun.Sun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SunTests {
    // Stroud
    private val lat = 51.745
    private val lon = -2.218

    private fun utc(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        java.time.LocalDateTime.of(y, mo, d, h, mi)
            .toInstant(java.time.ZoneOffset.UTC).toEpochMilli()

    @Test
    fun `midsummer noon sun is high and due south`() {
        val p = Sun.position(utc(2026, 6, 21, 12, 0), lat, lon)
        assertEquals("elevation", 61.0, p.elevationDeg, 2.5)
        assertEquals("azimuth", 178.0, p.azimuthDeg, 4.0)
    }

    @Test
    fun `midwinter noon sun is low and due south`() {
        val p = Sun.position(utc(2026, 12, 21, 12, 0), lat, lon)
        assertEquals("elevation", 15.0, p.elevationDeg, 2.5)
        assertEquals("azimuth", 178.0, p.azimuthDeg, 4.0)
    }

    @Test
    fun `summer sunrise is roughly north-east`() {
        val p = Sun.position(utc(2026, 6, 21, 4, 0), lat, lon)
        assertTrue("azimuth=${p.azimuthDeg}", p.azimuthDeg in 35.0..75.0)
    }

    @Test
    fun `winter afternoon sun blinds a westward road but not a northward one`() {
        val t = utc(2026, 12, 21, 15, 0) // low, south-west
        assertTrue("west", Sun.inYourEyes(t, lat, lon, bearingDeg = 222.0))
        assertFalse("north", Sun.inYourEyes(t, lat, lon, bearingDeg = 0.0))
    }

    @Test
    fun `high summer sun does not count even when straight ahead`() {
        val t = utc(2026, 6, 21, 12, 0)
        val p = Sun.position(t, lat, lon)
        assertFalse(Sun.inYourEyes(t, lat, lon, bearingDeg = p.azimuthDeg))
    }

    @Test
    fun `night is never glary`() {
        assertFalse(Sun.inYourEyes(utc(2026, 12, 21, 23, 0), lat, lon, 180.0))
    }

    @Test
    fun `heavy cloud suppresses the warning`() {
        val t = utc(2026, 12, 21, 15, 0)
        assertTrue(Sun.inYourEyes(t, lat, lon, 222.0, cloudCover = 0.2))
        assertFalse(Sun.inYourEyes(t, lat, lon, 222.0, cloudCover = 0.95))
    }
}
