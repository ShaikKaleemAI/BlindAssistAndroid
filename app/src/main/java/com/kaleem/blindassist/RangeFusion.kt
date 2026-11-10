package com.kaleem.blindassist

import kotlin.math.*

class RangeEst(val z: Double, val x: Double, val sigma: Double, val bearing: Double, val flags: Set<String>)
private data class Cand(val z: Double, val s: Double, val name: String)

private const val EDGE = 3.0

private fun wts(c: List<Cand>) = c.map { 1.0 / max(it.s, 1e-3).pow(2) }
private fun chi2(c: List<Cand>): Double {
    val w = wts(c)
    val m = c.indices.sumOf { w[it] * c[it].z } / w.sum()
    return c.indices.sumOf { w[it] * (c[it].z - m).pow(2) } / max(c.size - 1, 1)
}

/** Truncation-aware fusion of width / height / ground-plane range cues (port of range_fusion.py). */
fun estimateRange(label: String, box: DoubleArray, fw: Double, fh: Double, cam: Cam, pitchIn: Double?): RangeEst? {
    val (x1, y1, x2, y2) = box
    val bw = max(x2 - x1, 1.0)
    val bh = max(y2 - y1, 1.0)
    var uc = (x1 + x2) / 2.0
    val fx = cam.fx(fw)
    val pitch = pitchIn ?: cam.defaultPitchDeg
    val clipL = x1 <= EDGE; val clipR = x2 >= fw - EDGE
    val clipT = y1 <= EDGE; val clipB = y2 >= fh - EDGE
    val dims = Cfg.DIMS[label]
    val cands = mutableListOf<Cand>()
    val bounds = mutableListOf<Double>()
    val flags = mutableSetOf<String>()

    if (dims != null) {
        if (!(clipL || clipR)) { val z = fx * dims.w / bw; cands += Cand(z, dims.sw * z, "width") }
        else { bounds += fx * dims.w * (1 + 1.5 * dims.sw) / bw; flags += "clipped_side" }
        if (!(clipT || clipB)) { val z = fx * dims.h / bh; cands += Cand(z, dims.sh * z, "height") }
        else if (clipT) { bounds += fx * dims.h * (1 + 1.5 * dims.sh) / bh; flags += "clipped_top" }
    }
    if (label in Cfg.GROUNDED) {
        if (!clipB) {
            val z = cam.groundZ(uc, y2, fw, fh, pitch)
            if (z != null && z > 0.3) {
                val za = cam.groundZ(uc, y2 - 4, fw, fh, pitch)
                val zb = cam.groundZ(uc, y2 + 4, fw, fh, pitch)
                val zp1 = cam.groundZ(uc, y2, fw, fh, pitch + Cfg.pitchSigmaDeg)
                val zp2 = cam.groundZ(uc, y2, fw, fh, pitch - Cfg.pitchSigmaDeg)
                val sPix = abs((za ?: z) - (zb ?: z)) / 2.0
                val sPit = abs((zp1 ?: z) - (zp2 ?: z)) / 2.0
                val sigma = max(hypot(sPix, sPit), 0.06 * z)
                if (z < 30) cands += Cand(z, sigma, "ground")
            }
        } else {
            cam.groundZ(uc, fh - 1, fw, fh, pitch)?.let { bounds += it }
            flags += "clipped_bottom"
        }
    }

    var z: Double
    var sigma: Double
    if (cands.isEmpty()) {
        if (bounds.isEmpty()) return null
        z = 0.8 * bounds.min(); sigma = 0.3 * z
        flags += "bound_only"; flags += "close"
    } else {
        val w = wts(cands)
        z = cands.indices.sumOf { w[it] * cands[it].z } / w.sum()
        sigma = sqrt(1.0 / w.sum())
        if (cands.size >= 2 && chi2(cands) > 6.0) {
            var best: Triple<Double, List<Cand>, String>? = null
            if (cands.size >= 3) {
                for (i in cands.indices) {
                    val sub = cands.filterIndexed { j, _ -> j != i }
                    val c2 = chi2(sub)
                    if (c2 <= 3.0 && (best == null || c2 < best.first)) best = Triple(c2, sub, cands[i].name)
                }
            }
            if (best != null) {
                val sub = best.second
                val ws = wts(sub)
                z = sub.indices.sumOf { ws[it] * sub[it].z } / ws.sum()
                sigma = sqrt(1.0 / ws.sum())
                flags += "dropped_" + best.third
            } else {                         // no consensus: fail SAFE = nearest
                val n = cands.minByOrNull { it.z }!!
                z = n.z; sigma = max(sigma, 0.25 * z); flags += "disagree"
            }
        }
        if (bounds.isNotEmpty() && z > bounds.min()) { z = bounds.min(); flags += "clamped_by_bound" }
    }

    if ((clipL || clipR) && dims != null && !(clipL && clipR)) {
        val expW = fx * dims.w / max(z, 0.3)
        if (expW > bw) { uc = if (clipR) x1 + expW / 2.0 else x2 - expW / 2.0; flags += "centre_recovered" }
    }
    val bearing = cam.bearingDeg(uc, fw)
    return RangeEst(z, z * tan(Math.toRadians(bearing)), sigma, bearing, flags)
}
