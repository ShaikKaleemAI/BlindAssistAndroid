package com.kaleem.blindassist

data class Dims(val w: Double, val h: Double, val sw: Double, val sh: Double)

/** All tunables (ported from config.py of Blind Assist v11). Distances in metres. */
object Cfg {
    var lang = "en"
    var hfovDeg = 50.0          // overwritten from the real camera at start
    var camHeightM = 1.10       // pocket height: set in the app
    var defaultPitchDeg = 0.0   // camera horizontal when phone is upright
    var pitchSigmaDeg = 3.0     // pocket sways more than a chest mount
    var verbose = false

    const val USER_HALF_WIDTH = 0.35
    const val CORRIDOR_MARGIN = 0.25
    const val CORRIDOR_GROWTH = 0.05
    const val CRITICAL_DIST = 1.2
    const val WARNING_DIST = 2.5
    const val INFO_DIST = 4.5
    const val VEHICLE_SCALE = 2.0
    const val CRITICAL_TTC = 2.0
    const val WARNING_TTC = 4.0
    const val LOOKAHEAD = 4.0
    const val MIN_HITS = 2
    const val MIN_HITS_CLOSE = 1.5
    const val TRACK_MAX_AGE = 0.8
    const val DEESCALATE_AFTER = 0.7
    const val CRITICAL_REPEAT = 2.5
    const val GUIDANCE_REPEAT = 3.0
    const val STEP_M = 0.75
    const val TTL_CRITICAL = 1.0
    const val TTL_INFO = 3.0
    const val SCORE_MIN = 0.45f

    val DIMS = mapOf(
        "person" to Dims(0.50, 1.70, 0.30, 0.10), "bicycle" to Dims(0.60, 1.10, 0.60, 0.15),
        "car" to Dims(1.80, 1.50, 0.60, 0.15), "motorcycle" to Dims(0.80, 1.20, 0.60, 0.20),
        "bus" to Dims(2.50, 3.00, 0.60, 0.15), "truck" to Dims(2.50, 3.00, 0.60, 0.15),
        "chair" to Dims(0.50, 0.90, 0.25, 0.25), "couch" to Dims(1.90, 0.90, 0.30, 0.25),
        "bench" to Dims(1.20, 0.85, 0.30, 0.25), "dining table" to Dims(1.20, 0.75, 0.40, 0.25),
        "potted plant" to Dims(0.40, 0.60, 0.40, 0.40), "dog" to Dims(0.30, 0.50, 0.60, 0.40),
        "cat" to Dims(0.25, 0.30, 0.60, 0.50), "cow" to Dims(0.70, 1.40, 0.70, 0.20),
        "horse" to Dims(0.70, 1.60, 0.70, 0.20), "fire hydrant" to Dims(0.30, 0.70, 0.20, 0.25),
        "suitcase" to Dims(0.45, 0.70, 0.30, 0.30), "bed" to Dims(1.50, 0.60, 0.40, 0.40),
    )
    val GROUNDED = DIMS.keys
    val VEHICLES = setOf("bicycle", "car", "motorcycle", "bus", "truck")
    val DYNAMIC = VEHICLES + setOf("person", "dog", "cat", "cow", "horse")
    val GROUND_HAZARDS = setOf("stairs", "steps", "pothole", "open drain", "manhole", "curb") // for a future fine-tuned model
    val NAV = DIMS.keys + setOf("backpack", "stop sign", "parking meter", "tv")
}
