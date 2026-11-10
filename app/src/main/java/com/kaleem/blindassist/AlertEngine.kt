package com.kaleem.blindassist

import kotlin.math.abs
import kotlin.math.max

enum class Level { NONE, INFO, WARNING, CRITICAL }

sealed class Ev {
    class Speech(val text: String, val pri: Int, val ttl: Double, val key: String) : Ev()
    class Beep(val pan: Double, val freq: Double, val dur: Double) : Ev()
    object Vibrate : Ev()
}

const val P_CRITICAL = 0; const val P_ANSWER = 1; const val P_NAV = 2; const val P_INFO = 3

fun classify(tr: Track): Level {
    val z = tr.z ?: return Level.NONE
    val ttc = tr.ttc
    val lab = tr.label
    val scale = if (lab in Cfg.VEHICLES) Cfg.VEHICLE_SCALE else 1.0
    val path = Avoidance.inPath(tr)

    if (lab in Cfg.GROUND_HAZARDS) {
        val x = tr.x
        val nearLat = x != null && abs(x) < Avoidance.corridorHalf(z) + 0.6
        if (nearLat && z <= Cfg.WARNING_DIST + 0.5)
            return if (z <= Cfg.CRITICAL_DIST + 0.4) Level.CRITICAL else Level.WARNING
        return Level.NONE
    }
    if (path) {
        if (z <= Cfg.CRITICAL_DIST * scale) return Level.CRITICAL
        if (ttc != null && ttc <= Cfg.CRITICAL_TTC && z < 8 * scale) return Level.CRITICAL
        if (z <= Cfg.WARNING_DIST * scale) return Level.WARNING
        if (ttc != null && ttc <= Cfg.WARNING_TTC) return Level.WARNING
        if (z <= Cfg.INFO_DIST * scale) return Level.INFO
        return Level.NONE
    }
    val x = tr.x
    if (lab in Cfg.DYNAMIC && ttc != null && ttc <= 3.0 && x != null) {
        val xPred = x + tr.vx * minOf(ttc, 3.0)
        if (abs(xPred) < Avoidance.corridorHalf(z) + 0.3 && tr.vx != 0.0) return Level.WARNING
    }
    if (x != null && abs(x) < 0.9 && z < 1.2) return Level.INFO
    return Level.NONE
}

/** Edge-triggered, hysteresis-stabilised alerts; one speaker at a time; beeps + haptics carry urgency. */
class AlertEngine(var lang: String) {
    private val announced = HashMap<Int, Level>()
    private val levelSince = HashMap<Int, Pair<Level, Double>>()
    private val shown = HashMap<Int, Level>()
    private var lastCriticalT = -1e9
    private var lastVibeT = -1e9
    private var lastGuideSig = ""
    private var lastGuideT = 0.0
    private var nextBeepT = 0.0

    private fun effective(tr: Track, raw: Level, now: Double): Level {
        val prev = shown[tr.id] ?: Level.NONE
        if (raw >= prev) { shown[tr.id] = raw; levelSince[tr.id] = raw to now; return raw }
        val (lv, t0) = levelSince[tr.id] ?: (raw to now)
        if (lv != raw) { levelSince[tr.id] = raw to now; return prev }
        if (now - t0 >= Cfg.DEESCALATE_AFTER) { shown[tr.id] = raw; return raw }
        return prev
    }

    private fun gc(live: Set<Int>) {
        announced.keys.retainAll(live); levelSince.keys.retainAll(live); shown.keys.retainAll(live)
    }

