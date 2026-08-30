package com.rallycopilot.app.drive

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * Orientation-free road surface sensing: projects linear acceleration onto the gravity
 * vector, so "vertical" needs no mount calibration whatsoever. Emits a 1 s RMS
 * (surface roughness) and discrete spikes (pothole hits, sunk grids).
 *
 * Raw samples are also handed to the mount-wobble monitor via
 * [onSample]; this class stays a dumb sensor pump.
 */
class ImuMonitor(
    context: Context,
    private val onRoughness: (rms: Double) -> Unit,
    private val onBump: () -> Unit,
    /** Raw phone-frame vectors for the mount-wobble monitor, ~50 Hz. */
    private val onSample: (accel: com.rallycopilot.core.imu.Vec3, gravity: com.rallycopilot.core.imu.Vec3) -> Unit = { _, _ -> },
    /**
     * Yaw rate about the VERTICAL axis, rad/s, positive anticlockwise seen from
     * above. Derived from the gyroscope's component along gravity, so it needs no
     * mount alignment: swivelling the phone about the vertical axis — which is
     * exactly what a loose cradle does — leaves this component untouched.
     */
    private val onYawRate: (radPerSec: Double) -> Unit = { },
) : SensorEventListener {

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gravity = FloatArray(3)
    private var haveGravity = false
    private val window = ArrayList<Double>(64)
    private var windowStartMs = 0L
    private var lastBumpMs = 0L

    private val bumpThresholdMps2 = 9.0   // a proper hit, not cat's eyes
    private val bumpCooldownMs = 1500L

    fun start() {
        sm.getDefaultSensor(Sensor.TYPE_GRAVITY)?.let {
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)?.let {
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() = sm.unregisterListener(this)

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_GRAVITY -> {
                gravity[0] = e.values[0]; gravity[1] = e.values[1]; gravity[2] = e.values[2]
                haveGravity = true
            }
            Sensor.TYPE_GYROSCOPE -> {
                if (!haveGravity) return
                val gMag = sqrt(
                    (gravity[0] * gravity[0] + gravity[1] * gravity[1] + gravity[2] * gravity[2]).toDouble()
                )
                if (gMag < 1e-3) return
                // Android's TYPE_GRAVITY points UP (flat phone, screen up, reads
                // +9.81 on z), so projecting the gyro onto it directly gives
                // rotation about UP: positive = turning left (anticlockwise from
                // above), matching the negated course rate in DriveService.
                // Drive 42 regression: a negation here — written for a DOWN-pointing
                // gravity that Android does not report — inverted the yaw sign, so
                // yaw and course rate disagreed in sign through 91% of cornering
                // samples and every ordinary corner read as a spin (OVERSTEER).
                val yaw = (e.values[0] * gravity[0] + e.values[1] * gravity[1] +
                    e.values[2] * gravity[2]) / gMag
                onYawRate(yaw)
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                if (!haveGravity) return
                val gMag = sqrt(
                    (gravity[0] * gravity[0] + gravity[1] * gravity[1] + gravity[2] * gravity[2]).toDouble()
                )
                if (gMag < 1e-3) return
                // Vertical component = projection onto the gravity unit vector.
                val vert = (e.values[0] * gravity[0] + e.values[1] * gravity[1] +
                    e.values[2] * gravity[2]) / gMag

                onSample(
                    com.rallycopilot.core.imu.Vec3(e.values[0].toDouble(), e.values[1].toDouble(), e.values[2].toDouble()),
                    com.rallycopilot.core.imu.Vec3(gravity[0].toDouble(), gravity[1].toDouble(), gravity[2].toDouble()),
                )
                val now = System.currentTimeMillis()
                if (kotlin.math.abs(vert) > bumpThresholdMps2 && now - lastBumpMs > bumpCooldownMs) {
                    lastBumpMs = now
                    onBump()
                }
                window += vert.toDouble()
                if (windowStartMs == 0L) windowStartMs = now
                if (now - windowStartMs >= 1000 && window.size >= 8) {
                    val rms = sqrt(window.sumOf { it * it } / window.size)
                    onRoughness(rms)
                    window.clear()
                    windowStartMs = now
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
