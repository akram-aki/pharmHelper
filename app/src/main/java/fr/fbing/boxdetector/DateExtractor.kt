package fr.fbing.boxdetector

import android.util.Log
import java.text.Normalizer
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Extracts fabrication and expiration dates from OCR lines that may come from
 * several passes — the full crop at three rotations plus the isolated left/right
 * edge strips (where the vignette prints the expiration date vertically).
 *
 * Each plausible date becomes a scored [Candidate]; candidates are grouped by
 * (month, year) and voted, so a strip-read expiration date corroborated across
 * passes wins over stray numbers elsewhere. Grammar is fuzzy and drop-tolerant
 * (JL -> July, 0CT -> October), and month words split from their year onto a
 * separate strip line are paired back by proximity within the same pass.
 */
class DateExtractor {

    data class Result(val fab: String?, val exp: String?)

    private enum class Label { FAB, EXP, NONE }

    private class Candidate(
        val day: Int?,
        val month: Int,
        val year: Int,
        val label: Label,
        val region: OcrRegion,
        val score: Float
    ) {
        fun key() = year * 100 + month
        fun formatted() =
            if (day != null) "%02d/%02d/%d".format(day, month, year)
            else "%02d/%d".format(month, year)
    }

    private class MonthMatch(val month: Int, val sim: Float)

    fun extract(lines: List<OcrLine>): Result {
        val candidates = lines.groupBy { it.passId }
            .flatMap { (_, passLines) -> candidatesFromPass(passLines) }
            .filter { it.score >= MIN_CANDIDATE_SCORE }

        if (candidates.isEmpty()) return Result(null, null)
        Log.d(TAG, "candidates: " + candidates.joinToString { "${it.formatted()}[${it.label},${it.region},%.2f]".format(it.score) })

        val groups = candidates.groupBy { it.key() }.values
        fun groupScore(g: List<Candidate>) =
            g.maxOf { it.score } + min(CORROBORATION_CAP, CORROBORATION_BONUS * (g.map { it.region }.distinct().size - 1))
        fun repr(g: List<Candidate>) = g.maxByOrNull { it.score }!!

        val chosen = HashSet<Int>()
        val expGroup = groups.filter { g -> g.any { it.label == Label.EXP } }.maxByOrNull { groupScore(it) }
        val fabGroup = groups
            .filter { g -> g.any { it.label == Label.FAB } && g.first().key() != expGroup?.let { repr(it).key() } }
            .maxByOrNull { groupScore(it) }

        var exp = expGroup?.let { repr(it) }
        var fab = fabGroup?.let { repr(it) }
        exp?.let { chosen.add(it.key()) }
        fab?.let { chosen.add(it.key()) }

        // Unlabeled fallback: strongest leftover groups, ordered chronologically.
        val leftovers = groups
            .filter { g -> repr(g).key() !in chosen }
            .sortedByDescending { groupScore(it) }
            .map { repr(it) }

        if (exp == null && fab == null && leftovers.size >= 2) {
            val byDate = leftovers.take(2).sortedBy { it.key() }
            fab = byDate[0]; exp = byDate[1]
        } else if (exp == null && leftovers.isNotEmpty()) {
            // A lone date is the expiration (what the sticker emphasizes).
            exp = leftovers.first()
        }
        if (fab == null && exp != null) {
            leftovers.firstOrNull { it.key() < exp!!.key() }?.let { fab = it }
        }

        return Result(fab?.formatted(), exp?.formatted())
    }

    // ------------------------------------------------- per-pass extraction

    private class Raw(val day: Int?, val month: Int, val year: Int, val monthSim: Float, val box: TextBox, val conf: Float?)

