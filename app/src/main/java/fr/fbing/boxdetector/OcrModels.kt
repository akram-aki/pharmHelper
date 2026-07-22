package fr.fbing.boxdetector

/**
 * Plain, Android-free box in one OCR pass's pixel space. Boxes are only
 * comparable within the same [OcrLine.passId] — never across passes.
 */
data class TextBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val cx get() = (left + right) / 2f
    val cy get() = (top + bottom) / 2f
    val width get() = (right - left).coerceAtLeast(1)
    val height get() = (bottom - top).coerceAtLeast(1)
}

/** Which region of the vignette a line was read from. */
enum class OcrRegion { FULL, LEFT_STRIP, RIGHT_STRIP }

/** One ML Kit text line, tagged with the pass it came from. */
data class OcrLine(
    val text: String,
    val box: TextBox,
    val confidence: Float?,   // ML Kit line confidence, null when unknown/0
    val region: OcrRegion,
    val passId: Int
)

/**
 * OCR output. [mergedText] is built from the FULL passes only (name / PPA /
 * dosage input, unchanged from the old flat-string pipeline). [lines] carries
 * every pass including the edge strips, for date extraction.
 */
data class OcrResult(val mergedText: String, val lines: List<OcrLine>)
