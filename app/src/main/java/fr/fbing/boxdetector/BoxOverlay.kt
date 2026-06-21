package fr.fbing.boxdetector

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

class BoxOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var result: DetectionResult = DetectionResult.EMPTY

    private val strokePaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.box_stroke)
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.box_stroke)
        textSize = 36f
        isAntiAlias = true
        typeface = Typeface.DEFAULT_BOLD
    }

    fun setDetections(detectionResult: DetectionResult) {
        result = detectionResult
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val srcW = result.sourceWidth
        val srcH = result.sourceHeight
        if (srcW == 0 || srcH == 0 || result.detections.isEmpty()) return

        // FILL_CENTER mapping: the PreviewView scales the camera frame so it
        // fills the view, cropping the overflow on the long side. We mirror
        // that math so our boxes land on the same pixels the user sees.
        val scale = maxOf(width.toFloat() / srcW, height.toFloat() / srcH)
        val offsetX = (width - srcW * scale) / 2f
        val offsetY = (height - srcH * scale) / 2f

        for (det in result.detections) {
            val x1 = det.box.left * scale + offsetX
            val y1 = det.box.top * scale + offsetY
            val x2 = det.box.right * scale + offsetX
            val y2 = det.box.bottom * scale + offsetY
            canvas.drawRect(x1, y1, x2, y2, strokePaint)
            val label = "%.2f".format(det.confidence)
            canvas.drawText(
                label,
                x1,
                (y1 - 8f).coerceAtLeast(textPaint.textSize),
                textPaint
            )
        }
    }
}
