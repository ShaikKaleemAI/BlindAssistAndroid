package com.kaleem.blindassist

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.min

/**
 * Runs the whole assistant on the phone, with the screen off and the phone in a pocket:
 * camera -> detector -> range fusion -> tracker -> alerts -> speech / panned beeps / vibration.
 */
@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
class AssistService : LifecycleService() {
    companion object {
        const val ACTION_STOP = "com.kaleem.blindassist.STOP"
        @Volatile var running = false
    }

    private val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()
    private var speech: Speech? = null
    private var earcons: Earcons? = null
    private var sensors: PocketSensors? = null
    private var detector: Detector? = null
    @Volatile private var pipeline: Pipeline? = null
    private var wake: PowerManager.WakeLock? = null
    private var session: MediaSession? = null

    private val stats = FrameStats()
    private var vitals: DeviceVitals? = null
    private var logger: RunLogger? = null
    private var startT = 0.0
    @Volatile private var lastDecodeMs = 0.0
    @Volatile private var lastDetectMs = 0.0
    @Volatile private var lastPipeMs = 0.0
    @Volatile private var lastCapMs = Double.NaN

    @Volatile private var lastFrameT = 0.0
    @Volatile private var armedAt = Double.MAX_VALUE
    private var cameraLost = false
    private var slowCount = 0
    private var darkSince = 0.0
    private var lastWarnT = 0.0
    private val lang get() = Cfg.lang

    private fun nowS() = SystemClock.elapsedRealtime() / 1000.0

