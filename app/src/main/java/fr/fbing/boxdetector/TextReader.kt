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
 * On-demand ML Kit text recognition on a detected vignette region. Runs the
 * full crop at three rotations for the main text, plus the left/right edge
 * strips (where the expiration date is printed vertically) in isolation so the
 * small side-text is read cleanly instead of competing with sideways main text.
 * Returns structured lines (text + box + confidence + region) so the parser can
 * reason about geometry. One read runs at a time.
 */
class TextReader {

    enum class FailReason { BUSY, TOO_SMALL, BLURRY, NO_TEXT }

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val inFlight = AtomicBoolean(false)

    private class Pass(val bitmap: Bitmap, val rotation: Int, val region: OcrRegion)

    /**
     * OCRs [box] within [source]. [onResult] receives structured OCR output on
     * the main thread; [onFail] the reason (the size/blur gates fire on the
     * caller thread, recognition on the main thread).
     */
    fun read(
        source: Bitmap,
        box: RectF,
        onResult: (OcrResult) -> Unit,
        onFail: (FailReason) -> Unit
    ) {
        if (!inFlight.compareAndSet(false, true)) {
            onFail(FailReason.BUSY)
            return
        }

        // Pad the box so text touching the detection edge isn't clipped.
        val padX = box.width() * PAD_FRACTION
        val padY = box.height() * PAD_FRACTION
        val x = max(0f, box.left - padX).roundToInt()
        val y = max(0f, box.top - padY).roundToInt()
        val w = min(source.width.toFloat(), box.right + padX).roundToInt() - x
        val h = min(source.height.toFloat(), box.bottom + padY).roundToInt() - y

        if (w < MIN_CROP_W || h < MIN_CROP_H) {
            Log.d(TAG, "fail: too small ${w}x$h")
            inFlight.set(false)
            onFail(FailReason.TOO_SMALL)
            return
        }

        val rawCrop = Bitmap.createBitmap(source, x, y, w, h)

        // Gate on the ORIGINAL crop so upscaling can't fake sharpness.
        val sharpness = sharpness(rawCrop)
        if (sharpness < SHARPNESS_MIN) {
            Log.d(TAG, "fail: blurry sharpness=%.1f < %.1f".format(sharpness, SHARPNESS_MIN))
            rawCrop.recycle()
            inFlight.set(false)
            onFail(FailReason.BLURRY)
            return
        }

        val crop = ensureReadable(rawCrop, MIN_READABLE_SIDE, MAX_UPSCALE)
        val leftStrip = buildStrip(source, box, left = true)
        val rightStrip = buildStrip(source, box, left = false)

        // FULL crop at 3 rotations (main text) + each strip at 90 and 270
        // (the date is printed 90° off; running both undoes either direction).
        val passes = buildList {
            add(Pass(crop, 0, OcrRegion.FULL))
            add(Pass(crop, 90, OcrRegion.FULL))
            add(Pass(crop, 270, OcrRegion.FULL))
            leftStrip?.let { add(Pass(it, 90, OcrRegion.LEFT_STRIP)); add(Pass(it, 270, OcrRegion.LEFT_STRIP)) }
            rightStrip?.let { add(Pass(it, 90, OcrRegion.RIGHT_STRIP)); add(Pass(it, 270, OcrRegion.RIGHT_STRIP)) }
        }

        val start = SystemClock.elapsedRealtime()
        val collected = mutableListOf<OcrLine>()
        runPasses(passes, 0, collected) {
            val mergedText = mergeFull(collected)
            val alnum = mergedText.count { it.isLetterOrDigit() }
            val ms = SystemClock.elapsedRealtime() - start
            crop.recycle()
            leftStrip?.recycle()
            rightStrip?.recycle()
            inFlight.set(false)
            if (alnum >= MIN_ACCEPT_CHARS) {
                Log.d(TAG, "ocr: $alnum alnum chars, ${collected.size} lines, ${passes.size} passes, ${ms}ms")
                onResult(OcrResult(mergedText, collected))
            } else {
                Log.d(TAG, "fail: $alnum alnum chars, ${ms}ms")
                onFail(FailReason.NO_TEXT)
            }
        }
    }

