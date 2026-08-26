package com.rallycopilot.core.geo

import com.rallycopilot.core.model.LatLon
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Geometry primitives. Everything works in a local equirectangular projection around a
 * reference latitude — at UK latitudes and the sub-100 km scales we care about, the error
 * is centimetres, and it keeps the maths flat and fast.
 */
object Geo {
    const val EARTH_RADIUS_M = 6_371_000.0

    fun haversineM(a: LatLon, b: LatLon): Double {
        val dLat = (b.lat - a.lat).toRad()
        val dLon = (b.lon - a.lon).toRad()
        val s = sin(dLat / 2) * sin(dLat / 2) +
            cos(a.lat.toRad()) * cos(b.lat.toRad()) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(s), sqrt(1 - s))
    }

    /**
     * Squared distance in metres², flat-earth, for range tests only. Compare against
     * radius² and skip both the square root and the haversine — this runs over every
     * geometry point of a couple of thousand edges when the map view reloads.
     */
    fun approxSquareMetres(a: LatLon, b: LatLon): Double {
        val x = (b.lon - a.lon).toRad() * EARTH_RADIUS_M * cos(a.lat.toRad())
        val y = (b.lat - a.lat).toRad() * EARTH_RADIUS_M
        return x * x + y * y
    }

    /** Local flat projection: metres east/north of [origin]. */
    fun toXY(p: LatLon, origin: LatLon): XY {
        val x = (p.lon - origin.lon).toRad() * EARTH_RADIUS_M * cos(origin.lat.toRad())
        val y = (p.lat - origin.lat).toRad() * EARTH_RADIUS_M
        return XY(x, y)
    }

    fun bearingDeg(a: LatLon, b: LatLon): Double {
        val dLon = (b.lon - a.lon).toRad()
        val y = sin(dLon) * cos(b.lat.toRad())
        val x = cos(a.lat.toRad()) * sin(b.lat.toRad()) -
            sin(a.lat.toRad()) * cos(b.lat.toRad()) * cos(dLon)
        return (atan2(y, x).toDeg() + 360.0) % 360.0
    }

    /** Smallest absolute angular difference between two bearings, 0..180. */
    fun bearingDiffDeg(a: Double, b: Double): Double {
        val d = abs(a - b) % 360.0
        return if (d > 180.0) 360.0 - d else d
    }

    private fun Double.toRad() = this * PI / 180.0
    private fun Double.toDeg() = this * 180.0 / PI
}

data class XY(val x: Double, val y: Double) {
    operator fun minus(o: XY) = XY(x - o.x, y - o.y)
    operator fun plus(o: XY) = XY(x + o.x, y + o.y)
    operator fun times(k: Double) = XY(x * k, y * k)
    fun dot(o: XY) = x * o.x + y * o.y
    fun cross(o: XY) = x * o.y - y * o.x
    fun norm() = sqrt(x * x + y * y)
}

/** Result of projecting a point onto a polyline. */
data class Projection(
    val distanceAlongM: Double,
    val lateralOffsetM: Double,
    val segmentIndex: Int,
)

object Polyline {
    /** Cumulative distance at each vertex. cum[0] = 0. */
    fun cumulative(points: List<LatLon>): DoubleArray {
        val cum = DoubleArray(points.size)
        for (i in 1 until points.size) {
            cum[i] = cum[i - 1] + Geo.haversineM(points[i - 1], points[i])
        }
        return cum
    }

    /**
     * Project [p] onto the polyline. Returns distance along it and unsigned lateral offset.
     * O(n) — fine for single edges; the matcher pre-filters candidates spatially.
     */
    fun project(points: List<LatLon>, cum: DoubleArray, p: LatLon): Projection {
        require(points.size >= 2)
        val origin = points[0]
        val pt = Geo.toXY(p, origin)
        var bestLat = Double.MAX_VALUE
        var bestAlong = 0.0
        var bestSeg = 0
        for (i in 0 until points.size - 1) {
            val a = Geo.toXY(points[i], origin)
            val b = Geo.toXY(points[i + 1], origin)
            val ab = b - a
            val len2 = ab.dot(ab)
            val t = if (len2 == 0.0) 0.0 else ((pt - a).dot(ab) / len2).coerceIn(0.0, 1.0)
            val proj = a + ab * t
            val lateral = (pt - proj).norm()
            if (lateral < bestLat) {
                bestLat = lateral
                bestSeg = i
                bestAlong = cum[i] + (cum[i + 1] - cum[i]) * t
            }
        }
        return Projection(bestAlong, bestLat, bestSeg)
    }

    /** Interpolated point at [distM] along the polyline. Clamped to ends. */
    fun pointAt(points: List<LatLon>, cum: DoubleArray, distM: Double): LatLon {
        if (distM <= 0) return points.first()
        if (distM >= cum.last()) return points.last()
        var i = cum.indexOfFirst { it >= distM }
        if (i <= 0) i = 1
        val segLen = cum[i] - cum[i - 1]
        val t = if (segLen == 0.0) 0.0 else (distM - cum[i - 1]) / segLen
        val a = points[i - 1]
        val b = points[i]
        return LatLon(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
    }

    /** Bearing of the polyline at [distM] along it. */
    fun bearingAt(points: List<LatLon>, cum: DoubleArray, distM: Double): Double {
        var i = cum.indexOfFirst { it >= distM }.coerceAtLeast(1)
        if (i >= points.size) i = points.size - 1
        return Geo.bearingDeg(points[i - 1], points[i])
    }
}

object Curvature {
    /**
     * Radius of the circumscribed circle through three points, metres.
     * Returns Double.MAX_VALUE for collinear points (straight road).
     * Sign of the returned cross product convention is handled by [turnSign].
     */
    fun circumradius(a: XY, b: XY, c: XY): Double {
        val ab = (b - a).norm()
        val bc = (c - b).norm()
        val ca = (a - c).norm()
        val cross = (b - a).cross(c - a)
        val area2 = abs(cross)
        if (area2 < 1e-9) return Double.MAX_VALUE
        return (ab * bc * ca) / (2.0 * area2)
    }

    /** +1 = left turn, -1 = right turn, 0 = straight, for points in an east/north frame. */
    fun turnSign(a: XY, b: XY, c: XY): Int {
        val cross = (b - a).cross(c - a)
        return when {
            cross > 1e-9 -> 1
            cross < -1e-9 -> -1
            else -> 0
        }
    }
}