    private fun candidatesFromPass(passLines: List<OcrLine>): List<Candidate> {
        val region = passLines.firstOrNull()?.region ?: OcrRegion.FULL
        val medianH = passLines.map { it.box.height }.sorted().let { if (it.isEmpty()) 1 else it[it.size / 2] }
        val norm = passLines.map { it to fixOcrConfusions(stripAccents(it.text)) }

        // Standalone 4-digit years, for pairing a month word split onto its own line.
        val yearTokens = norm.flatMap { (line, text) ->
            YEAR_ONLY.findAll(text).mapNotNull { m ->
                val y = m.value.toIntOrNull()?.takeIf { it in 2000..2099 } ?: return@mapNotNull null
                y to line.box
            }
        }
        // Label-only lines (EXP / FAB with little else), for spatial association.
        val labelLines = norm.mapNotNull { (line, text) ->
            labelOf(text).takeIf { it != Label.NONE }?.let { it to line.box }
        }

        val raws = mutableListOf<Pair<Raw, Label>>()
        for ((line, text) in norm) {
            val taken = mutableListOf<IntRange>()
            fun free(r: IntRange) = taken.none { it.first <= r.last && r.first <= it.last }

            for (m in DATE_FULL.findAll(text)) {
                taken.add(m.range)
                val month = m.groupValues[2].toIntOrNull() ?: continue
                add(raws, line, text, m.range, m.groupValues[1].ifEmpty { null }, month, 1f, m.groupValues[3], labelLines, medianH)
            }
            for (m in DATE_MONTH_TOKEN.findAll(text)) {
                if (!free(m.range)) continue
                val word = m.groupValues[2]
                if (isLabelWord(word)) continue           // "FAB" fuzzes to "FEB" — never a month here
                val mm = matchMonth(word) ?: continue
                val year = normalizeYear(m.groupValues[3]) ?: continue  // bad year: leave the range for DATE_MY
                taken.add(m.range)
                addParsed(raws, line, text, m.range, m.groupValues[1].ifEmpty { null }?.toIntOrNull(), mm.month, mm.sim, year, labelLines, medianH)
            }
            for (m in DATE_MY.findAll(text)) {
                if (!free(m.range)) continue
                val month = m.groupValues[1].toIntOrNull() ?: continue
                taken.add(m.range)
                add(raws, line, text, m.range, null, month, 1f, m.groupValues[2], labelLines, medianH)
            }
            // Month word with no adjacent year on this line: pair across the pass.
            for (m in MONTH_WORD.findAll(text)) {
                if (!free(m.range)) continue
                val word = m.value
                if (isLabelWord(word)) continue
                val mm = matchMonth(word) ?: continue
                val year = nearestYear(line.box, yearTokens, medianH) ?: continue
                taken.add(m.range)
                addParsed(raws, line, text, m.range, null, mm.month, mm.sim, year, labelLines, medianH)
            }
        }

        return raws.map { (raw, label) ->
            Candidate(raw.day, raw.month, raw.year, label, region, score(raw, label, region))
        }
    }

    /** Validates a year string then delegates to [addParsed]. */
    private fun add(
        out: MutableList<Pair<Raw, Label>>, line: OcrLine, text: String, range: IntRange,
        dayStr: String?, month: Int, monthSim: Float, yearStr: String,
        labelLines: List<Pair<Label, TextBox>>, medianH: Int
    ) {
        val year = normalizeYear(yearStr) ?: return
        addParsed(out, line, text, range, dayStr?.toIntOrNull(), month, monthSim, year, labelLines, medianH)
    }

    private fun addParsed(
        out: MutableList<Pair<Raw, Label>>, line: OcrLine, text: String, range: IntRange,
        day: Int?, month: Int, monthSim: Float, year: Int,
        labelLines: List<Pair<Label, TextBox>>, medianH: Int
    ) {
        if (month !in 1..12) return
        if (day != null && day !in 1..31) return
        val label = labelBefore(text, range.first) ?: nearestLabel(line.box, labelLines, medianH)
        out.add(Raw(day, month, year, monthSim, line.box, line.confidence) to label)
    }

    /** 2-digit years accepted only in the 2020–2039 window; 4-digit in 2000–2099. */
    private fun normalizeYear(yearStr: String): Int? {
        val y = yearStr.toIntOrNull() ?: return null
        return when (yearStr.length) {
            2 -> if (y in TWO_DIGIT_YEAR_MIN..TWO_DIGIT_YEAR_MAX) y + 2000 else null
            4 -> if (y in 2000..2099) y else null
            else -> null
        }
    }