    private fun runPasses(
        passes: List<Pass>,
        index: Int,
        sink: MutableList<OcrLine>,
        onDone: () -> Unit
    ) {
        if (index >= passes.size) {
            onDone()
            return
        }
        val pass = passes[index]
        recognizer.process(InputImage.fromBitmap(pass.bitmap, pass.rotation))
            .addOnSuccessListener { vt ->
                var count = 0
                vt.textBlocks.forEach { block ->
                    block.lines.forEach { line ->
                        val r = line.boundingBox
                        val boxOfLine = if (r != null) TextBox(r.left, r.top, r.right, r.bottom)
                            else TextBox(0, sink.size * 10, 1, sink.size * 10 + 1)  // synthetic stack
                        sink.add(
                            OcrLine(
                                line.text.trim(),
                                boxOfLine,
                                line.confidence.takeIf { it > 0f },
                                pass.region,
                                index
                            )
                        )
                        count++
                    }
                }
                if (pass.region != OcrRegion.FULL && count > 0) {
                    Log.d(TAG, "strip ${pass.region}@${pass.rotation}: ${count} line(s)")
                }
            }
            .addOnFailureListener { Log.w(TAG, "pass $index (${pass.region} ${pass.rotation}) failed", it) }
            .addOnCompleteListener { runPasses(passes, index + 1, sink, onDone) }
    }

    /** FULL-pass text only, deduped — identical to the old flat pipeline. */
    private fun mergeFull(lines: List<OcrLine>): String {
        val seen = HashSet<String>()
        val kept = mutableListOf<String>()
        for (line in lines) {
            if (line.region != OcrRegion.FULL) continue
            val trimmed = line.text.trim()
            val key = trimmed.filter { it.isLetterOrDigit() }.lowercase()
            if (key.length < 2) continue
            if (seen.add(key)) kept.add(trimmed)
        }
        return kept.joinToString("\n")
    }

    /**
     * Crops an inner vertical band along one edge of the vignette, where the
     * expiration date is printed sideways (inside the sticker, not off it).
     * Upscaled for the small text. Null if the clamped strip is degenerate.
     */
    private fun buildStrip(source: Bitmap, box: RectF, left: Boolean): Bitmap? {
        val inward = box.width() * STRIP_WIDTH_FRACTION
        val outward = box.width() * STRIP_OUTWARD_FRACTION
        val vpad = box.height() * STRIP_VPAD_FRACTION

        val x0 = if (left) box.left - outward else box.right - inward
        val x1 = if (left) box.left + inward else box.right + outward
        val ix0 = max(0f, x0).roundToInt()
        val iy0 = max(0f, box.top - vpad).roundToInt()
        val ix1 = min(source.width.toFloat(), x1).roundToInt()
        val iy1 = min(source.height.toFloat(), box.bottom + vpad).roundToInt()
        val w = ix1 - ix0
        val h = iy1 - iy0
        if (w < STRIP_MIN_DIM || h < STRIP_MIN_DIM) return null

        val cropped = Bitmap.createBitmap(source, ix0, iy0, w, h)
        return ensureReadable(cropped, STRIP_MIN_READABLE_SIDE, STRIP_MAX_UPSCALE)
    }

    /**
     * ML Kit reads small text poorly; upscale (bilinear) so the short side is
     * at least [target] px, capped at [maxFactor]. Recycles the input when a
     * new bitmap is produced.
     */
    private fun ensureReadable(bitmap: Bitmap, target: Int, maxFactor: Float): Bitmap {
        val shortSide = min(bitmap.width, bitmap.height)
        if (shortSide >= target) return bitmap
        val factor = min(maxFactor, target.toFloat() / shortSide)
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * factor).roundToInt(),
            (bitmap.height * factor).roundToInt(),
            true
        )
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
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
        private const val PAD_FRACTION = 0.10f
        private const val MIN_CROP_W = 100
        private const val MIN_CROP_H = 60
        private const val SHARPNESS_MIN = 80f
        private const val SHARPNESS_MAX_SIDE = 160
        private const val MIN_ACCEPT_CHARS = 4
        private const val MIN_READABLE_SIDE = 300
        private const val MAX_UPSCALE = 3f

        // Inner vertical bands (inside the sticker) carrying the sideways date.
        private const val STRIP_WIDTH_FRACTION = 0.40f    // inward from edge × box width
        private const val STRIP_OUTWARD_FRACTION = 0f     // stay inside the sticker
        private const val STRIP_VPAD_FRACTION = 0f        // span the box height, no overflow
        private const val STRIP_MIN_READABLE_SIDE = 320
        private const val STRIP_MAX_UPSCALE = 5f
        private const val STRIP_MIN_DIM = 24
    }
}
