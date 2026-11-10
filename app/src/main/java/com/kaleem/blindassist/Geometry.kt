package com.kaleem.blindassist

import kotlin.math.*

/** Pinhole camera. Pitch is positive when the camera looks DOWN toward the ground. */
class Cam(var hfovDeg: Double, var heightM: Double, var defaultPitchDeg: Double) {
    fun fx(w: Double) = (w / 2.0) / tan(Math.toRadians(hfovDeg) / 2.0)
    fun bearingDeg(u: Double, w: Double) = Math.toDegrees(atan((u - w / 2.0) / fx(w)))

    /** Forward ground distance of pixel (u,v), or null if it is at/above the horizon. */
    fun groundZ(u: Double, v: Double, w: Double, h: Double, pitchDeg: Double?): Double? {
        val p = Math.toRadians(pitchDeg ?: defaultPitchDeg)
        val f = fx(w)
        val yn = (v - h / 2.0) / f
        val down = yn * cos(p) + sin(p)
        val fwd = cos(p) - yn * sin(p)
        if (down <= 1e-3 || fwd <= 1e-3) return null
        return (heightM / down) * fwd
    }
}

fun clockHour(bearing: Double): Int {
    val h = ((bearing / 30.0).roundToInt() % 12 + 12) % 12
    return if (h == 0) 12 else h
}
