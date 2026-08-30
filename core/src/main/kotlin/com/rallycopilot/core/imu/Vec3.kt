package com.rallycopilot.core.imu

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.sqrt

/** Minimal 3-vector for phone-frame sensor maths. */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Double) = Vec3(x * k, y * k, z * k)
    fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun norm() = sqrt(x * x + y * y + z * z)
    fun unit(): Vec3 { val n = norm(); return if (n < 1e-9) this else Vec3(x / n, y / n, z / n) }
    fun isFinite() = x.isFinite() && y.isFinite() && z.isFinite()
}
