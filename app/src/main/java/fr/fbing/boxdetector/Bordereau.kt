package fr.fbing.boxdetector

import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Where a bordereau stands, derived from `etat` + `date_depot_ftp`:
 *
 *  - [OUVERT]    `etat = "O"` — still being filled at the pharmacy.
 *  - [A_DEPOSER] `etat = "C"` but never sent — closed and waiting for the FTP deposit.
 *  - [DEPOSE]    closed *and* deposited — `date_depot_ftp` holds a real date.
 */
enum class BordereauStatus { OUVERT, A_DEPOSER, DEPOSE }

/** Shared formatting so item rows and group headers read alike. */
object BordereauFormat {

    private val AMOUNT_FMT = NumberFormat.getNumberInstance(Locale.FRANCE).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }

    private val MONTH_KEY_FMT = SimpleDateFormat("yyyy-MM", Locale.US)
    private val MONTH_LABEL_FMT = SimpleDateFormat("MMMM yyyy", Locale.FRANCE)

    /** "226 589,71" — grouped the way the CNAS paperwork writes amounts. */
    fun amount(value: Double): String = AMOUNT_FMT.format(value)

    /**
     * "2026-08" for [date]. Builds its own formatter because this one is called
     * while parsing on a background thread, unlike the display formatters, which
     * only ever run on the UI thread.
     */
    fun monthKey(date: Date): String = SimpleDateFormat("yyyy-MM", Locale.US).format(date)

    /** "16/08/2026" — day precision, all an observed date is worth. */
    fun dayLabel(date: Date): String = SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE).format(date)

    /** "2026-08" → "Août 2026". Echoes the key if it cannot be parsed. */
    fun monthLabel(key: String): String = try {
        MONTH_LABEL_FMT.format(MONTH_KEY_FMT.parse(key)!!)
            .replaceFirstChar { it.uppercase() }
    } catch (e: Exception) {
        key
    }
}

/**
 * One row of the `bordereau` node of the Realtime Database. Only the fields the
 * screen actually uses are modelled; the rest of the record is ignored.
 *
 * Every value arrives as a string, including amounts and dates, and unset dates
 * arrive as the [EPOCH_SENTINEL] rather than as an empty string — so parsing is
 * always defensive and the raw string stays available for display fallbacks.
 */
