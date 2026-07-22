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

    private val dateExtractor = DateExtractor()

    private val dictionary: List<String> by lazy {
        val start = System.currentTimeMillis()
        val names = context.assets.open(DICT_ASSET).bufferedReader().readLines()
            .map { it.trim() }
            .filter { it.length >= 3 }
        Log.d(TAG, "dictionary: ${names.size} names in ${System.currentTimeMillis() - start}ms")
        names
    }

    /** Primary entry: structured OCR (incl. edge strips) drives date extraction. */
    fun parse(ocr: OcrResult): VignetteInfo {
        val lines = ocr.mergedText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val (fabDate, expDate) = dateExtractor.extract(ocr.lines)
        return assemble(lines, fabDate, expDate, ocr.mergedText)
    }

    /** Back-compat: flat string; dates come from synthetic single-frame lines. */
    fun parse(rawText: String): VignetteInfo {
        val synthetic = rawText.lines()
            .mapIndexed { i, t -> OcrLine(t.trim(), TextBox(0, i * 10, 1, i * 10 + 1), null, OcrRegion.FULL, 0) }
            .filter { it.text.isNotEmpty() }
        val (fabDate, expDate) = dateExtractor.extract(synthetic)
        val lines = rawText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        return assemble(lines, fabDate, expDate, rawText)
    }

    private fun assemble(lines: List<String>, fabDate: String?, expDate: String?, rawText: String): VignetteInfo {
        val ppa = extractPpa(lines)
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
                    !DATE_LIKE.containsMatchIn(line) &&
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

        private val PPA_LABEL = Regex("""P\.?\s*P\.?\s*A""", RegexOption.IGNORE_CASE)
        private val MONEY = Regex("""(\d{1,6}(?:[.,]\d{1,2})?)\s*(?:DA|DZD)?""")
        private val MONEY_WITH_DA = Regex("""(\d{1,6}(?:[.,]\d{1,2})?)\s*(?:DA|DZD)\b""", RegexOption.IGNORE_CASE)
        // Numeric date shape — only used to keep date lines out of name candidates.
        private val DATE_LIKE = Regex("""\d{1,2}\s*[/.\-]\s*\d{2,4}""")
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