    override fun onCreate() {
        super.onCreate()
        running = true
        val sp = getSharedPreferences("ba", Context.MODE_PRIVATE)
        Cfg.lang = sp.getString("lang", "en") ?: "en"
        Cfg.camHeightM = sp.getFloat("height_cm", 110f) / 100.0
        startFg()
        speech = Speech(this, Cfg.lang)
        earcons = Earcons()
        sensors = PocketSensors(this).also { it.start() }
        startT = nowS()
        vitals = DeviceVitals(this)
        logger = try { RunLogger(this, "live") } catch (e: Exception) { Log.e("BA", "logger init failed", e); null }
        wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "blindassist:run").also { it.acquire(4 * 60 * 60 * 1000L) }
        detector = try { Detector(this) } catch (e: Throwable) {
            Log.e("BA", "detector init failed", e)
            speech?.say(I18n.t(lang, "no_model"), P_CRITICAL, 10.0); stopSelf(); return
        }
        speech?.say(I18n.t(lang, "starting"), P_ANSWER, 30.0, "start")
        armedAt = nowS() + 10.0
        main.postDelayed({ speech?.say(I18n.t(lang, "ready"), P_ANSWER, 5.0, "ready") }, 10_000)
        bindCamera()
        setupSession()
        main.postDelayed(watchdog, 2000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) stopSelf()
        return START_NOT_STICKY
    }

    private fun startFg() {
        val ch = "blindassist"
        if (Build.VERSION.SDK_INT >= 26)
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(ch, "Blind Assist", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this, AssistService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, ch).setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Blind Assist is running").setOngoing(true).addAction(0, "Stop", stop).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        else startForeground(1, n)
    }

    private fun bindCamera() {
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({
            try {
                val provider = f.get()
                val rs = ResolutionSelector.Builder().setResolutionStrategy(
                    ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build()
                val analysis = ImageAnalysis.Builder().setResolutionSelector(rs)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(exec) { img -> analyze(img) }
                provider.unbindAll()
                val cam = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
                readHfov(cam)?.let {
                    Cfg.hfovDeg = it
                    getSharedPreferences("ba", Context.MODE_PRIVATE).edit().putFloat("hfov_deg", it.toFloat()).apply()
                }
                logger?.writeMeta("hfov_deg=${Cfg.hfovDeg}\ncam_height_m=${Cfg.camHeightM}\nlang=${Cfg.lang}\n" +
                    "device=${Build.MANUFACTURER} ${Build.MODEL}\nsdk=${Build.VERSION.SDK_INT}\n")
                pipeline = Pipeline(Cam(Cfg.hfovDeg, Cfg.camHeightM, Cfg.defaultPitchDeg), Cfg.lang)
                lastFrameT = nowS()
            } catch (e: Exception) { Log.e("BA", "camera bind failed", e) }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Real horizontal field of view in PORTRAIT (uses the sensor's short side). */
    private fun readHfov(cam: Camera): Double? = try {
        val c2 = Camera2CameraInfo.from(cam.cameraInfo)
        val fl = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
        val sz = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        if (fl != null && sz != null) {
            val d = Math.toDegrees(2 * atan(min(sz.width, sz.height) / (2.0 * fl)))
            if (d in 30.0..90.0) d else null
        } else null
    } catch (e: Exception) { null }

    private fun analyze(img: ImageProxy) {
        try {
            val p = pipeline ?: return
            val det = detector ?: return
            val sen = sensors ?: return
            val n0 = SystemClock.elapsedRealtimeNanos()
            val now = n0 / 1e9                       // same time base as nowS()
            // Capture->analyzer delay: only meaningful if the camera timestamps are on the realtime clock (unverified per device).
            val capMs = ((n0 - img.imageInfo.timestamp) / 1e6).let { if (it in 0.0..2000.0) it else Double.NaN }
            lastFrameT = now
            val pitch = sen.pitchDeg()
            val rot = img.imageInfo.rotationDegrees + if (sen.upsideDown()) 180 else 0
            val raw = img.toBitmap()
            val up = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
            checkHealth(up, now, pitch)
            val n1 = SystemClock.elapsedRealtimeNanos()
            val dets = det.detect(up)
            val n2 = SystemClock.elapsedRealtimeNanos()
            val evs = p.process(dets, now, up.width.toDouble(), up.height.toDouble(), pitch)
            val n3 = SystemClock.elapsedRealtimeNanos()
            dispatch(evs, now)
            val n4 = SystemClock.elapsedRealtimeNanos()
            lastDecodeMs = (n1 - n0) / 1e6; lastDetectMs = (n2 - n1) / 1e6; lastPipeMs = (n3 - n2) / 1e6; lastCapMs = capMs
            val ms = (n4 - n0) / 1e6
            // Latency = capture->alert-dispatched when the capture delay is known, else analyzer time only. Excludes TTS/audio start.
            stats.record(now, ms + if (capMs.isNaN()) 0.0 else capMs)
            slowCount = if (ms > 450) slowCount + 1 else 0
            if (slowCount == 5 && now >= armedAt) speech?.say(I18n.t(lang, "slow"), P_ANSWER, 5.0, "slow")
        } catch (e: Throwable) { Log.e("BA", "analyze", e) } finally { img.close() }
    }

    /** Once per second (called from the watchdog): one CSV row + one logcat line with fps, latency, battery, thermal. */
    private fun logTick(now: Double) {
        val lg = logger ?: return
        if (stats.count() == 0) return
        val v = vitals?.read() ?: return
        val fps = stats.fps(); val p50 = stats.percentile(50.0); val p95 = stats.percentile(95.0)
        lg.row(now - startT, fps, p50, p95, lastDecodeMs, lastDetectMs, lastPipeMs, lastCapMs, v,
            pipeline?.lastTracks?.size ?: 0, slowCount)
        Log.i("BA", String.format(java.util.Locale.US, "fps=%.1f p50=%.0fms p95=%.0fms detect=%.0fms batt=%d%% %.1fC thermal=%d",
            fps, p50, p95, lastDetectMs, v.batteryPct, v.batteryTempC, v.thermalStatus))
    }

    private fun dispatch(evs: List<Ev>, now: Double) {
        if (now < armedAt) return
        for (e in evs) when (e) {
            is Ev.Speech -> speech?.say(e.text, e.pri, e.ttl, e.key)
            is Ev.Beep -> earcons?.beep(e.pan, e.freq, e.dur)
            Ev.Vibrate -> vibrateOnce(this)
        }
    }

    private fun checkHealth(b: Bitmap, now: Double, pitch: Double) {
        if (now < armedAt) return
        var sum = 0.0
        val sx = b.width / 16; val sy = b.height / 16
        for (yy in 0 until 16) for (xx in 0 until 16) {
            val c = b.getPixel(xx * sx + sx / 2, yy * sy + sy / 2)
            sum += 0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)
        }
        if (sum / 256 < 15) {
            if (darkSince == 0.0) darkSince = now
            if (now - darkSince > 2 && now - lastWarnT > 10) { lastWarnT = now; speech?.say(I18n.t(lang, "covered"), P_ANSWER, 5.0, "covered") }
        } else darkSince = 0.0
        if (abs(pitch) > 55 && now - lastWarnT > 10) { lastWarnT = now; speech?.say(I18n.t(lang, "not_upright"), P_ANSWER, 5.0, "upright") }
    }

    private val watchdog = object : Runnable {
        override fun run() {
            val now = nowS()
            if (now > armedAt) {
                val stalled = now - lastFrameT > 1.5
                if (stalled && !cameraLost) { cameraLost = true; speech?.say(I18n.t(lang, "camera_lost"), P_CRITICAL, 5.0, "lost") }
                if (!stalled && cameraLost) { cameraLost = false; speech?.say(I18n.t(lang, "camera_back"), P_ANSWER, 5.0, "back") }
            }
            logTick(now)
            main.postDelayed(this, 1000)
        }
    }

    /** Earphone button (single press) = "what is around me". */
    private fun setupSession() {
        val s = MediaSession(this, "BlindAssist")
        s.setCallback(object : MediaSession.Callback() {
            override fun onPlay() = summarize()
            override fun onPause() = summarize()
        })
        s.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE)
            .setState(PlaybackState.STATE_PLAYING, 0, 1f).build())
        s.isActive = true
        session = s
    }

    private fun summarize() {
        val now = nowS()
        val near = (pipeline?.lastTracks ?: emptyList()).filter { it.z != null && now - it.tLast < 1.0 }
            .sortedBy { it.z }.take(3)
        val text = if (near.isEmpty()) I18n.t(lang, "nothing")
        else near.joinToString(" ") {
            I18n.t(lang, "obstacle", "label" to I18n.label(lang, it.label),
                "dir" to I18n.direction(lang, it.bearing), "dist" to I18n.dist(lang, it.z))
        }
        speech?.say(text, P_ANSWER, 10.0, "summary")
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacksAndMessages(null)
        speech?.say(I18n.t(lang, "stopped"), P_CRITICAL, 3.0, "bye")
        val sp = speech
        Handler(Looper.getMainLooper()).postDelayed({ sp?.shutdown() }, 2500)
        session?.release()
        sensors?.stop()
        earcons?.shutdown()
        exec.shutdown()
        try { detector?.close() } catch (ignored: Exception) {}
        logger?.close()
        try { if (wake?.isHeld == true) wake?.release() } catch (ignored: Exception) {}
        super.onDestroy()
    }
}
