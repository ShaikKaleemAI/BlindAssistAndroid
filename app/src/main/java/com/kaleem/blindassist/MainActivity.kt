package com.kaleem.blindassist

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/** One giant START/STOP button (TalkBack friendly) + two settings set once by a sighted helper. */
class MainActivity : AppCompatActivity() {
    private lateinit var btn: Button
    private lateinit var replayBtn: Button
    private lateinit var status: TextView
    private lateinit var heightEt: EditText
    private lateinit var rg: RadioGroup
    private val langs = listOf("en" to "English", "hi" to "हिन्दी", "te" to "తెలుగు")

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startAssist()
        } else {
            status.text = "Camera permission is required."
        }
    }

    private val pickVideo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) startReplay(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sp = getSharedPreferences("ba", Context.MODE_PRIVATE)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 60, 40, 40) }

        root.addView(TextView(this).apply { text = "Blind Assist"; textSize = 28f })
        root.addView(TextView(this).apply {
            text = "Camera height from the ground when the phone is in your pocket (cm). Shirt pocket ≈ 120, trouser pocket ≈ 85."
            textSize = 14f
        })
        heightEt = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(sp.getFloat("height_cm", 110f).toInt().toString())
            contentDescription = "Camera height in centimetres"
        }
        root.addView(heightEt)

        rg = RadioGroup(this)
        val saved = sp.getString("lang", "en")
        langs.forEachIndexed { i, p ->
            rg.addView(RadioButton(this).apply { id = 100 + i; text = p.second; textSize = 20f; isChecked = p.first == saved })
        }
        root.addView(rg)

        btn = Button(this).apply { textSize = 32f; gravity = Gravity.CENTER; setOnClickListener { toggle() } }
        root.addView(btn, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        replayBtn = Button(this).apply {
            text = "Replay a walk video (test mode)"; textSize = 16f
            contentDescription = "Replay a recorded walk video through the detector for testing"
            setOnClickListener { pickVideo.launch(arrayOf("video/*")) }
        }
        root.addView(replayBtn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        status = TextView(this).apply {
            textSize = 14f
            text = "This is an aid, not a replacement for your white cane or guide dog. Phone must be upright, back camera facing forward and uncovered."
        }
        root.addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        setContentView(root)
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        val on = AssistService.running
        btn.text = if (on) "STOP" else "START"
        btn.contentDescription = if (on) "Stop Blind Assist" else "Start Blind Assist. You will have ten seconds to put the phone in your pocket."
    }

    private fun toggle() {
        if (AssistService.running) {
            startService(Intent(this, AssistService::class.java).setAction(AssistService.ACTION_STOP))
            btn.postDelayed({ refresh() }, 400)
            return
        }
        val h = heightEt.text.toString().toFloatOrNull()?.coerceIn(50f, 160f) ?: 110f
        val lang = langs.getOrNull(rg.checkedRadioButtonId - 100)?.first ?: "en"
        getSharedPreferences("ba", Context.MODE_PRIVATE).edit().putFloat("height_cm", h).putString("lang", lang).apply()
        val need = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) need += Manifest.permission.POST_NOTIFICATIONS
        val missing = need.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startAssist() else permLauncher.launch(missing.toTypedArray())
    }

    /** Test mode: run a recorded video through the exact live pipeline; results go to <app files>/replay/*.csv. */
    private fun startReplay(uri: Uri) {
        if (AssistService.running) { status.text = "Stop Blind Assist before replaying a video."; return }
        val sp = getSharedPreferences("ba", Context.MODE_PRIVATE)
        Cfg.lang = sp.getString("lang", "en") ?: "en"
        val heightM = (heightEt.text.toString().toFloatOrNull()?.coerceIn(50f, 160f) ?: 110f) / 100.0
        // Real hfov is saved by the first live run on this phone; until then replay uses the 50 deg default (less accurate).
        val hfov = sp.getFloat("hfov_deg", 50f).toDouble()
        replayBtn.isEnabled = false
        status.text = "Replay starting..."
        Thread {
            var det: Detector? = null
            try {
                val d = Detector(applicationContext)
                det = d
                val runner = ReplayRunner(applicationContext, d, ReplayConfig(camHeightM = heightM, hfovDeg = hfov))
                val sum = runner.run(uri, "replay") { done, total ->
                    runOnUiThread { status.text = "Replaying: $done / $total frames" }
                }
                val msg = "Replay done. frames=${sum.frames}, speech=${sum.speech}, beeps=${sum.beeps}, " +
                    "mean ${"%.0f".format(sum.meanMs)} ms, p95 ${"%.0f".format(sum.p95Ms)} ms. Files: ${sum.files.first().parent}"
                runOnUiThread { status.text = msg }
            } catch (e: Throwable) {
                runOnUiThread { status.text = "Replay failed: ${e.message}" }
            } finally {
                try { det?.close() } catch (ignored: Exception) {}
                runOnUiThread { replayBtn.isEnabled = true }
            }
        }.start()
    }

    private fun startAssist() {
        ContextCompat.startForegroundService(this, Intent(this, AssistService::class.java))
        btn.text = "STOP"
        status.text = "Running. Put the phone in your pocket. Lock the screen if you like; it keeps running."
        btn.postDelayed({ refresh() }, 600)
    }
}
