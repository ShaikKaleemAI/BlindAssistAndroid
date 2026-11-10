package com.kaleem.blindassist

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.asin
import kotlin.math.sqrt

/**
 * Camera tilt from gravity. Back camera looks along device -Z. The gravity sensor reads the "up" vector
 * in device axes, so sin(pitch_down) = gz/|g|.  Phone upright in the pocket => pitch ~ 0.
 */
class PocketSensors(ctx: Context) : SensorEventListener {
    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    @Volatile var gx = 0.0
    @Volatile var gy = 9.8
    @Volatile var gz = 0.0
    private var lowpass = false

    fun start() {
        val g = sm.getDefaultSensor(Sensor.TYPE_GRAVITY)
        if (g != null) sm.registerListener(this, g, SensorManager.SENSOR_DELAY_GAME)
        else sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            lowpass = true; sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() = sm.unregisterListener(this)

    override fun onSensorChanged(e: SensorEvent) {
        val a = if (lowpass) 0.08 else 1.0
        gx += a * (e.values[0] - gx); gy += a * (e.values[1] - gy); gz += a * (e.values[2] - gz)
    }

    override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}

    fun pitchDeg(): Double {
        val n = sqrt(gx * gx + gy * gy + gz * gz)
        return if (n < 1.0) 0.0 else Math.toDegrees(asin((gz / n).coerceIn(-1.0, 1.0)))
    }

    /** Phone inverted in the pocket (camera end down): image must be rotated 180 deg. */
    fun upsideDown() = gy < -3.0
}
