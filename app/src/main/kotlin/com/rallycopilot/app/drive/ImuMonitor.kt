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
 * Yaw alignment (which way is FORWARD in the mount) — needed for camber — is
 * deliberately not attempted here; roughness and grade don't need it.
 */
class ImuMonitor(
    context: Context,
    private val onRoughness: (rms: Double) -> Unit,
    private val onBump: () -> Unit,
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
    }

    fun stop() = sm.unregisterListener(this)

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_GRAVITY -> {
                gravity[0] = e.values[0]; gravity[1] = e.values[1]; gravity[2] = e.values[2]
                haveGravity = true
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
