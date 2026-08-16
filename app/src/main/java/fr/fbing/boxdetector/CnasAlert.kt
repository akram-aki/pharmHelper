package fr.fbing.boxdetector

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One alert written by the pharmacy PC under `/notifications/<pushKey>`.
 *
 * The PC's daily task (`CHIFA_DailyCheck`) appends a record whenever CNAS
 * rejects the rotating daily code — HTTP 401/403 — meaning the automated
 * payment check has stopped working and someone has to fix the code.
 *
 * Records are append-only and rare: for long stretches there are none at all,
 * and the node may not exist. That is the healthy state, not an error.
 */
data class CnasAlert(
    /** The Firebase push key. Sorts both lexicographically and chronologically. */
    val key: String,
    val kind: String,
    val message: String,
    val at: String,
    val host: String
) {

    /** "15/08/2026 10:12" — the PC's wall clock; the raw string if unparseable. */
    fun formatAt(): String {
        val value = at.trim()
        if (value.isEmpty()) return ""
        // java.time, not SimpleDateFormat: the fractional seconds vary from one
        // digit to seven across this database, and SSS would swallow all of them
        // as milliseconds — ".1449744" would land 24 minutes late.
        runCatching { return OffsetDateTime.parse(value).format(DISPLAY_FMT) }
        runCatching { return LocalDateTime.parse(value).format(DISPLAY_FMT) }
        return value
    }

    fun toJson(): JSONObject = JSONObject()
        .put("key", key)
        .put("kind", kind)
        .put("message", message)
        .put("at", at)
        .put("host", host)

    companion object {
        /** The only kind the app reacts to; others are ignored by design. */
        const val KIND_AUTH_FAILED = "cnas_auth_failed"

        private val DISPLAY_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.FRANCE)

        fun fromJson(json: JSONObject): CnasAlert = CnasAlert(
            key = json.optString("key"),
            kind = json.optString("kind"),
            message = json.optString("message"),
            at = json.optString("at"),
            host = json.optString("host")
        )

        /**
         * Parses the `/notifications` node, keeping only [KIND_AUTH_FAILED]
         * records and sorting them oldest-first by push key.
         *
         * An absent node comes back as the literal `null` and yields an empty
         * list — the normal state when the PC has never failed. Both the object
         * shape (push keys) and the array shape Firebase uses for dense numeric
         * keys are accepted, so a hand-seeded node can't break this.
         */
        fun parseAll(body: String): List<CnasAlert> {
            val trimmed = body.trim()
            if (trimmed.isEmpty() || trimmed == "null") return emptyList()
            val parsed = when (trimmed.first()) {
                '[' -> {
                    val array = JSONArray(trimmed)
                    (0 until array.length()).mapNotNull { i ->
                        array.optJSONObject(i)?.let { fromNode(i.toString(), it) }
                    }
                }
                else -> {
                    val obj = JSONObject(trimmed)
                    obj.keys().asSequence()
                        .mapNotNull { key -> obj.optJSONObject(key)?.let { fromNode(key, it) } }
                        .toList()
                }
            }
            return parsed
                .filter { it.kind == KIND_AUTH_FAILED }
                .sortedBy { it.key }
        }

        private fun fromNode(key: String, json: JSONObject) = CnasAlert(
            key = key,
            kind = json.optString("kind"),
            message = json.optString("message"),
            at = json.optString("at"),
            host = json.optString("host")
        )
    }
}