    private fun score(raw: Raw, label: Label, region: OcrRegion): Float {
        var s = 0f
        s += W_MONTH * raw.monthSim
        s += W_YEAR * 1f                                   // already validated to a plausible window
        s += W_LABEL * if (label != Label.NONE) 1f else 0f
        s += W_OCR_CONF * (raw.conf ?: NEUTRAL_CONF)
        s += W_REGION * if (region != OcrRegion.FULL) 1f else FULL_REGION_PRIOR
        s += W_DAY * if (raw.day != null) 1f else 0f
        return s
    }

    // ------------------------------------------------- spatial helpers

    /** A label keyword immediately preceding the token on the same line. */
    private fun labelBefore(text: String, start: Int): Label? {
        val before = text.substring(0, start)
        return when {
            EXP_LABEL.containsMatchIn(before) -> Label.EXP
            FAB_LABEL.containsMatchIn(before) -> Label.FAB
            else -> null
        }
    }

    /** Whole-line label classification (for label-only lines in a strip). */
    private fun labelOf(text: String): Label = when {
        EXP_LABEL.containsMatchIn(text) -> Label.EXP
        FAB_LABEL.containsMatchIn(text) -> Label.FAB
        else -> Label.NONE
    }

    /** A standalone label keyword must never be interpreted as a fuzzy month. */
    private fun isLabelWord(word: String): Boolean {
        val w = word.uppercase()
        return FAB_LABEL.containsMatchIn(w) || EXP_LABEL.containsMatchIn(w)
    }

    private fun nearestLabel(box: TextBox, labels: List<Pair<Label, TextBox>>, medianH: Int): Label {
        val limit = LABEL_RADIUS_H * medianH
        return labels
            .map { (label, lbox) -> label to dist(box, lbox) }
            .filter { it.second <= limit }
            .minByOrNull { it.second }?.first ?: Label.NONE
    }

    private fun nearestYear(box: TextBox, years: List<Pair<Int, TextBox>>, medianH: Int): Int? {
        if (years.isEmpty()) return null
        if (years.size == 1) return years.first().first
        val limit = PAIR_RADIUS_H * medianH
        return years
            .map { (y, ybox) -> y to dist(box, ybox) }
            .filter { it.second <= limit }
            .minByOrNull { it.second }?.first
    }

    private fun dist(a: TextBox, b: TextBox): Float {
        val dx = a.cx - b.cx
        val dy = a.cy - b.cy
        return sqrt(dx * dx + dy * dy)
    }

    // ------------------------------------------------- text normalization

