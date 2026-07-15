package fr.fbing.boxdetector

import android.content.Context
import android.util.Log
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class NameCandidate(val name: String, val confidence: Int)

/**
 * One extracted field cross-referenced against the drug database: best value,
 * its confidence, and up to 3 ranked alternatives for the user to pick from.
 */
data class FieldResult(
    val value: String?,
    val confidence: Int,           // 0-100; 0 = nothing usable in the OCR text
    val options: List<String>,     // ranked best-first, ≤3
    val optionConfidences: List<Int>
) {
    /** True when the UI should ask the user to choose. */
    fun needsChoice(): Boolean =
        options.size >= 2 &&
            (confidence < VignetteParser.ACCEPT_CONFIDENCE ||
                (optionConfidences.getOrNull(1) ?: -1000) >= confidence - 15)

    companion object {
        val EMPTY = FieldResult(null, 0, emptyList(), emptyList())
    }
}

data class VignetteInfo(
    val name: String?,        // best dictionary match, null when below threshold
    val nameConfidence: Int,  // 0-100, of the best match even when rejected
    val nameCandidates: List<NameCandidate>, // plausible matches, best first, ≤3
    val dosage: FieldResult,
    val cond: FieldResult,    // conditionnement, e.g. B/20
    val forme: FieldResult,   // forme courte, e.g. COMP.
    val ppa: String?,         // e.g. "245.50 DA"
    val fabDate: String?,     // normalized MM/YYYY or DD/MM/YYYY
    val expDate: String?,
    val rawText: String
)

/**
 * Extracts structured vignette fields from the raw OCR text, cross-referencing
 * the medication database derived from the pharmacy STOCK.mdb: name against
 * assets/drug_names.txt, then dosage / conditionnement / forme against the
 * known variants of the matched medicine (assets/drug_db.tsv via [DrugDb]).
 */
class VignetteParser(private val context: Context) {

    private val dictionary: List<String> by lazy {
        val start = System.currentTimeMillis()
        val names = context.assets.open(DICT_ASSET).bufferedReader().readLines()
            .map { it.trim() }
            .filter { it.length >= 3 }
        Log.d(TAG, "dictionary: ${names.size} names in ${System.currentTimeMillis() - start}ms")
        names
    }

    fun parse(rawText: String): VignetteInfo {
        val lines = rawText.lines().map { it.trim() }.filter { it.isNotEmpty() }

        val ppa = extractPpa(lines)
        val (fabDate, expDate) = extractDates(lines)
        val nameResult = extractName(lines)
        val (dosage, cond, forme) = matchFieldsInternal(lines, nameResult.name)

        Log.d(
            TAG,
            "name=${nameResult.name} conf=${nameResult.confidence} candidates=${nameResult.candidates} " +
                "dosage=${dosage.value}(${dosage.confidence}) cond=${cond.value}(${cond.confidence}) " +
                "forme=${forme.value}(${forme.confidence}) ppa=$ppa fab=$fabDate exp=$expDate"
        )
        return VignetteInfo(
            nameResult.name, nameResult.confidence, nameResult.candidates,
            dosage, cond, forme, ppa, fabDate, expDate, rawText
        )
    }

    /**
     * Cross-references dosage / conditionnement / forme for [name] (null =
     * no matched medicine, OCR-only fallback). Also called by TextActivity
     * when the user changes the selected name.
     */
    fun matchFields(rawText: String, name: String?): Triple<FieldResult, FieldResult, FieldResult> {
        val lines = rawText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        return matchFieldsInternal(lines, name)
    }

    private fun matchFieldsInternal(
        lines: List<String>,
        name: String?
    ): Triple<FieldResult, FieldResult, FieldResult> {
        DrugDb.load(context)
        val variants = name?.let { DrugDb.variantsFor(it) }.orEmpty()
        if (variants.isEmpty()) return Triple(fallbackDosage(lines), fallbackCond(lines), FieldResult.EMPTY)

        val dosage = matchField(dosageTokens(lines), variants.map { it.dosage })
        val cond = matchField(condTokens(lines), variants.map { it.cond })
        val forme = matchField(formeTokens(lines), variants.map { it.forme })
        return Triple(dosage, cond, forme)
    }

    // ------------------------------------------------- Field cross-reference

