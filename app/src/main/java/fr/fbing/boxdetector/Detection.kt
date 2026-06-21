package fr.fbing.boxdetector

import android.graphics.RectF

data class Detection(
    val box: RectF,
    val confidence: Float
)

data class DetectionResult(
    val detections: List<Detection>,
    val sourceWidth: Int,
    val sourceHeight: Int
) {
    companion object {
        val EMPTY = DetectionResult(emptyList(), 0, 0)
    }
}
