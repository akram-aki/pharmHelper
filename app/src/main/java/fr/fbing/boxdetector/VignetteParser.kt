package fr.fbing.boxdetector

import android.content.Context
import android.util.Log
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class VignetteInfo(
    val name: String?,        // best dictionary match, null when below threshold
    val nameConfidence: Int,  // 0-100, of the best match even when rejected
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
        val (name, confidence, nameLine) = extractName(lines)
        val dosage = extractDosage(lines, nameLine)

        Log.d(TAG, "name=$name conf=$confidence dosage=$dosage ppa=$ppa fab=$fabDate exp=$expDate")
        return VignetteInfo(name, confidence, dosage, ppa, fabDate, expDate, rawText)
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

    private fun extractName(lines: List<String>): Triple<String?, Int, String?> {
        val candidates = lines
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

        var bestName: String? = null
        var bestSim = 0f
        var bestLine: String? = null
        for (candidate in candidates) {
            val normalized = normalize(candidate)
            if (normalized.length < 3) continue

            val (fullName, fullSim) = bestDictionaryMatch(normalized)
            if (fullSim > bestSim) {
                bestSim = fullSim
                bestName = fullName
                bestLine = candidate
            }

            // Leading tokens too: brand names are often a single word among
            // dosage/form noise. Partial-line matches must be near-exact,
            // otherwise short tokens fuzz-match unrelated dictionary names.
            val tokens = normalized.split(' ')
            if (tokens.size > 1) {
                for (variant in listOf(tokens[0], tokens.take(2).joinToString(" ")).distinct()) {
                    if (variant.length < 3) continue
                    val (name, sim) = bestDictionaryMatch(variant)
                    if (sim >= TOKEN_MIN_SIM && sim > bestSim) {
                        bestSim = sim
                        bestName = name
                        bestLine = candidate
                    }
                }
            }
        }

        val confidence = (bestSim * 100).toInt()
        return if (confidence >= ACCEPT_CONFIDENCE) {
            Triple(bestName, confidence, bestLine)
        } else {
            Triple(null, confidence, null)
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

    private fun bestDictionaryMatch(candidate: String): Pair<String?, Float> {
        var bestName: String? = null
        var bestSim = 0f
        for (name in dictionary) {
            // Cheap length pruning before Levenshtein.
            val maxLen = max(name.length, candidate.length)
            if (abs(name.length - candidate.length) > maxLen * 0.4f) continue
            val upperBound = 1f - abs(name.length - candidate.length).toFloat() / maxLen
            if (upperBound <= bestSim) continue
            val sim = 1f - levenshtein(candidate, name).toFloat() / maxLen
            if (sim > bestSim) {
                bestSim = sim
                bestName = name
            }
        }
        return bestName to bestSim
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