    /**
     * Ranks the known values of a field (frequency-weighted) by their best
     * similarity to any OCR token. With no OCR token everything scores 0 and
     * frequency alone ranks the options.
     */
    private fun matchField(ocrTokens: List<String>, knownValues: List<String>): FieldResult {
        val freq = knownValues.filter { it.isNotBlank() }.groupingBy { it }.eachCount()
        if (freq.isEmpty()) return FieldResult.EMPTY

        val scored = freq.entries
            .map { (known, count) ->
                val sim = ocrTokens.maxOfOrNull { similarity(it, known) } ?: 0f
                Triple(known, sim, count)
            }
            .sortedWith(
                compareByDescending<Triple<String, Float, Int>> { it.second }
                    .thenByDescending { it.third }
            )
            .take(MAX_NAME_OPTIONS)

        val best = scored.first()
        return FieldResult(
            value = best.first,
            confidence = (best.second * 100).toInt(),
            options = scored.map { it.first },
            optionConfidences = scored.map { (it.second * 100).toInt() }
        )
    }

    private fun similarity(a: String, b: String): Float {
        if (a == b) return 1f
        val maxLen = max(a.length, b.length)
        if (maxLen == 0) return 0f
        return 1f - levenshtein(a, b).toFloat() / maxLen
    }

    private fun dosageTokens(lines: List<String>): List<String> =
        lines.flatMap { line ->
            DOSAGE_FULL.findAll(line).map { it.value.uppercase().replace(Regex("\\s+"), "") }
        }.distinct()

    private fun condTokens(lines: List<String>): List<String> =
        lines.flatMap { line ->
            COND_TOKEN.findAll(line).map { m ->
                m.value.uppercase()
                    .replace(Regex("\\s+"), "")
                    // Common OCR misread of the leading "B".
                    .replace(Regex("^8"), "B")
            }
        }.distinct()

    private fun formeTokens(lines: List<String>): List<String> =
        lines.flatMap { line -> line.split(Regex("[\\s,;]+")) }
            .map { normalize(it).replace(".", "") }
            .filter { it.length >= 3 && it.any { c -> c.isLetter() } }
            .distinct()

    private fun fallbackDosage(lines: List<String>): FieldResult {
        for (line in lines) {
            val m = DOSAGE_FULL.find(line) ?: continue
            val value = m.value.uppercase().replace(Regex("\\s+"), "")
            return FieldResult(value, FALLBACK_CONFIDENCE, listOf(value), listOf(FALLBACK_CONFIDENCE))
        }
        return FieldResult.EMPTY
    }

    private fun fallbackCond(lines: List<String>): FieldResult {
        for (line in lines) {
            val m = COND_TOKEN.find(line) ?: continue
            val value = m.value.uppercase().replace(Regex("\\s+"), "").replace(Regex("^8"), "B")
            return FieldResult(value, FALLBACK_CONFIDENCE, listOf(value), listOf(FALLBACK_CONFIDENCE))
        }
        return FieldResult.EMPTY
    }

    // ---------------------------------------------------------------- PPA

    private fun extractPpa(lines: List<String>): String? {
        for (line in lines) {
            if (!PPA_LABEL.containsMatchIn(line)) continue
            val m = MONEY.find(line) ?: continue
            return formatMoney(m.groupValues[1])
        }
        // Fallback: any line with an amount followed by DA/DZD.
        for (line in lines) {
            val m = MONEY_WITH_DA.find(line) ?: continue
            return formatMoney(m.groupValues[1])
        }
        return null
    }

    private fun formatMoney(num: String) = "${num.replace(',', '.')} DA"

    // --------------------------------------------------------------- Dates

    private class ParsedDate(val day: Int?, val month: Int, val year: Int) {
        fun key() = year * 100 + month
        fun formatted() =
            if (day != null) "%02d/%02d/%d".format(day, month, year)
            else "%02d/%d".format(month, year)
    }

