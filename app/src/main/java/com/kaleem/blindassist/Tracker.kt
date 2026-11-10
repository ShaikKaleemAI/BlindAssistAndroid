package com.kaleem.blindassist

import kotlin.math.*

class Det(val label: String, val box: DoubleArray, val conf: Double, var est: RangeEst? = null)

private val FAMILY = mapOf("car" to "veh", "truck" to "veh", "bus" to "veh",
    "motorcycle" to "two", "bicycle" to "two", "dining table" to "table")
private fun family(l: String) = FAMILY[l] ?: l

fun iou(a: DoubleArray, b: DoubleArray): Double {
    val iw = max(min(a[2], b[2]) - max(a[0], b[0]), 0.0)
    val ih = max(min(a[3], b[3]) - max(a[1], b[1]), 0.0)
    val inter = iw * ih
    val ua = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
    return if (ua > 0) inter / ua else 0.0
}

/** 1-D constant-velocity Kalman filter on range: state [z, v]. */
class RangeKalman(z0: Double, sigma0: Double, private val q: Double = 2.0) {
    var z = z0; var v = 0.0
    private var p = arrayOf(doubleArrayOf(sigma0 * sigma0, 0.0), doubleArrayOf(0.0, 4.0))

    fun predict(dt: Double) {
        if (dt <= 0) return
        z += v * dt
        val qd = q * q
        val p00 = p[0][0] + dt * (p[1][0] + p[0][1]) + dt * dt * p[1][1] + qd * dt.pow(4) / 4
        val p01 = p[0][1] + dt * p[1][1] + qd * dt.pow(3) / 2
        val p10 = p[1][0] + dt * p[1][1] + qd * dt.pow(3) / 2
        val p11 = p[1][1] + qd * dt * dt
        p = arrayOf(doubleArrayOf(p00, p01), doubleArrayOf(p10, p11))
    }

    fun update(zm: Double, sigma: Double) {
        var r = max(sigma, 0.05).pow(2)
        var s = p[0][0] + r
        val innov = zm - z
        if (innov * innov > 16 * s) { r *= 4; s = p[0][0] + r }
        val k0 = p[0][0] / s; val k1 = p[1][0] / s
        z += k0 * innov; v += k1 * innov
        p = arrayOf(
            doubleArrayOf((1 - k0) * p[0][0], (1 - k0) * p[0][1]),
            doubleArrayOf(p[1][0] - k1 * p[0][0], p[1][1] - k1 * p[0][1]))
    }
}

class Track(val label: String, var box: DoubleArray, var est: RangeEst?, t: Double, var conf: Double) {
    val id = nextId++
    var hits = 1
    var tLast = t
    var kf: RangeKalman? = est?.let { RangeKalman(it.z, it.sigma) }
    private val sizeHist = ArrayDeque<Pair<Double, Double>>()
    private val xHist = ArrayDeque<Pair<Double, Double>>()
    var vx = 0.0
    var ttc: Double? = null
    var closingSpeed = 0.0

    val z: Double? get() = kf?.z ?: est?.z
    val bearing: Double get() = est?.bearing ?: 0.0
    val x: Double? get() { val e = est ?: return null; return (z ?: return null) * tan(Math.toRadians(e.bearing)) }

    fun pushSize(t: Double, b: DoubleArray, fw: Double, fh: Double) {
        val e = 3.0
        val s = when {
            b[1] > e && b[3] < fh - e -> b[3] - b[1]
            b[0] > e && b[2] < fw - e -> b[2] - b[0]
            else -> { sizeHist.clear(); return }
        }
        if (s > 4) { sizeHist.addLast(Pair(t, ln(s))); while (sizeHist.size > 14) sizeHist.removeFirst() }
    }

    private fun lateralVelocity(): Double {
        val h = xHist
        if (h.size < 5 || h.last().first - h.first().first < 0.5) return 0.0
        val n = h.size
        val mt = h.sumOf { it.first } / n; val mx = h.sumOf { it.second } / n
        val sxx = h.sumOf { (it.first - mt).pow(2) }
        if (sxx < 1e-6) return 0.0
        val v = h.sumOf { (it.first - mt) * (it.second - mx) } / sxx
        return if (abs(v) > 0.5) v else 0.0
    }

