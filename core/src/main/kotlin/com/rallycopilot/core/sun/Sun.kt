package com.rallycopilot.core.sun

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Where the sun is, from date, time and position — no network, no data files.
 *
 * A low sun straight down the road is one of the genuinely dangerous things about
 * driving in this country between October and March: you simply cannot see the
 * corner. The app already knows the bearing of every road on the predicted path
 * and the time of day, so it can say so before you get there.
 *
 * NOAA solar position equations, good to well under a degree — far finer than the
 * question "is the sun about to be in my eyes?" requires.
 */
object Sun {

    data class Position(
        /** Degrees above the horizon; negative is below. */
        val elevationDeg: Double,
        /** Compass bearing of the sun, degrees from true north. */
        val azimuthDeg: Double,
    )

    fun position(utcMillis: Long, latDeg: Double, lonDeg: Double): Position {
        // Julian day from the Unix epoch.
        val jd = utcMillis / 86_400_000.0 + 2_440_587.5
        val n = jd - 2_451_545.0

        val meanLong = (280.460 + 0.9856474 * n).mod(360.0)
        val meanAnom = Math.toRadians((357.528 + 0.9856003 * n).mod(360.0))
        // Ecliptic longitude, including the equation of centre.
        val eclLong = Math.toRadians(
            meanLong + 1.915 * sin(meanAnom) + 0.020 * sin(2 * meanAnom)
        )
        val obliquity = Math.toRadians(23.439 - 0.0000004 * n)

        val rightAsc = atan2(cos(obliquity) * sin(eclLong), cos(eclLong))
        val decl = asin(sin(obliquity) * sin(eclLong))

        // Greenwich mean sidereal time -> local hour angle.
        val gmst = (18.697374558 + 24.06570982441908 * n).mod(24.0)
        val lmstDeg = (gmst * 15.0 + lonDeg).mod(360.0)
        var hourAngle = Math.toRadians(lmstDeg) - rightAsc
        // Wrap to -pi..pi so azimuth comes out on the correct side of the sky.
        while (hourAngle > PI) hourAngle -= 2 * PI
        while (hourAngle < -PI) hourAngle += 2 * PI

        val lat = Math.toRadians(latDeg)
        val elevation = asin(sin(lat) * sin(decl) + cos(lat) * cos(decl) * cos(hourAngle))
        val azimuth = atan2(
            -sin(hourAngle),
            tan(decl) * cos(lat) - sin(lat) * cos(hourAngle)
        )
        return Position(
            elevationDeg = Math.toDegrees(elevation),
            azimuthDeg = (Math.toDegrees(azimuth) + 360.0).mod(360.0),
        )
    }

    data class Params(
        /** Above this the sun is high enough that a visor deals with it. */
        val maxElevationDeg: Double = 17.0,
        /** Below this it has set (or nearly), and there is nothing to be blinded by. */
        val minElevationDeg: Double = -1.5,
        /** How closely the road must point at the sun to matter. */
        val maxBearingOffsetDeg: Double = 22.0,
        /** Cloud cover fraction above which the sun is not a problem. */
        val maxCloudCover: Double = 0.65,
    )

    /**
     * Is the sun going to be in the driver's eyes on a road heading [bearingDeg]?
     *
     * [cloudCover] is 0..1 if a recent observation is available, or null when it is
     * not — an unknown sky is treated as clear, because a missed warning costs more
     * than a warning you can ignore.
     */
    fun inYourEyes(
        utcMillis: Long,
        latDeg: Double,
        lonDeg: Double,
        bearingDeg: Double,
        cloudCover: Double? = null,
        params: Params = Params(),
    ): Boolean {
        if (bearingDeg.isNaN()) return false
        if (cloudCover != null && cloudCover > params.maxCloudCover) return false
        val p = position(utcMillis, latDeg, lonDeg)
        if (p.elevationDeg > params.maxElevationDeg || p.elevationDeg < params.minElevationDeg) return false
        var diff = abs(p.azimuthDeg - bearingDeg) % 360.0
        if (diff > 180.0) diff = 360.0 - diff
        return diff <= params.maxBearingOffsetDeg
    }

    /** Degrees between the sun and a heading, 0..180. Useful for the HUD. */
    fun offsetFrom(sun: Position, bearingDeg: Double): Double {
        var diff = abs(sun.azimuthDeg - bearingDeg) % 360.0
        if (diff > 180.0) diff = 360.0 - diff
        return diff
    }
}

/**
 * Turns [Sun] into one calm warning per episode rather than a running commentary.
 *
 * Glare arrives and leaves as you turn, so the raw test flickers on every bend.
 * This warns on entering a glary stretch and then holds its tongue.
 */
class SunWatch(
    private val params: Sun.Params = Sun.Params(),
    /** Shortest gap between two warnings, however many corners intervene. */
    private val cooldownMs: Long = 8 * 60_000L,
    /** Glare must persist this long to be worth mentioning — not one flick of a bend. */
    private val sustainMs: Long = 4_000L,
) {
    private var glarySinceMs = -1L
    private var lastWarnMs = Long.MIN_VALUE / 2
    private var warnedThisEpisode = false

    /** Cloud cover 0..1 from a recent observation, or null if unknown. */
    @Volatile
    var cloudCover: Double? = null

    /** True exactly once per episode of genuine glare. */
    fun check(nowMs: Long, latDeg: Double, lonDeg: Double, bearingDeg: Double): Boolean {
        val glary = Sun.inYourEyes(nowMs, latDeg, lonDeg, bearingDeg, cloudCover, params)
        if (!glary) {
            glarySinceMs = -1
            warnedThisEpisode = false
            return false
        }
        if (glarySinceMs < 0) glarySinceMs = nowMs
        if (warnedThisEpisode) return false
        if (nowMs - glarySinceMs < sustainMs) return false
        if (nowMs - lastWarnMs < cooldownMs) return false
        lastWarnMs = nowMs
        warnedThisEpisode = true
        return true
    }
}
