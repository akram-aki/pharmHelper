package fr.fbing.boxdetector

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Runs ML Kit text recognition on a detected box region, but only when the
 * crop looks readable: big enough and sharp enough. At most one recognition
 * is in flight at a time.
 */
class TextReader {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val inFlight = AtomicBoolean(false)
    @Volatile private var lastOcrAt = 0L

    /**
     * Called on the analysis thread. Crops [box] out of [source], applies the
     * readability gates and, if they pass, runs OCR. [onText] is invoked on
     * the main thread only when text is accepted.
     */
    fun maybeRead(source: Bitmap, box: RectF, onText: (String) -> Unit) {
        val now = SystemClock.elapsedRealtime()
        if (inFlight.get() || now - lastOcrAt < MIN_INTERVAL_MS) return

        // Pad the box so text touching the detection edge isn't clipped.
        val padX = box.width() * PAD_FRACTION
        val padY = box.height() * PAD_FRACTION
        val x = max(0f, box.left - padX).roundToInt()
        val y = max(0f, box.top - padY).roundToInt()
        val w = min(source.width.toFloat(), box.right + padX).roundToInt() - x
        val h = min(source.height.toFloat(), box.bottom + padY).roundToInt() - y

        if (w < MIN_CROP_W || h < MIN_CROP_H) {
            Log.d(TAG, "skip: too small ${w}x$h")
            return
        }

        val crop = Bitmap.createBitmap(source, x, y, w, h)

        val sharpness = sharpness(crop)
        if (sharpness < SHARPNESS_MIN) {
            Log.d(TAG, "skip: blurry sharpness=%.1f < %.1f".format(sharpness, SHARPNESS_MIN))
            crop.recycle()
            return
        }

        if (!inFlight.compareAndSet(false, true)) {
            crop.recycle()
            return
        }
        val start = SystemClock.elapsedRealtime()
        // The box may carry text in more than one orientation (e.g. a side
        // panel printed perpendicular to the front), so run a pass per
        // rotation and merge the lines.
        recognizeRotations(crop, 0, mutableListOf()) { lines ->
            val text = mergeLines(lines)
            val alnum = text.count { it.isLetterOrDigit() }
            val ms = SystemClock.elapsedRealtime() - start
            if (alnum >= MIN_ACCEPT_CHARS) {
                Log.d(TAG, "ocr: $alnum alnum chars, sharpness=%.1f, ${ms}ms".format(sharpness))
                onText(text)
            } else {
                Log.d(TAG, "rejected: $alnum alnum chars, ${ms}ms")
            }
            lastOcrAt = SystemClock.elapsedRealtime()
            inFlight.set(false)
            crop.recycle()
        }
    }

    /** Runs the recognizer once per entry in [ROTATIONS], accumulating raw lines. */
    private fun recognizeRotations(
        crop: Bitmap,
        index: Int,
        lines: MutableList<String>,
        onDone: (List<String>) -> Unit
    ) {
        if (index >= ROTATIONS.size) {
            onDone(lines)
            return
        }
        val rotation = ROTATIONS[index]
        recognizer.process(InputImage.fromBitmap(crop, rotation))
            .addOnSuccessListener { visionText ->
                visionText.textBlocks.forEach { block ->
                    block.lines.forEach { lines.add(it.text) }
                }
            }
            .addOnFailureListener { Log.w(TAG, "pass ${rotation}deg failed", it) }
            .addOnCompleteListener { recognizeRotations(crop, index + 1, lines, onDone) }
    }

    /**
     * Deduplicates lines across rotation passes (the same text can be read in
     * more than one pass) and drops near-empty garbage from wrong-rotation reads.
     */
    private fun mergeLines(lines: List<String>): String {
        val seen = HashSet<String>()
        val kept = mutableListOf<String>()
        for (line in lines) {
            val trimmed = line.trim()
            val key = trimmed.filter { it.isLetterOrDigit() }.lowercase()
            if (key.length < 2) continue
            if (seen.add(key)) kept.add(trimmed)
        }
        return kept.joinToString("\n")
    }

    fun close() {
        recognizer.close()
    }

    /**
     * Variance of a 4-neighbour Laplacian over the luma of a downscaled copy.
     * Low variance = few edges = blurry or featureless crop.
     */
    private fun sharpness(bitmap: Bitmap): Float {
        val scale = SHARPNESS_MAX_SIDE.toFloat() / max(bitmap.width, bitmap.height)
        val small = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bitmap,
                max(1, (bitmap.width * scale).roundToInt()),
                max(1, (bitmap.height * scale).roundToInt()),
                true
            )
        } else bitmap

        val w = small.width
        val h = small.height
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        if (small !== bitmap) small.recycle()

        val luma = FloatArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            luma[i] = 0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p)
        }

        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        for (yy in 1 until h - 1) {
            for (xx in 1 until w - 1) {
                val i = yy * w + xx
                val lap = 4f * luma[i] - luma[i - 1] - luma[i + 1] - luma[i - w] - luma[i + w]
                sum += lap
                sumSq += lap * lap
                n++
            }
        }
        if (n == 0) return 0f
        val mean = sum / n
        return (sumSq / n - mean * mean).toFloat()
    }

    companion object {
        private const val TAG = "TextReader"
        private const val MIN_INTERVAL_MS = 1500L
        private const val PAD_FRACTION = 0.10f
        private const val MIN_CROP_W = 100
        private const val MIN_CROP_H = 60
        private const val SHARPNESS_MIN = 80f
        private const val SHARPNESS_MAX_SIDE = 160
        private const val MIN_ACCEPT_CHARS = 4
        // 0 = as-detected, 90/270 = text printed perpendicular to the main label.
        private val ROTATIONS = intArrayOf(0, 90, 270)
    }
}
