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

        val dosage = matchField(
            dosageTokens(lines), variants.map { it.dosage },
            canon = ::canonCompact, offerOcrToken = true
        )
        val cond = matchField(
            condTokens(lines), variants.map { it.cond },
            canon = ::canonCond, offerOcrToken = true
        )
        val forme = matchField(
            formeTokens(lines), variants.map { it.forme },
            canon = ::canonForme, offerOcrToken = false // forme tokens are any word, too noisy to offer
        )
        return Triple(dosage, cond, forme)
    }

    // ------------------------------------------------- Field cross-reference

    /**
     * Ranks the known values of a field (frequency-weighted) by their best
     * similarity to any OCR token, comparing canonical forms (so B/8 matches
     * catalog BTE/8). With no OCR token everything scores 0 and frequency
     * alone ranks the options. When OCR clearly read something no catalog
     * entry resembles, the raw reading itself is offered as first choice.
     */
    private fun matchField(
        ocrTokens: List<String>,
        knownValues: List<String>,
        canon: (String) -> String,
        offerOcrToken: Boolean
    ): FieldResult {
        val freq = knownValues.filter { it.isNotBlank() }.groupingBy { it }.eachCount()
        if (freq.isEmpty()) {
            if (!offerOcrToken) return FieldResult.EMPTY
            val token = ocrTokens.firstOrNull() ?: return FieldResult.EMPTY
            return FieldResult(token, FALLBACK_CONFIDENCE, listOf(token), listOf(FALLBACK_CONFIDENCE))
        }

        val canonTokens = ocrTokens.map(canon).filter { it.isNotEmpty() }
        val scored = freq.entries
            .map { (known, count) ->
                val ck = canon(known)
                val sim = canonTokens.maxOfOrNull { similarity(it, ck) } ?: 0f
                Triple(known, sim, count)
            }
            .sortedWith(
                compareByDescending<Triple<String, Float, Int>> { it.second }
                    .thenByDescending { it.third }
            )
            .take(MAX_NAME_OPTIONS)

        val best = scored.first()
        val options = scored.map { it.first }.toMutableList()
        val optionConfs = scored.map { (it.second * 100).toInt() }.toMutableList()

        if (offerOcrToken && ocrTokens.isNotEmpty() && best.second < OCR_OPTION_SIM) {
            // Sticker says something the catalog doesn't know — trust the print
            // enough to offer it as a choice ahead of weak catalog guesses.
            val token = ocrTokens.first()
            options.add(0, token)
            optionConfs.add(0, FALLBACK_CONFIDENCE)
            return FieldResult(token, FALLBACK_CONFIDENCE, options.take(MAX_NAME_OPTIONS), optionConfs.take(MAX_NAME_OPTIONS))
        }

        return FieldResult(
            value = best.first,
            confidence = (best.second * 100).toInt(),
            options = options,
            optionConfidences = optionConfs
        )
    }

    /** Compact uppercase: spaces removed. */
    private fun canonCompact(s: String): String = s.uppercase().replace(Regex("\\s+"), "")

    /** B, BT and BTE all mean "boîte" — canonicalize to B for comparison. */
    private fun canonCond(s: String): String =
        canonCompact(s).replace(Regex("^BTE?(?=/)"), "B")

    /** Dots and spaces are formatting noise in formes (COMP. vs COMP). */
    private fun canonForme(s: String): String = canonCompact(s).replace(".", "")

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
            COND_TOKEN.findAll(line).flatMap { m ->
                // Common OCR misread of the leading "B".
                val core = canonCompact(m.groupValues[1]).replace(Regex("^8"), "B")
                val suffix = canonCompact(m.groupValues[2])
                // Both variants: catalog has plain "B/12" and "B/12 SACHETS".
                if (suffix.isEmpty()) listOf(core) else listOf(core, "$core $suffix")
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
        val value = condTokens(lines).firstOrNull() ?: return FieldResult.EMPTY
        return FieldResult(value, FALLBACK_CONFIDENCE, listOf(value), listOf(FALLBACK_CONFIDENCE))
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

        for (rawLine in lines) {
            // Accent-stripped + OCR-confusion-fixed copy (same length, so match
            // ranges still align): 0CT -> OCT, 1O/28 -> 10/28, 3XP -> EXP...
            val line = fixOcrConfusions(stripAccents(rawLine))
            val taken = mutableListOf<IntRange>()
            val tokens = mutableListOf<Pair<IntRange, ParsedDate?>>()
            for (m in DATE_FULL.findAll(line)) {
                taken.add(m.range)
                tokens.add(m.range to parseDate(m.groupValues[1], m.groupValues[2].toIntOrNull(), m.groupValues[3]))
            }
            for (m in DATE_MONTH_NAME.findAll(line)) {
                if (taken.any { it.first <= m.range.last && m.range.first <= it.last }) continue
                taken.add(m.range)
                val month = MONTH_NAMES[m.groupValues[2].uppercase()]
                tokens.add(m.range to parseDate(m.groupValues[1].ifEmpty { null }, month, m.groupValues[3]))
            }
            for (m in DATE_MY.findAll(line)) {
                if (taken.any { it.first <= m.range.last && m.range.first <= it.last }) continue
                tokens.add(m.range to parseDate(null, m.groupValues[1].toIntOrNull(), m.groupValues[2]))
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

    private fun stripAccents(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    /**
     * Repairs common OCR letter/digit swaps using each token's dominant type:
     * digits inside letter-dominant words become letters (0CT -> OCT,
     * 5EPT -> SEPT) and letters inside digit-dominant tokens become digits
     * (1O -> 10, 2O28 -> 2028). Length-preserving.
     */
    private fun fixOcrConfusions(line: String): String {
        val out = StringBuilder(line)
        for (m in WORD_TOKEN.findAll(line)) {
            val token = m.value
            val letters = token.count { it.isLetter() }
            val digits = token.count { it.isDigit() }
            if (letters >= 2 && letters > digits) {
                for (i in m.range) {
                    LETTER_FOR_DIGIT[line[i]]?.let { out.setCharAt(i, it) }
                }
            } else if (digits >= 1 && digits >= letters) {
                for (i in m.range) {
                    DIGIT_FOR_LETTER[line[i]]?.let { out.setCharAt(i, it) }
                }
            }
        }
        return out.toString()
    }

    /** Validates and normalizes; day is null for month/year tokens. */
    private fun parseDate(dayStr: String?, month: Int?, yearStr: String): ParsedDate? {
        if (month == null || month !in 1..12) return null
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
        // Below this best-catalog similarity, the raw OCR reading is offered too.
        private const val OCR_OPTION_SIM = 0.75f
        private const val TWO_DIGIT_YEAR_MIN = 20
        private const val TWO_DIGIT_YEAR_MAX = 39

        private val PPA_LABEL = Regex("""P\.?\s*P\.?\s*A""", RegexOption.IGNORE_CASE)
        private val MONEY = Regex("""(\d{1,6}(?:[.,]\d{1,2})?)\s*(?:DA|DZD)?""")
        private val MONEY_WITH_DA = Regex("""(\d{1,6}(?:[.,]\d{1,2})?)\s*(?:DA|DZD)\b""", RegexOption.IGNORE_CASE)
        private val DATE_FULL = Regex("""\b(\d{1,2})\s*[/.\-]\s*(\d{1,2})\s*[/.\-]\s*(\d{2,4})\b""")
        private val DATE_MY = Regex("""\b(\d{1,2})\s*[/.\-]\s*(\d{2,4})\b""")
        // "OCT 2028", "15 OCT 28", "OCT-2028", "AOUT 2027" — French + English
        // abbreviations; JUIL before JUL/JUN so juillet doesn't match juin.
        private val DATE_MONTH_NAME = Regex(
            """\b(?:(\d{1,2})\s*[/.\-\s]\s*)?(JAN|FEV|FEB|MAR|AVR|APR|MAI|MAY|JUIL|JUIN|JUL|JUN|AOU|AUG|SEP|OCT|NOV|DEC)[A-Z]*\.?\s*[/.\-\s]?\s*(\d{2,4})\b""",
            RegexOption.IGNORE_CASE
        )
        private val MONTH_NAMES = mapOf(
            "JAN" to 1, "FEV" to 2, "FEB" to 2, "MAR" to 3, "AVR" to 4, "APR" to 4,
            "MAI" to 5, "MAY" to 5, "JUIN" to 6, "JUN" to 6, "JUIL" to 7, "JUL" to 7,
            "AOU" to 8, "AUG" to 8, "SEP" to 9, "OCT" to 10, "NOV" to 11, "DEC" to 12
        )

        private val WORD_TOKEN = Regex("""[A-Za-z0-9]+""")
        // OCR glyph confusions, applied by token context in fixOcrConfusions.
        private val LETTER_FOR_DIGIT = mapOf(
            '0' to 'O', '1' to 'I', '3' to 'E', '4' to 'A', '5' to 'S', '8' to 'B'
        )
        private val DIGIT_FOR_LETTER = mapOf(
            'O' to '0', 'o' to '0', 'I' to '1', 'i' to '1', 'l' to '1',
            'S' to '5', 's' to '5', 'B' to '8', 'Z' to '2', 'z' to '2'
        )
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
        // Group 1 = core (B/8), group 2 = optional packaging word even when OCR
        // glues it to the number (b/8comprime). (?!\d) instead of \b so a
        // trailing letter can't kill the whole match.
        private val COND_TOKEN = Regex(
            """\b((?:BTE|BT|FL|B|8)\s*/\s*\d{1,3})\s*(ML|COMP\w*|GELULE\w*|SACHET\w*|AMP\w*|FLACON\w*|STYLO\w*)?(?!\d)""",
            RegexOption.IGNORE_CASE
        )
    }
}
