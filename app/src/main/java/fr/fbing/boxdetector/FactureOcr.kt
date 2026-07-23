package fr.fbing.boxdetector

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * Best-effort OCR suggestions for a scanned invoice. Reads the first page with
 * ML Kit and heuristically guesses the supplier (the most prominent line near
 * the top — usually the letterhead) and the invoice date (the first plausible
 * dd/mm/yyyy, preferring a line that mentions a date/invoice keyword). Both are
 * only hints; the user confirms/edits before saving. Intentionally simpler than
 * [DateExtractor], which solves the much harder sideways-sticker date problem.
 */
class FactureOcr {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    data class Suggestion(val supplier: String?, val invoiceDate: String?)

    /** Recognizes [pageImageUri] and delivers suggestions on the main thread. */
    fun suggest(context: Context, pageImageUri: Uri, onResult: (Suggestion) -> Unit) {
        val image = try {
            InputImage.fromFilePath(context, pageImageUri)
        } catch (e: Exception) {
            Log.w(TAG, "cannot open page image for OCR", e)
            onResult(Suggestion(null, null))
            return
        }
        recognizer.process(image)
            .addOnSuccessListener { text ->
                onResult(Suggestion(guessSupplier(text, image.height), guessDate(text)))
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "invoice OCR failed", e)
                onResult(Suggestion(null, null))
            }
    }

    /**
     * The supplier is usually the largest text in the top region (letterhead).
     * Among name-like lines whose vertical centre falls in the top third, pick
     * the one with the tallest bounding box (a font-size proxy).
     */
    private fun guessSupplier(text: Text, imageHeight: Int): String? {
        val topLimit = if (imageHeight > 0) imageHeight / 3 else Int.MAX_VALUE
        val lines = text.textBlocks.flatMap { it.lines }

        val best = lines
            .filter { (it.boundingBox?.centerY() ?: Int.MAX_VALUE) <= topLimit }
            .filter { looksLikeName(it.text) }
            .maxByOrNull { it.boundingBox?.height() ?: 0 }

        return best?.text?.trim()?.take(MAX_SUPPLIER_LEN)
    }

    private fun looksLikeName(raw: String): Boolean {
        val s = raw.trim()
        if (s.length < 3) return false
        val letters = s.count { it.isLetter() }
        val digits = s.count { it.isDigit() }
        return letters >= 3 && letters > digits
    }

    /**
     * First plausible dd/mm/yyyy (or dd-mm-yy) on the page, preferring a match
     * on a line that mentions "date"/"facture"/"invoice". Normalized to
     * dd/mm/yyyy.
     */
    private fun guessDate(text: Text): String? {
        val lines = text.textBlocks.flatMap { it.lines }.map { it.text }
        val preferred = lines.filter { KEYWORD.containsMatchIn(it) }
        return (preferred + lines).firstNotNullOfOrNull { parseDate(it) }
    }

    private fun parseDate(line: String): String? {
        for (m in DATE.findAll(line)) {
            val day = m.groupValues[1].toIntOrNull() ?: continue
            val month = m.groupValues[2].toIntOrNull() ?: continue
            var year = m.groupValues[3].toIntOrNull() ?: continue
            if (day !in 1..31 || month !in 1..12) continue
            if (m.groupValues[3].length == 2) year += 2000
            if (year !in 2000..2099) continue
            return "%02d/%02d/%04d".format(day, month, year)
        }
        return null
    }

    fun close() {
        recognizer.close()
    }

    companion object {
        private const val TAG = "FactureOcr"
        private const val MAX_SUPPLIER_LEN = 40
        private val DATE = Regex("""\b(\d{1,2})[/.\-](\d{1,2})[/.\-](\d{2}|\d{4})\b""")
        private val KEYWORD = Regex("""(?i)\b(date|facture|invoice)\b""")
    }
}