    private fun loomingTtc(): Double? {
        val h = sizeHist
        if (h.size < 5 || h.last().first - h.first().first < 0.4) return null
        val n = h.size
        val mt = h.sumOf { it.first } / n; val my = h.sumOf { it.second } / n
        val sxx = h.sumOf { (it.first - mt).pow(2) }
        if (sxx < 1e-6) return null
        val m = h.sumOf { (it.first - mt) * (it.second - my) } / sxx
        val res = h.sumOf { (it.second - (my + m * (it.first - mt))).pow(2) }
        val se = sqrt(res / max(n - 2, 1) / sxx)
        if (m > max(0.06, 2.5 * se)) { val tau = 1.0 / m; return if (tau < 20) tau else null }
        return null
    }

    fun update(b: DoubleArray, e: RangeEst?, t: Double, c: Double, fw: Double, fh: Double) {
        val dt = t - tLast
        box = b; conf = c; hits++; tLast = t
        pushSize(t, b, fw, fh)
        if (e != null) {
            val k = kf
            if (k == null) kf = RangeKalman(e.z, e.sigma) else { k.predict(dt); k.update(e.z, e.sigma) }
            est = e
            val cx = x
            if (cx != null) {
                xHist.addLast(Pair(t, cx))
                while (xHist.size > 10) xHist.removeFirst()
            }
            vx = lateralVelocity()
        }
        val tl = loomingTtc()
        var tk: Double? = null
        val filter = kf
        if (filter != null) {
            closingSpeed = -filter.v
            if (closingSpeed > 0.25 && filter.z > 0) tk = filter.z / closingSpeed
        }
        val tkv = tk
        ttc = if (tl != null && tkv != null) min(tl, tkv) else (tl ?: tkv)
    }

    companion object { var nextId = 0 }
}

class Tracker {
    val tracks = HashMap<Int, Track>()

    fun update(dets: List<Det>, t: Double, fw: Double, fh: Double): List<Track> {
        data class Pair3(val score: Double, val di: Int, val tid: Int)
        val pairs = mutableListOf<Pair3>()
        for ((di, d) in dets.withIndex()) for ((tid, tr) in tracks) {
            if (family(tr.label) != family(d.label)) continue
            val ov = iou(tr.box, d.box)
            val score: Double
            if (ov < 0.05) {
                val cx1 = (tr.box[0] + tr.box[2]) / 2; val cy1 = (tr.box[1] + tr.box[3]) / 2
                val cx2 = (d.box[0] + d.box[2]) / 2; val cy2 = (d.box[1] + d.box[3]) / 2
                val diag = hypot(tr.box[2] - tr.box[0], tr.box[3] - tr.box[1]) + 1
                val dist = hypot(cx1 - cx2, cy1 - cy2) / diag
                if (dist > 0.5) continue
                score = 0.05 - dist * 0.05
            } else score = ov
            pairs += Pair3(score, di, tid)
        }
        pairs.sortByDescending { it.score }
        val usedD = HashSet<Int>(); val usedT = HashSet<Int>(); val touched = mutableListOf<Track>()
        for (p in pairs) {
            if (p.di in usedD || p.tid in usedT) continue
            usedD += p.di; usedT += p.tid
            val d = dets[p.di]; val tr = tracks[p.tid]!!
            tr.update(d.box, d.est, t, d.conf, fw, fh); touched += tr
        }
        for ((di, d) in dets.withIndex()) {
            if (di in usedD) continue
            val tr = Track(d.label, d.box, d.est, t, d.conf)
            tr.pushSize(t, d.box, fw, fh)
            tracks[tr.id] = tr; touched += tr
        }
        tracks.entries.removeAll { t - it.value.tLast > Cfg.TRACK_MAX_AGE }
        return touched.filter { confirmed(it) }
    }

    private fun confirmed(tr: Track): Boolean {
        if (tr.hits >= Cfg.MIN_HITS) return true
        val z = tr.z
        return z != null && z <= Cfg.MIN_HITS_CLOSE
    }
}
