package fr.fbing.boxdetector

import android.content.Context
import android.util.Log
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class NameCandidate(val name: String, val confidence: Int)

data class VignetteInfo(
    val name: String?,        // best dictionary match, null when below threshold
    val nameConfidence: Int,  // 0-100, of the best match even when rejected
    val nameCandidates: List<NameCandidate>, // plausible matches, best first, ≤3
    val dosage: String?,      // e.g. "500MG" or "1G/125MG"
    val ppa: String?,         // e.g. "245.50 DA"
    val fabDate: String?,     // as printed, e.g. "03/2024"
    val expDate: String?,
    val rawText: String
)

/**
 * Extracts structured vignette fields (medicine name, PPA price, fabrication
 * and expiration dates) from the raw OCR text. The name is fuzzy-matched
 * against the bundled nomenclature (assets/drug_names.txt, derived from the
 * pharmacy STOCK.mdb).
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
        val dosage = extractDosage(lines, nameResult.bestLine)

        Log.d(
            TAG,
            "name=${nameResult.name} conf=${nameResult.confidence} " +
                "candidates=${nameResult.candidates} dosage=$dosage ppa=$ppa fab=$fabDate exp=$expDate"
        )
        return VignetteInfo(
            nameResult.name, nameResult.confidence, nameResult.candidates,
            dosage, ppa, fabDate, expDate, rawText
        )
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

    private fun extractDates(lines: List<String>): Pair<String?, String?> {
        var fab: String? = null
        var exp: String? = null
        val unlabeled = mutableListOf<String>()

        for (line in lines) {
            for (m in DATE.findAll(line)) {
                val token = m.value
                val before = line.substring(0, m.range.first)
                when {
                    FAB_LABEL.containsMatchIn(before) -> if (fab == null) fab = token
                    EXP_LABEL.containsMatchIn(before) -> if (exp == null) exp = token
                    else -> unlabeled.add(token)
                }
            }
        }

        if (fab == null && exp == null && unlabeled.size == 2) {
            val sorted = unlabeled.sortedBy { dateKey(it) }
            fab = sorted[0]
            exp = sorted[1]
        } else {
            if (fab == null && exp != null && unlabeled.size == 1) fab = unlabeled[0]
            if (exp == null && fab != null && unlabeled.size == 1) exp = unlabeled[0]
        }
        return fab to exp
    }

    /** Sortable key (year, month) from date strings like 03/2024 or 15/03/24. */
    private fun dateKey(token: String): Int {
        val parts = token.split('/', '-', '.')
        return try {
            when (parts.size) {
                2 -> parts[1].toInt() * 100 + parts[0].toInt()          // MM/YYYY
                3 -> {
                    val year = parts[2].toInt().let { if (it < 100) it + 2000 else it }
                    year * 100 + parts[1].toInt()                        // DD/MM/YYYY
                }
                else -> 0
            }
        } catch (e: NumberFormatException) {
            0
        }
    }

    // -------------------------------------------------------------- Dosage

    /**
     * Prefers a dosage on the line the name was matched from, then falls back
     * to the first dosage-looking token anywhere. Handles compound dosages
     * like "1G/125MG".
     */
    private fun extractDosage(lines: List<String>, nameLine: String?): String? {
        val searchOrder = if (nameLine != null) listOf(nameLine) + lines else lines
        for (line in searchOrder) {
            val m = DOSAGE_FULL.find(line) ?: continue
            return m.value.uppercase().replace(Regex("\\s+"), "")
        }
        return null
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
                    !DATE.containsMatchIn(line) &&
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

        private val PPA_LABEL = Regex("""P\.?\s*P\.?\s*A""", RegexOption.IGNORE_CASE)
        private val MONEY = Regex("""(\d{1,6}(?:[.,]\d{1,2})?)\s*(?:DA|DZD)?""")
        private val MONEY_WITH_DA = Regex("""(\d{1,6}(?:[.,]\d{1,2})?)\s*(?:DA|DZD)\b""", RegexOption.IGNORE_CASE)
        private val DATE = Regex("""\b\d{2}[/.\-]\d{2}(?:[/.\-]\d{2,4})?\b|\b\d{2}[/.\-]\d{4}\b""")
        private val FAB_LABEL = Regex("""FAB|MFG|PROD""", RegexOption.IGNORE_CASE)
        private val EXP_LABEL = Regex("""EXP|PER|USE\s*BY""", RegexOption.IGNORE_CASE)
        private val LOT_LABEL = Regex("""\bLOT\b|\bBATCH\b|N[°o]\s""", RegexOption.IGNORE_CASE)
        private val DOSAGE = Regex("""\b\d+(?:[.,]\d+)?\s?(MG|G|ML|UI|%|MCG|µG)\b""", RegexOption.IGNORE_CASE)
        private val DOSAGE_FULL = Regex(
            """\b\d+(?:[.,]\d+)?\s*(?:MG|G|ML|UI|MCG|%)(?:\s*/\s*\d+(?:[.,]\d+)?\s*(?:MG|G|ML|UI|MCG|%)?)?\b""",
            RegexOption.IGNORE_CASE
        )
    }
}
