package com.kaleem.blindassist

import android.content.Context
import android.media.*
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.*
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Stereo, panned beeps: pitch + rate rise with urgency, pan follows the obstacle bearing. */
class Earcons {
    private val sr = 22050
    private val ex = Executors.newSingleThreadScheduledExecutor()
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()

    fun beep(pan: Double, freq: Double, dur: Double) {
        ex.execute {
            try {
                val n = (sr * dur).toInt()
                val buf = ShortArray(n * 2)
                val ang = (pan.coerceIn(-1.0, 1.0) + 1) * PI / 4
                val l = cos(ang); val r = sin(ang)
                val fade = (0.005 * sr).toInt().coerceAtMost(n / 2).coerceAtLeast(1)
                for (i in 0 until n) {
                    var env = 1.0
                    if (i < fade) env = i / fade.toDouble() else if (i > n - fade) env = (n - i) / fade.toDouble()
                    val s = sin(2 * PI * freq * i / sr) * env * 0.7 * 32767
                    buf[2 * i] = (s * l).toInt().toShort(); buf[2 * i + 1] = (s * r).toInt().toShort()
                }
                val t = AudioTrack.Builder().setAudioAttributes(attrs)
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                    .setBufferSizeInBytes(buf.size * 2).setTransferMode(AudioTrack.MODE_STATIC).build()
                t.write(buf, 0, buf.size)
                t.play()
                ex.schedule(Runnable { try { t.release() } catch (ignored: Exception) {} }, (dur * 1000).toLong() + 150, TimeUnit.MILLISECONDS)
            } catch (ignored: Exception) {}
        }
    }

    fun shutdown() = ex.shutdownNow()
}

@Suppress("DEPRECATION")
fun vibrateOnce(ctx: Context) {
    val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
    val pattern = longArrayOf(0, 120, 60, 120)
    if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(pattern, -1))
    else v.vibrate(pattern, -1)
}

/** Priority speech: critical preempts, stale items expire (TTL), interrupted answers are re-queued. */
class Speech(ctx: Context, var lang: String) {
    private class Item(val text: String, val pri: Int, var exp: Long, val key: String?, val lang: String, val seq: Long)

    private val seq = java.util.concurrent.atomic.AtomicLong()
    private val q = PriorityBlockingQueue<Item>(16, compareBy<Item>({ it.pri }, { it.seq }))
    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    @Volatile private var running = true
    @Volatile private var current: Item? = null
    @Volatile private var latch = CountDownLatch(0)

    init {
        tts = TextToSpeech(ctx.applicationContext) { st ->
            if (st == TextToSpeech.SUCCESS) {
                tts?.setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                tts?.setSpeechRate(1.15f)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { latch.countDown() }
                    @Deprecated("Deprecated in Java") override fun onError(id: String?) { latch.countDown() }
                    override fun onStop(id: String?, interrupted: Boolean) { latch.countDown() }
                })
                ready = true
            }
        }
        Thread({ loop() }, "speech").start()
    }

    fun say(text: String, pri: Int = P_INFO, ttl: Double = 3.0, key: String? = null) {
        if (text.isBlank()) return
        if (key != null) q.removeIf { it.key == key }
        val item = Item(text, pri, System.currentTimeMillis() + (ttl * 1000).toLong(), key, lang, seq.incrementAndGet())
        val cur = current
        if (pri == P_CRITICAL && cur != null && cur.pri > P_CRITICAL) {
            if (cur.pri == P_ANSWER || cur.pri == P_NAV) { cur.exp = System.currentTimeMillis() + 15000; q.add(cur) }
            q.add(item)
            tts?.stop()
        } else {
            if (pri > P_CRITICAL && cur != null && cur.pri == P_INFO) { /* let it finish */ }
            q.add(item)
        }
    }

    private fun loop() {
        while (running) {
            val it = q.poll(300, TimeUnit.MILLISECONDS) ?: continue
            if (!ready) { q.add(it); Thread.sleep(200); continue }
            if (System.currentTimeMillis() > it.exp) continue      // stale: describes the past
            current = it
            try {
                val loc = when (it.lang) { "hi" -> Locale("hi", "IN"); "te" -> Locale("te", "IN"); else -> Locale("en", "IN") }
                tts?.setLanguage(loc)
                latch = CountDownLatch(1)
                tts?.speak(it.text, TextToSpeech.QUEUE_FLUSH, null, "u${it.seq}")
                latch.await(8, TimeUnit.SECONDS)
            } catch (ignored: Exception) {}
            current = null
        }
    }

    fun shutdown() { running = false; tts?.stop(); tts?.shutdown() }
}