    fun update(confirmed: List<Track>, all: List<Track>, now: Double): List<Ev> {
        val ev = mutableListOf<Ev>()
        gc(all.map { it.id }.toSet())
        val scored = mutableListOf<Pair<Level, Track>>()
        for (tr in confirmed) {
            val lvl = effective(tr, classify(tr), now)
            if (lvl > Level.NONE) scored += lvl to tr
        }
        if (scored.isEmpty()) return ev
        scored.sortWith(Comparator<Pair<Level, Track>> { a, b ->
            val byLevel = b.first.ordinal.compareTo(a.first.ordinal)
            if (byLevel != 0) byLevel
            else {
                val byTtc = (a.second.ttc ?: 99.0).compareTo(b.second.ttc ?: 99.0)
                if (byTtc != 0) byTtc else (a.second.z ?: 99.0).compareTo(b.second.z ?: 99.0)
            }
        })
        val (topLevel, top) = scored[0]
        val guide = Avoidance.plan(all.filter { it.hits >= 2 || (it.z ?: 9.0) < 1.5 })

        if (topLevel >= Level.WARNING && now >= nextBeepT) {
            val z = top.z ?: 5.0
            val u = urgency(z, top.ttc)
            nextBeepT = now + 0.9 - 0.75 * u
            ev += Ev.Beep((top.bearing / 35.0).coerceIn(-1.0, 1.0), 500 + 700 * u, 0.06 + 0.05 * u)
        }
        if (topLevel == Level.CRITICAL && now - lastVibeT > 0.9) { lastVibeT = now; ev += Ev.Vibrate }

        val prev = announced[top.id] ?: Level.NONE
        var speak = topLevel > prev
        if (topLevel == Level.CRITICAL && now - lastCriticalT > Cfg.CRITICAL_REPEAT) speak = true
        if (topLevel == Level.INFO && !Cfg.verbose) speak = false
        if (speak) {
            announced[top.id] = if (topLevel > prev) topLevel else prev
            if (topLevel == Level.CRITICAL) lastCriticalT = now
            ev += compose(top, topLevel, guide)
            lastGuideSig = guide.state + guide.side; lastGuideT = now
        } else if (topLevel >= Level.WARNING && (guide.state == "veer" || guide.state == "blocked")) {
            val sig = guide.state + guide.side
            if ((sig != lastGuideSig && now - lastGuideT > 1.0) || now - lastGuideT > Cfg.GUIDANCE_REPEAT) {
                lastGuideSig = sig; lastGuideT = now
                ev += Ev.Speech(guideText(guide), if (topLevel == Level.CRITICAL) P_CRITICAL else 1,
                    Cfg.TTL_CRITICAL, "guidance")
            }
        }
        return ev
    }

    private fun urgency(z: Double, ttc: Double?): Double {
        val ud = 1.0 - ((z - 0.8) / 4.0).coerceIn(0.0, 1.0)
        val ut = if (ttc == null) 0.0 else 1.0 - ((ttc - 0.8) / 4.0).coerceIn(0.0, 1.0)
        return max(ud, ut)
    }

    private fun guideText(g: Guidance) = when (g.state) {
        "veer" -> I18n.t(lang, if (g.side == "left") "move_left" else "move_right")
        "blocked" -> I18n.t(lang, "blocked")
        else -> ""
    }

    private fun compose(tr: Track, level: Level, g: Guidance): Ev.Speech {
        val lab = I18n.label(lang, tr.label)
        val dir = I18n.direction(lang, tr.bearing)
        val dist = I18n.dist(lang, tr.z)
        val key = when {
            level == Level.CRITICAL -> "critical"
            tr.closingSpeed > 0.4 || (tr.ttc != null && tr.ttc!! < 6) -> "approaching"
            else -> "obstacle"
        }
        var text = I18n.t(lang, key, "label" to lab, "dir" to dir, "dist" to dist)
        val gt = guideText(g)
        if (gt.isNotEmpty() && level >= Level.WARNING) text = "$text $gt"
        return if (level == Level.CRITICAL) Ev.Speech(text, P_CRITICAL, Cfg.TTL_CRITICAL, "crit-${tr.id}")
        else Ev.Speech(text, if (level == Level.WARNING) 1 else P_INFO, Cfg.TTL_INFO, "obs-${tr.id}")
    }
}

class Pipeline(val cam: Cam, lang: String) {
    val tracker = Tracker()
    val alerts = AlertEngine(lang)
    @Volatile var lastTracks: List<Track> = emptyList()

    fun process(dets: List<Det>, t: Double, fw: Double, fh: Double, pitch: Double?): List<Ev> {
        val enriched = dets.mapNotNull { d ->
            val e = estimateRange(d.label, d.box, fw, fh, cam, pitch) ?: return@mapNotNull null
            d.est = e; d
        }
        val confirmed = tracker.update(enriched, t, fw, fh)
        val all = tracker.tracks.values.toList()
        lastTracks = all
        return alerts.update(confirmed, all, t)
    }
}
