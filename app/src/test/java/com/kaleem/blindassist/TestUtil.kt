package com.kaleem.blindassist

import kotlin.math.atan2

/** A confirmed-looking track at forward distance z (m) and lateral offset x (m, +right). */
fun mkTrack(label: String, z: Double, x: Double = 0.0, t: Double = 0.0): Track {
    val bearing = Math.toDegrees(atan2(x, z))
    val est = RangeEst(z, x, 0.2, bearing, emptySet())
    return Track(label, doubleArrayOf(100.0, 100.0, 200.0, 300.0), est, t, 0.9)
}

/** Pixel box a pinhole camera (pitch 0) would see for a Cfg.DIMS object standing on the ground z metres ahead. */
fun boxFor(label: String, z: Double, cam: Cam, fw: Double, fh: Double, jitterPx: Double = 0.0): DoubleArray {
    val d = Cfg.DIMS.getValue(label)
    val fx = cam.fx(fw)
    val bw = fx * d.w / z
    val bh = fx * d.h / z
    val feet = fh / 2 + fx * cam.heightM / z
    val u = fw / 2
    return doubleArrayOf(u - bw / 2 + jitterPx, feet - bh + jitterPx, u + bw / 2 - jitterPx, feet - jitterPx)
}