    private fun stripAccents(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    /**
     * Length-preserving repair of OCR letter/digit swaps by token context:
     * digits in letter-dominant words become letters (0CT -> OCT) and letters
     * in digit-dominant tokens become digits (2O28 -> 2028). Complements the
     * fuzzy month match — it does not replace it.
     */
    private fun fixOcrConfusions(line: String): String {
        val out = StringBuilder(line)
        for (m in WORD_TOKEN.findAll(line)) {
            val token = m.value
            val letters = token.count { it.isLetter() }
            val digits = token.count { it.isDigit() }
            if (letters >= 2 && letters > digits) {
                for (i in m.range) LETTER_FOR_DIGIT[line[i]]?.let { out.setCharAt(i, it) }
            } else if (digits >= 1 && digits >= letters) {
                for (i in m.range) DIGIT_FOR_LETTER[line[i]]?.let { out.setCharAt(i, it) }
            }
        }
        return out.toString()
    }

    /** Nearest month by fuzzy match over French + English spellings. */
    private fun matchMonth(token: String): MonthMatch? {
        val t = token.uppercase()
        if (t.length < 2) return null
        var best: MonthMatch? = null
        for ((spelling, month) in MONTH_CANDIDATES) {
            val sim = similarity(t, spelling)
            if (best == null || sim > best!!.sim) best = MonthMatch(month, sim)
        }
        return best?.takeIf { it.sim >= MONTH_MIN_SIM }
    }

    companion object {
        private const val TAG = "DateExtractor"

        private const val MIN_CANDIDATE_SCORE = 0.45f
        private const val MONTH_MIN_SIM = 0.55f
        private const val TWO_DIGIT_YEAR_MIN = 20
        private const val TWO_DIGIT_YEAR_MAX = 39

        // Scoring weights (sum ~0.95).
        private const val W_MONTH = 0.30f
        private const val W_YEAR = 0.20f
        private const val W_LABEL = 0.20f
        private const val W_OCR_CONF = 0.10f
        private const val W_REGION = 0.10f
        private const val W_DAY = 0.05f
        private const val NEUTRAL_CONF = 0.7f
        private const val FULL_REGION_PRIOR = 0.6f

        private const val CORROBORATION_BONUS = 0.05f
        private const val CORROBORATION_CAP = 0.15f
        private const val LABEL_RADIUS_H = 3.0f   // × median line height
        private const val PAIR_RADIUS_H = 4.0f

        private val DATE_FULL = Regex("""\b(\d{1,2})\s*[/.\-]\s*(\d{1,2})\s*[/.\-]\s*(\d{2,4})\b""")
        private val DATE_MY = Regex("""\b(\d{1,2})\s*[/.\-]\s*(\d{2,4})\b""")
        private val DATE_MONTH_TOKEN = Regex(
            """\b(?:(\d{1,2})\s*[/.\-\s]\s*)?([A-Za-z]{2,9})\.?\s*[/.\-\s]?\s*(\d{2,4})\b"""
        )
        // {2,9} so a dropped-letter month split onto its own strip line (JL) still pairs.
        private val MONTH_WORD = Regex("""\b[A-Za-z]{2,9}\b""")
        private val YEAR_ONLY = Regex("""\b(20\d{2})\b""")

        private val MONTH_CANDIDATES = listOf(
            "JAN" to 1, "JANV" to 1, "JANVIER" to 1, "JANUARY" to 1,
            "FEV" to 2, "FEB" to 2, "FEVR" to 2, "FEVRIER" to 2, "FEBRUARY" to 2,
            "MAR" to 3, "MARS" to 3, "MARCH" to 3,
            "AVR" to 4, "APR" to 4, "AVRIL" to 4, "APRIL" to 4,
            "MAI" to 5, "MAY" to 5,
            "JUIN" to 6, "JUN" to 6, "JUNE" to 6,
            "JUIL" to 7, "JUL" to 7, "JUILLET" to 7, "JULY" to 7,
            "AOU" to 8, "AOUT" to 8, "AUG" to 8, "AUGUST" to 8,
            "SEP" to 9, "SEPT" to 9, "SEPTEMBRE" to 9, "SEPTEMBER" to 9,
            "OCT" to 10, "OCTOBRE" to 10, "OCTOBER" to 10,
            "NOV" to 11, "NOVEMBRE" to 11, "NOVEMBER" to 11,
            "DEC" to 12, "DECEMBRE" to 12, "DECEMBER" to 12
        )

        private val WORD_TOKEN = Regex("""[A-Za-z0-9]+""")
        private val LETTER_FOR_DIGIT = mapOf(
            '0' to 'O', '1' to 'I', '3' to 'E', '4' to 'A', '5' to 'S', '8' to 'B'
        )
        private val DIGIT_FOR_LETTER = mapOf(
            'O' to '0', 'o' to '0', 'I' to '1', 'i' to '1', 'l' to '1',
            'S' to '5', 's' to '5', 'B' to '8', 'Z' to '2', 'z' to '2'
        )
        private val FAB_LABEL = Regex("""\b(?:DATE\s*)?(?:FAB\w*|MFG|PROD\w*)""", RegexOption.IGNORE_CASE)
        private val EXP_LABEL = Regex("""\b(?:DATE\s*)?(?:EXP\w*|PER\w*|PEREMPTION)""", RegexOption.IGNORE_CASE)
    }
}
