package com.kaleem.blindassist

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector

/** Anything that turns an upright ARGB_8888 frame into detections (real model, or a fake in tests/replay). */
interface FrameDetector {
    fun detect(bmp: Bitmap): List<Det>
    fun close() {}
}

/** On-device COCO object detector (EfficientDet-Lite0). No internet, no laptop. */
class Detector(ctx: Context) : FrameDetector {
    private val od: ObjectDetector = ObjectDetector.createFromOptions(
        ctx,
        ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("efficientdet_lite0.tflite").build())
            .setScoreThreshold(Cfg.SCORE_MIN)
            .setMaxResults(12)
            .setRunningMode(RunningMode.IMAGE)
            .build())

    override fun detect(bmp: Bitmap): List<Det> {
        val res = od.detect(BitmapImageBuilder(bmp).build())
        val out = ArrayList<Det>()
        for (d in res.detections()) {
            val c = d.categories().firstOrNull() ?: continue
            val name = c.categoryName()
            if (name !in Cfg.NAV) continue
            val b = d.boundingBox()
            out += Det(name, doubleArrayOf(b.left.toDouble(), b.top.toDouble(), b.right.toDouble(), b.bottom.toDouble()),
                c.score().toDouble())
        }
        return out
    }

    override fun close() = od.close()
}
