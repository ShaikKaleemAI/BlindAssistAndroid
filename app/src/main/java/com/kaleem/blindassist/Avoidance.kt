package com.kaleem.blindassist

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class Guidance(val state: String, val side: String?)   // state: clear | veer | blocked

/** Steering guidance from a top-down corridor check. NEVER reports "safe", only "nothing seen". */
object Avoidance {
    fun halfWidth(t: Track): Double {
        val d = Cfg.DIMS[t.label] ?: return 0.4
        var w = d.w
        if (t.label in Cfg.VEHICLES || t.label == "cow" || t.label == "horse") w = max(w, 1.0)
        return w / 2.0
    }

    fun corridorHalf(z: Double) =
        Cfg.USER_HALF_WIDTH + Cfg.CORRIDOR_MARGIN + Cfg.CORRIDOR_GROWTH * max(z, 0.0)

    fun inPath(t: Track): Boolean {
        val z = t.z ?: return false
        val x = t.x ?: return false
        if (z <= 0) return false
        val hw = halfWidth(t)
        return (x - hw) < corridorHalf(z) && (x + hw) > -corridorHalf(z)
    }

    fun plan(tracks: List<Track>): Guidance {
        val near = tracks.filter { val z = it.z; z != null && it.x != null && z > 0 && z <= Cfg.LOOKAHEAD }
        val blockers = near.filter { inPath(it) }
        if (blockers.isEmpty()) return Guidance("clear", null)
        val first = blockers.minByOrNull { it.z!! }!!
        val relevant = near.filter { it.z!! <= first.z!! + 1.5 }
        val need = 2 * (Cfg.USER_HALF_WIDTH + Cfg.CORRIDOR_MARGIN)
        val span = 2.5
        val ivs = relevant.map { (it.x!! - halfWidth(it)) to (it.x!! + halfWidth(it)) }.sortedBy { it.first }
        val merged = mutableListOf<DoubleArray>()
        for ((lo, hi) in ivs) {
            if (merged.isNotEmpty() && lo <= merged.last()[1] + 1e-6) merged.last()[1] = max(merged.last()[1], hi)
            else merged += doubleArrayOf(lo, hi)
        }
        val gaps = mutableListOf<Pair<Double, Double>>()
        var cursor = -span
        for (m in merged) { if (m[0] - cursor >= need) gaps += cursor to m[0]; cursor = max(cursor, m[1]) }
        if (span - cursor >= need) gaps += cursor to span
        var bestCentre: Double? = null; var bestCost = 1e9
        for ((lo, hi) in gaps) {
            val centre = min(max(0.0, lo + need / 2), hi - need / 2)
            if (abs(centre) < bestCost) { bestCost = abs(centre); bestCentre = centre }
        }
        if (bestCentre == null) return Guidance("blocked", null)
        if (bestCost < 0.1) return Guidance("clear", null)
        return Guidance("veer", if (bestCentre < 0) "left" else "right")
    }
}
