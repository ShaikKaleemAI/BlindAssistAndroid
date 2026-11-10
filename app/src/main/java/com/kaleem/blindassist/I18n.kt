package com.kaleem.blindassist

import kotlin.math.abs
import kotlin.math.roundToInt

/** NOTE: Hindi/Telugu wording has not been reviewed by a native speaker - please review before real use. */
object I18n {
    internal val S = mapOf(
        "en" to mapOf(
            "step" to "{n} step", "steps" to "{n} steps",
            "obstacle" to "{label}, {dir}, {dist}.", "approaching" to "{label} approaching, {dir}, {dist}.",
            "critical" to "Stop. {label}, {dir}, {dist}.",
            "move_left" to "Move left.", "move_right" to "Move right.", "blocked" to "Blocked. Stop.",
            "camera_lost" to "Camera lost. Stop and use your cane.", "camera_back" to "Camera is back.",
            "slow" to "Processing is slow. Be extra careful.",
            "nothing" to "I don't detect anything right now. That does not mean the way is safe.",
            "starting" to "Blind assist starting. Put the phone in your pocket, camera facing forward. Ready in ten seconds. Keep using your cane.",
            "ready" to "Ready.", "stopped" to "Blind assist stopped.",
            "covered" to "Camera is covered. Adjust the phone.", "not_upright" to "Phone is not upright. Adjust it.",
            "no_model" to "Detection model missing. Cannot start.",
            "left" to "left", "right" to "right", "slight_left" to "slightly left", "slight_right" to "slightly right",
            "ahead" to "ahead", "far_left" to "far left", "far_right" to "far right"),
        "hi" to mapOf(
            "step" to "{n} कदम", "steps" to "{n} कदम",
            "obstacle" to "{label}, {dir}, {dist}।", "approaching" to "{label} पास आ रहा है, {dir}, {dist}।",
            "critical" to "रुकिए। {label}, {dir}, {dist}।",
            "move_left" to "बाएं मुड़िए।", "move_right" to "दाएं मुड़िए।", "blocked" to "रास्ता बंद है। रुकिए।",
            "camera_lost" to "कैमरा बंद हो गया। रुकिए और छड़ी का इस्तेमाल कीजिए।", "camera_back" to "कैमरा चालू हो गया।",
            "slow" to "प्रोसेसिंग धीमी है। सावधान रहिए।",
            "nothing" to "अभी मुझे कुछ नहीं दिख रहा। इसका मतलब यह नहीं कि रास्ता सुरक्षित है।",
            "starting" to "ब्लाइंड असिस्ट शुरू हो रहा है। फ़ोन जेब में रखिए, कैमरा सामने। दस सेकंड में तैयार। छड़ी का इस्तेमाल जारी रखिए।",
            "ready" to "तैयार।", "stopped" to "ब्लाइंड असिस्ट बंद।",
            "covered" to "कैमरा ढका हुआ है। फ़ोन ठीक कीजिए।", "not_upright" to "फ़ोन सीधा नहीं है। ठीक कीजिए।",
            "no_model" to "मॉडल नहीं मिला। शुरू नहीं हो सकता।",
            "left" to "बाएं", "right" to "दाएं", "slight_left" to "थोड़ा बाएं", "slight_right" to "थोड़ा दाएं",
            "ahead" to "सामने", "far_left" to "बहुत बाएं", "far_right" to "बहुत दाएं"),
        "te" to mapOf(
            "step" to "{n} అడుగు", "steps" to "{n} అడుగులు",
            "obstacle" to "{label}, {dir}, {dist}.", "approaching" to "{label} దగ్గరకు వస్తోంది, {dir}, {dist}.",
            "critical" to "ఆగండి. {label}, {dir}, {dist}.",
            "move_left" to "ఎడమవైపు వెళ్ళండి.", "move_right" to "కుడివైపు వెళ్ళండి.", "blocked" to "దారి మూసుకుపోయింది. ఆగండి.",
            "camera_lost" to "కెమెరా పనిచేయడం లేదు. ఆగి, కర్ర ఉపయోగించండి.", "camera_back" to "కెమెరా తిరిగి పనిచేస్తోంది.",
            "slow" to "ప్రాసెసింగ్ నెమ్మదిగా ఉంది. జాగ్రత్తగా ఉండండి.",
            "nothing" to "ఇప్పుడు నాకు ఏమీ కనిపించడం లేదు. దారి సురక్షితం అని దీని అర్థం కాదు.",
            "starting" to "బ్లైండ్ అసిస్ట్ మొదలవుతోంది. ఫోన్‌ను జేబులో పెట్టండి, కెమెరా ముందుకు. పది సెకన్లలో సిద్ధం. కర్రను వాడుతూనే ఉండండి.",
            "ready" to "సిద్ధం.", "stopped" to "బ్లైండ్ అసిస్ట్ ఆగింది.",
            "covered" to "కెమెరా కప్పబడింది. ఫోన్‌ను సరిచేయండి.", "not_upright" to "ఫోన్ నిటారుగా లేదు. సరిచేయండి.",
            "no_model" to "మోడల్ లేదు. మొదలుపెట్టలేను.",
            "left" to "ఎడమవైపు", "right" to "కుడివైపు", "slight_left" to "కొంచెం ఎడమవైపు", "slight_right" to "కొంచెం కుడివైపు",
            "ahead" to "ముందు", "far_left" to "చాలా ఎడమవైపు", "far_right" to "చాలా కుడివైపు"))

    private val LABELS = mapOf(
        "hi" to mapOf("person" to "व्यक्ति", "car" to "कार", "bicycle" to "साइकिल", "motorcycle" to "मोटरसाइकिल",
            "bus" to "बस", "truck" to "ट्रक", "chair" to "कुर्सी", "couch" to "सोफा", "bench" to "बेंच",
            "dining table" to "मेज़", "dog" to "कुत्ता", "cat" to "बिल्ली", "cow" to "गाय"),
        "te" to mapOf("person" to "మనిషి", "car" to "కారు", "bicycle" to "సైకిల్", "motorcycle" to "బైక్",
            "bus" to "బస్సు", "truck" to "లారీ", "chair" to "కుర్చీ", "couch" to "సోఫా", "bench" to "బెంచ్",
            "dining table" to "బల్ల", "dog" to "కుక్క", "cat" to "పిల్లి", "cow" to "ఆవు"))

    fun t(lang: String, key: String, vararg kv: Pair<String, Any>): String {
        var s = S[lang]?.get(key) ?: S["en"]!![key] ?: key
        for ((k, v) in kv) s = s.replace("{$k}", v.toString())
        return s
    }

    fun label(lang: String, name: String) = LABELS[lang]?.get(name) ?: name

    fun dist(lang: String, z: Double?): String {
        if (z == null) return ""
        val n = maxOf(1, (z / Cfg.STEP_M).roundToInt())
        return t(lang, if (n == 1) "step" else "steps", "n" to n)
    }

    fun direction(lang: String, b: Double): String {
        if (lang == "en") return "${clockHour(b)} o'clock"
        val a = abs(b)
        if (a < 8) return t(lang, "ahead")
        val side = if (b < 0) "left" else "right"
        return when { a < 20 -> t(lang, "slight_$side"); a < 38 -> t(lang, side); else -> t(lang, "far_$side") }
    }
}
