package com.kaleem.blindassist

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.util.Locale
import kotlin.math.ceil

/**
 * Rolling fps + latency statistics. Pure Kotlin (no Android) so it is unit-tested on the JVM.
 * fps = processed frames per second (NOT camera fps: CameraX drops frames silently under load).
 */
class FrameStats(private val window: Int = 120) {
    private val ts = ArrayDeque<Double>()
    private val lat = ArrayDeque<Double>()

    @Synchronized fun record(tSec: Double, latencyMs: Double) {
        ts.addLast(tSec); lat.addLast(latencyMs)
        while (ts.size > window) { ts.removeFirst(); lat.removeFirst() }
    }

    @Synchronized fun count() = ts.size

    @Synchronized fun fps(): Double {
        if (ts.size < 2) return 0.0
        val span = ts.last() - ts.first()
        return if (span > 1e-6) (ts.size - 1) / span else 0.0
    }

    /** Nearest-rank percentile of the latency window (p in 0..100). */
    @Synchronized fun percentile(p: Double): Double {
        if (lat.isEmpty()) return 0.0
        val s = lat.sorted()
        val idx = (ceil(p / 100.0 * s.size).toInt() - 1).coerceIn(0, s.size - 1)
        return s[idx]
    }
}

class Vitals(val batteryPct: Int, val batteryTempC: Double, val charging: Boolean,
             val thermalStatus: Int, val thermalHeadroom: Float)

/**
 * Battery temperature is the BATTERY sensor, not the SoC: a lower bound on how hot the chip is.
 * thermalStatus: 0 none .. 3 severe, 4 critical, 5 emergency, 6 shutdown (-1 = API < 29).
 * thermalHeadroom: ~1.0 means throttling starts; NaN if the device does not report it.
 */
class DeviceVitals(private val ctx: Context) {
    private val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager

    fun read(): Vitals {
        val i: Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct = if (level >= 0 && scale > 0) (100 * level / scale) else -1
        val temp = (i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE)
            .let { if (it == Int.MIN_VALUE) Double.NaN else it / 10.0 }
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val thermal = if (Build.VERSION.SDK_INT >= 29) pm.currentThermalStatus else -1
        val head = if (Build.VERSION.SDK_INT >= 30) pm.getThermalHeadroom(10) else Float.NaN
        return Vitals(pct, temp, charging, thermal, head)
    }
}

/** One CSV row per second, in <external files>/logs/ (no storage permission needed; pull with adb or Files app). */
class RunLogger(ctx: Context, tag: String) {
    val file: File
    private val w: BufferedWriter

    init {
        val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "logs").apply { mkdirs() }
        file = File(dir, "${tag}_${System.currentTimeMillis()}.csv")
        w = BufferedWriter(FileWriter(file))
        w.write("t_s,fps,lat_p50_ms,lat_p95_ms,decode_ms,detect_ms,pipeline_ms,cap_to_analyze_ms," +
            "batt_pct,batt_temp_c,charging,thermal_status,thermal_headroom,tracks,slow_streak\n")
        w.flush()
    }

    @Synchronized fun row(tSec: Double, fps: Double, p50: Double, p95: Double, decodeMs: Double, detectMs: Double,
                          pipeMs: Double, capMs: Double, v: Vitals, tracks: Int, slow: Int) {
        val l = Locale.US
        w.write(String.format(l, "%.2f,%.1f,%.0f,%.0f,%.1f,%.1f,%.1f,%.0f,%d,%.1f,%d,%d,%.2f,%d,%d\n",
            tSec, fps, p50, p95, decodeMs, detectMs, pipeMs, capMs, v.batteryPct, v.batteryTempC,
            if (v.charging) 1 else 0, v.thermalStatus, v.thermalHeadroom, tracks, slow))
        w.flush()
    }

    /** Sidecar with the settings this run used (hfov, camera height...) so a replay can match them. */
    fun writeMeta(text: String) { try { File(file.path + ".meta.txt").writeText(text) } catch (ignored: Exception) {} }

    @Synchronized fun close() { try { w.close() } catch (ignored: Exception) {} }
}
