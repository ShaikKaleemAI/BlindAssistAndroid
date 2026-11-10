package com.kaleem.blindassist

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import java.io.File
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

/** CSV-safe text field. */
internal fun csvEscape(s: String): String = "\"" + s.replace("\"", "\"\"") + "\""

class ReplayConfig(
    val sampleFps: Double = 10.0,               // frames per video-second fed to the pipeline
    val camHeightM: Double = Cfg.camHeightM,    // must match where the phone was when the clip was recorded
    val hfovDeg: Double = Cfg.hfovDeg,          // must match the phone that recorded the clip
    val pitchDeg: Double = Cfg.defaultPitchDeg, // video has no IMU: constant pitch (Stage 4 adds IMU sidecar)
    val maxLongSide: Int = 640,                 // same scale as the live analysis stream
    val rotateDeg: Int = 0                      // extra rotation if the clip comes out sideways
)

class ReplaySummary(
    val frames: Int, val speech: Int, val beeps: Int, val vibes: Int,
    val meanMs: Double, val p95Ms: Double, val files: List<File>
)

/**
 * Runs a recorded video through Detector -> RangeFusion -> Tracker -> AlertEngine exactly like the live service.
 * The clock handed to the pipeline is VIDEO time, so the same clip + same model gives the same alerts every run.
 * Differences from live (be honest about them): no frame dropping, no IMU, no audio/TTS latency.
 * Writes three CSVs to <external files>/replay/: frames, events (what would have been said/beeped), dets.
 */
class ReplayRunner(private val ctx: Context, private val detector: FrameDetector, private val cfg: ReplayConfig) {
    @Volatile var cancelled = false

    fun run(uri: Uri, tag: String, onProgress: (done: Int, total: Int) -> Unit): ReplaySummary {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(ctx, uri)
            val durMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val total = ceil(durMs / 1000.0 * cfg.sampleFps).toInt().coerceAtLeast(1)
            val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "replay").apply { mkdirs() }
            val base = "${tag}_${System.currentTimeMillis()}"
            val fFrames = File(dir, "${base}_frames.csv")
            val fEvents = File(dir, "${base}_events.csv")
            val fDets = File(dir, "${base}_dets.csv")

            val pipe = Pipeline(Cam(cfg.hfovDeg, cfg.camHeightM, cfg.pitchDeg), Cfg.lang)
            val procMs = ArrayList<Double>()
            var speech = 0; var beeps = 0; var vibes = 0; var frames = 0
            val step = 1.0 / cfg.sampleFps
            val l = Locale.US

            fFrames.bufferedWriter().use { wf ->
                fEvents.bufferedWriter().use { we ->
                    fDets.bufferedWriter().use { wd ->
                        wf.write("t_video_s,proc_ms,n_dets,n_tracks\n")
                        we.write("t_video_s,kind,priority,text\n")
                        wd.write("t_video_s,label,conf,x1,y1,x2,y2,z_m,bearing_deg,flags\n")
                        var i = 0
                        while (!cancelled) {
                            val tVid = i * step
                            i++
                            if (tVid * 1000.0 > durMs) break
                            val raw = mmr.getFrameAtTime((tVid * 1_000_000.0).toLong(), MediaMetadataRetriever.OPTION_CLOSEST)
                                ?: continue
                            val t0 = SystemClock.elapsedRealtimeNanos()
                            val bmp = prepare(raw)
                            val dets = detector.detect(bmp)
                            val evs = pipe.process(dets, tVid, bmp.width.toDouble(), bmp.height.toDouble(), cfg.pitchDeg)
                            val ms = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
                            procMs += ms; frames++
                            wf.write(String.format(l, "%.3f,%.1f,%d,%d\n", tVid, ms, dets.size, pipe.lastTracks.size))
                            for (d in dets) {
                                val e = d.est
                                wd.write(String.format(l, "%.3f,%s,%.2f,%.0f,%.0f,%.0f,%.0f,%s,%s,%s\n", tVid, d.label, d.conf,
                                    d.box[0], d.box[1], d.box[2], d.box[3],
                                    if (e != null) String.format(l, "%.2f", e.z) else "",
                                    if (e != null) String.format(l, "%.1f", e.bearing) else "",
                                    if (e != null) e.flags.joinToString("|") else "no_range"))
                            }
                            for (ev in evs) when (ev) {
                                is Ev.Speech -> { speech++; we.write(String.format(l, "%.3f,speech,%d,%s\n", tVid, ev.pri, csvEscape(ev.text))) }
                                is Ev.Beep -> { beeps++; we.write(String.format(l, "%.3f,beep,,%s\n", tVid, csvEscape(String.format(l, "pan=%.2f freq=%.0f", ev.pan, ev.freq)))) }
                                Ev.Vibrate -> { vibes++; we.write(String.format(l, "%.3f,vibrate,,\"\"\n", tVid)) }
                            }
                            if (frames % 5 == 0) onProgress(frames, total)
                        }
                    }
                }
            }
            onProgress(frames, total)
            val sorted = procMs.sorted()
            val p95 = if (sorted.isEmpty()) 0.0 else sorted[(ceil(0.95 * sorted.size).toInt() - 1).coerceIn(0, sorted.size - 1)]
            return ReplaySummary(frames, speech, beeps, vibes, if (procMs.isEmpty()) 0.0 else procMs.average(), p95,
                listOf(fFrames, fEvents, fDets))
        } finally {
            try { mmr.release() } catch (ignored: Exception) {}
        }
    }

    private fun prepare(raw: Bitmap): Bitmap {
        var b = raw
        if (cfg.rotateDeg != 0)
            b = Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(cfg.rotateDeg.toFloat()) }, true)
        val longSide = maxOf(b.width, b.height)
        if (longSide > cfg.maxLongSide) {
            val s = cfg.maxLongSide.toDouble() / longSide
            b = Bitmap.createScaledBitmap(b, (b.width * s).roundToInt(), (b.height * s).roundToInt(), true)
        }
        if (b.config != Bitmap.Config.ARGB_8888) b = b.copy(Bitmap.Config.ARGB_8888, false)
        return b
    }
}