    /**
     * Every sticker prints dates its own way ("exp : 11-27", "date exp: 5/2027",
     * "PER. 11/2027"); labels win, otherwise two plausible dates are ordered
     * chronologically, and a single unlabeled date is assumed to be the
     * expiration (the date stickers emphasize).
     */
    private fun extractDates(lines: List<String>): Pair<String?, String?> {
        var fab: ParsedDate? = null
        var exp: ParsedDate? = null
        val unlabeled = mutableListOf<ParsedDate>()

        for (line in lines) {
            val taken = mutableListOf<IntRange>()
            val tokens = mutableListOf<Pair<IntRange, ParsedDate?>>()
            for (m in DATE_FULL.findAll(line)) {
                taken.add(m.range)
                tokens.add(m.range to parseDate(m.groupValues[1], m.groupValues[2], m.groupValues[3]))
            }
            for (m in DATE_MY.findAll(line)) {
                if (taken.any { it.first <= m.range.last && m.range.first <= it.last }) continue
                tokens.add(m.range to parseDate(null, m.groupValues[1], m.groupValues[2]))
            }

            for ((range, parsed) in tokens) {
                parsed ?: continue
                val before = line.substring(0, range.first)
                when {
                    FAB_LABEL.containsMatchIn(before) -> if (fab == null) fab = parsed
                    EXP_LABEL.containsMatchIn(before) -> if (exp == null) exp = parsed
                    else -> unlabeled.add(parsed)
                }
            }
        }

        when {
            fab == null && exp == null && unlabeled.size == 2 -> {
                val sorted = unlabeled.sortedBy { it.key() }
                fab = sorted[0]
                exp = sorted[1]
            }
            fab == null && exp == null && unlabeled.size == 1 -> exp = unlabeled[0]
            fab == null && exp != null && unlabeled.size == 1 -> fab = unlabeled[0]
            exp == null && fab != null && unlabeled.size == 1 -> exp = unlabeled[0]
        }
        return fab?.formatted() to exp?.formatted()
    }

    /** Validates and normalizes; day is null for month/year tokens. */
    private fun parseDate(dayStr: String?, monthStr: String, yearStr: String): ParsedDate? {
        val month = monthStr.toIntOrNull() ?: return null
        if (month !in 1..12) return null
        var year = yearStr.toIntOrNull() ?: return null
        when (yearStr.length) {
            2 -> {
                if (year !in TWO_DIGIT_YEAR_MIN..TWO_DIGIT_YEAR_MAX) return null
                year += 2000
            }
            4 -> if (year !in 2000..2099) return null
            else -> return null
        }
        val day = dayStr?.toIntOrNull()?.also { if (it !in 1..31) return null }
        return ParsedDate(day, month, year)
    }

    // ---------------------------------------------------------------- Name

    private class NameResult(
        val name: String?,
        val confidence: Int,
        val candidates: List<NameCandidate>,
        val bestLine: String?
    )