data class Bordereau(
    val idBord: String,
    val numBord: String,
    val codeCentre: String,
    val etat: String,
    val dateDepotFtp: String,
    val montVir: String,
    /**
     * Epoch millis of the moment this phone first saw [montVir] turn into a real
     * amount, or null when the virement predates the log or hasn't happened.
     * Filled in by [VirementLog] — the export itself carries no such date.
     */
    val firstSeenVireAt: Long? = null
) {
    /** The deposit instant, or null when the record still carries the sentinel. */
    val depositedAt: Date? = parseTimestamp(dateDepotFtp)

    /** Transferred amount in DA, or null when [montVir] is not a number. */
    val amount: Double? = montVir.toDoubleOrNull()

    /** True once the CNAS has actually paid the bordereau. */
    val isVire: Boolean = (amount ?: 0.0) > 0.0

    /**
     * "2026-08" — the month of the FTP deposit, used to group the virées by
     * date. Null when the bordereau was never deposited: `date_cloture` and
     * `date_ouverture` are the 1900 sentinel on every record of this export and
     * `updated_at` is a single export-wide timestamp, so the deposit is the only
     * real date the data carries.
     */
    val depositMonth: String? =
        if (depositedAt != null && dateDepotFtp.length >= 7) dateDepotFtp.substring(0, 7) else null

    /** When the virement was first observed landing, or null. */
    val virementSeenAt: Date? = firstSeenVireAt?.let { Date(it) }

    /**
     * The date the Virée view files this bordereau under. An observed virement
     * wins over the dépôt: the dépôt is only a proxy for when the money moved,
     * and a bordereau deposited in 2023 can be paid in 2026 — filing it under
     * 2023 would bury exactly the payment the pharmacist is watching for.
     */
    val groupDate: Date? = virementSeenAt ?: depositedAt

    /** "2026-08" for [groupDate]; null when neither date is known. */
    val groupMonth: String? =
        if (virementSeenAt != null) BordereauFormat.monthKey(virementSeenAt) else depositMonth

    /**
     * `num_bord` is "SSSSYY": a four-digit sequence followed by the two-digit
     * year it belongs to ("071826" is bordereau 0718 of 2026). Null when the
     * number does not follow that shape.
     */
    val year: Int? = if (numBord.length == 6 && numBord.all { it.isDigit() }) {
        2000 + numBord.substring(4).toInt()
    } else {
        null
    }

    /** The four-digit sequence within [year]; falls back to the whole number. */
    val sequence: String = if (year != null) numBord.substring(0, 4) else numBord

    val status: BordereauStatus = when {
        etat.equals("O", ignoreCase = true) -> BordereauStatus.OUVERT
        depositedAt != null -> BordereauStatus.DEPOSE
        // Anything closed — or carrying an état the export doesn't document —
        // that has never reached the FTP still needs to be deposited.
        else -> BordereauStatus.A_DEPOSER
    }

    /** "03/08/2026 09:21", or null when the bordereau was never deposited. */
    fun formatDepositDate(): String? = depositedAt?.let { DISPLAY_FMT.format(it) }

    /** "16/08/2026", or null when no virement has been observed landing. */
    fun formatVirementDate(): String? = virementSeenAt?.let(BordereauFormat::dayLabel)

    /** Grouped amount, or the raw string when [montVir] is not a number. */
    fun formatAmount(): String = amount?.let(BordereauFormat::amount) ?: montVir

    companion object {
        /** What the export writes when a date was never set. */
        private const val EPOCH_SENTINEL = "1900"

        private val DISPLAY_FMT = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE)

        /**
         * Firebase hands back either a JSON object keyed by id, or — when the keys
         * are numeric and dense, which they are here — a JSON *array* padded with
         * nulls for the missing indices. Both shapes are accepted.
         */
        fun parseAll(body: String): List<Bordereau> {
            val trimmed = body.trim()
            if (trimmed.isEmpty() || trimmed == "null") return emptyList()
            return when (trimmed.first()) {
                '[' -> {
                    val array = JSONArray(trimmed)
                    (0 until array.length()).mapNotNull { i ->
                        array.optJSONObject(i)?.let(::fromJson)
                    }
                }
                else -> {
                    val obj = JSONObject(trimmed)
                    obj.keys().asSequence()
                        .mapNotNull { key -> obj.optJSONObject(key)?.let(::fromJson) }
                        .toList()
                }
            }
        }

        private fun fromJson(json: JSONObject) = Bordereau(
            idBord = json.optString("id_bord"),
            numBord = json.optString("num_bord"),
            codeCentre = json.optString("code_centre"),
            etat = json.optString("etat"),
            dateDepotFtp = json.optString("date_depot_ftp"),
            montVir = json.optString("mont_vir")
        )

        /**
         * Parses "2026-08-03 09:21:34.869" (the milliseconds are optional).
         * Returns null for blanks and for the 1900 sentinel that stands in for
         * "never happened".
         */
        private fun parseTimestamp(raw: String): Date? {
            val value = raw.trim()
            if (value.isEmpty() || value.startsWith(EPOCH_SENTINEL)) return null
            val pattern = if (value.contains('.')) {
                "yyyy-MM-dd HH:mm:ss.SSS"
            } else {
                "yyyy-MM-dd HH:mm:ss"
            }
            return try {
                SimpleDateFormat(pattern, Locale.US).parse(value)
            } catch (e: Exception) {
                null
            }
        }
    }
}