    private fun extractName(lines: List<String>): NameResult {
        val candidateLines = lines
            .filter { line ->
                !PPA_LABEL.containsMatchIn(line) &&
                    !DATE_MY.containsMatchIn(line) &&
                    !LOT_LABEL.containsMatchIn(line)
            }
            .map { it to scoreCandidate(it) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(MAX_CANDIDATES)
            .map { it.first }

        // Best similarity per dictionary name across every line and variant,
        // so runner-up candidates survive for the user picker.
        val sims = HashMap<String, Float>()
        val sourceLines = HashMap<String, String>()
        for (candidate in candidateLines) {
            val normalized = normalize(candidate)
            if (normalized.length < 3) continue

            accumulateMatches(normalized, CANDIDATE_FLOOR_SIM, sims, sourceLines, candidate)

            // Leading tokens too: brand names are often a single word among
            // dosage/form noise. Partial-line matches must be near-exact,
            // otherwise short tokens fuzz-match unrelated dictionary names.
            val tokens = normalized.split(' ')
            if (tokens.size > 1) {
                for (variant in listOf(tokens[0], tokens.take(2).joinToString(" ")).distinct()) {
                    if (variant.length < 3) continue
                    accumulateMatches(variant, TOKEN_MIN_SIM, sims, sourceLines, candidate)
                }
            }
        }

        val ranked = sims.entries.sortedByDescending { it.value }
        val best = ranked.firstOrNull()
        val bestConfidence = ((best?.value ?: 0f) * 100).toInt()

        val candidates = ranked
            .map { NameCandidate(it.key, (it.value * 100).toInt()) }
            .filter { it.confidence >= CANDIDATE_MIN_CONFIDENCE && it.confidence >= bestConfidence - CANDIDATE_MAX_GAP }
            .take(MAX_NAME_OPTIONS)

        return if (bestConfidence >= ACCEPT_CONFIDENCE && best != null) {
            NameResult(best.key, bestConfidence, candidates, sourceLines[best.key])
        } else {
            NameResult(null, bestConfidence, candidates, null)
        }
    }

    /**
     * Records into [sims] every dictionary name whose similarity to [query]
     * is at least [minSim], keeping the best similarity seen per name.
     */
    private fun accumulateMatches(
        query: String,
        minSim: Float,
        sims: MutableMap<String, Float>,
        sourceLines: MutableMap<String, String>,
        sourceLine: String
    ) {
        for (name in dictionary) {
            // Cheap length pruning before Levenshtein.
            val maxLen = max(name.length, query.length)
            if (abs(name.length - query.length) > maxLen * 0.4f) continue
            val upperBound = 1f - abs(name.length - query.length).toFloat() / maxLen
            if (upperBound < minSim) continue
            val sim = 1f - levenshtein(query, name).toFloat() / maxLen
            if (sim < minSim) continue
            val previous = sims[name]
            if (previous == null || sim > previous) {
                sims[name] = sim
                sourceLines[name] = sourceLine
            }
        }
    }

    private fun scoreCandidate(line: String): Int {
        val letters = line.count { it.isLetter() }
        if (letters < 4) return 0
        var score = min(letters, 20)
        val upper = line.count { it.isUpperCase() }
        if (letters > 0 && upper.toFloat() / letters > 0.7f) score += 10
        if (DOSAGE.containsMatchIn(line)) score += 15
        return score
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        val prev = IntArray(b.length + 1) { it }
        val curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = min(min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            prev.indices.forEach { prev[it] = curr[it] }
        }
        return prev[b.length]
    }

    private fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .uppercase()
            .replace(DOSAGE, " ")            // drop dosage tokens, they aren't part of the name
            .replace(Regex("[^A-Z0-9 .\\-/+']"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    companion object {
        private const val TAG = "VignetteParser"
        private const val DICT_ASSET = "drug_names.txt"
        private const val MAX_CANDIDATES = 5
        const val ACCEPT_CONFIDENCE = 60
        private const val TOKEN_MIN_SIM = 0.85f
        private const val CANDIDATE_FLOOR_SIM = 0.45f
        private const val CANDIDATE_MIN_CONFIDENCE = 45
        private const val CANDIDATE_MAX_GAP = 15
        private const val MAX_NAME_OPTIONS = 3
        private const val FALLBACK_CONFIDENCE = 50
        private const val TWO_DIGIT_YEAR_MIN = 20
        private const val TWO_DIGIT_YEAR_MAX = 39

        private val PPA_LABEL = Regex("""P\.?\s*P\.?\s*A""", RegexOption.IGNORE_CASE)
        private val MONEY = Regex("""(\d{1,6}(?:[.,]\d{1,2})?)\s*(?:DA|DZD)?""")
        private val MONEY_WITH_DA = Regex("""(\d{1,6}(?:[.,]\d{1,2})?)\s*(?:DA|DZD)\b""", RegexOption.IGNORE_CASE)
        private val DATE_FULL = Regex("""\b(\d{1,2})\s*[/.\-]\s*(\d{1,2})\s*[/.\-]\s*(\d{2,4})\b""")
        private val DATE_MY = Regex("""\b(\d{1,2})\s*[/.\-]\s*(\d{2,4})\b""")
        // Anchored to the end of the text preceding the date token: the label
        // must sit directly before the date ("EXP 11/27 FAB 11/24" resolves
        // each token to its own label).
        private val FAB_LABEL = Regex("""\b(?:DATE\s*)?(?:FAB\w*|MFG|PROD\w*)\s*[:. ]*$""", RegexOption.IGNORE_CASE)
        private val EXP_LABEL = Regex("""\b(?:DATE\s*)?(?:EXP\w*|PER\w*|PEREMPTION)\s*[:. ]*$""", RegexOption.IGNORE_CASE)
        private val LOT_LABEL = Regex("""\bLOT\b|\bBATCH\b|N[°o]\s""", RegexOption.IGNORE_CASE)
        private val DOSAGE = Regex("""\b\d+(?:[.,]\d+)?\s?(MG|G|ML|UI|%|MCG|µG)\b""", RegexOption.IGNORE_CASE)
        private val DOSAGE_FULL = Regex(
            """\b\d+(?:[.,]\d+)?\s*(?:MG|G|ML|UI|MCG|%)(?:\s*/\s*\d+(?:[.,]\d+)?\s*(?:MG|G|ML|UI|MCG|%)?)?\b""",
            RegexOption.IGNORE_CASE
        )
        private val COND_TOKEN = Regex(
            """\b(?:B|BTE|FL|TB|8)\s*/\s*\d+(?:\s*(?:ML|COMP|GELULES?|SACHETS?|AMP(?:OULES)?|FLACONS?|STYLOS?))?\b""",
            RegexOption.IGNORE_CASE
        )
    }
}
