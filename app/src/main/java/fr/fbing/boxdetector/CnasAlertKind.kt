package fr.fbing.boxdetector

/**
 * What the pharmacy PC is reporting in a `/notifications` record.
 *
 * Deliberately an **open set**. The PC may start writing kinds this build has
 * never heard of, and the cost of the two possible mistakes is lopsided: showing
 * a generic title for an unfamiliar problem is a cosmetic miss, while dropping
 * the record leaves the pharmacist believing the automated check is healthy when
 * it isn't. So anything unrecognised maps to [UNKNOWN] and still alerts.
 *
 * [RECOVERED] is the one kind that must *not* alert. The spec lists it as
 * something the PC may add later — a record written on the first successful
 * check after a failure — so reacting to every entry blindly would turn good
 * news into an alarm. Handled here so it behaves correctly the day it appears,
 * and harmlessly until then.
 */
enum class CnasAlertKind(val id: String, val alerting: Boolean = true) {

    /** HTTP 401/403 — the rotating daily code is wrong. The urgent one. */
    AUTH_FAILED("cnas_auth_failed"),

    /** Any other failed request: 404/500/503, or a network, TLS or timeout error. */
    REQUEST_FAILED("cnas_error"),

    /** The checker script itself crashed, so nothing was verified at all. */
    SCRIPT_ERROR("cnas_script_error"),

    /** A successful check after a failure. Clears the warning instead of raising one. */
    RECOVERED("cnas_ok", alerting = false),

    /** A kind this build doesn't know. Still surfaced, with a generic title. */
    UNKNOWN("");

    /** Notification title and banner headline. */
    val titleRes: Int
        get() = when (this) {
            AUTH_FAILED -> R.string.cnas_kind_auth_title
            REQUEST_FAILED -> R.string.cnas_kind_error_title
            SCRIPT_ERROR -> R.string.cnas_kind_script_title
            RECOVERED -> R.string.cnas_kind_ok_title
            UNKNOWN -> R.string.cnas_kind_unknown_title
        }

    /** The line under the headline, saying what it means for the pharmacy. */
    val descRes: Int
        get() = when (this) {
            AUTH_FAILED -> R.string.cnas_kind_auth_desc
            REQUEST_FAILED -> R.string.cnas_kind_error_desc
            SCRIPT_ERROR -> R.string.cnas_kind_script_desc
            RECOVERED -> R.string.cnas_kind_ok_desc
            UNKNOWN -> R.string.cnas_kind_unknown_desc
        }

    companion object {
        /** Maps the raw `kind` string; anything unmatched becomes [UNKNOWN]. */
        fun of(raw: String): CnasAlertKind {
            val value = raw.trim()
            if (value.isEmpty()) return UNKNOWN
            return values().firstOrNull {
                it.id.isNotEmpty() && it.id.equals(value, ignoreCase = true)
            } ?: UNKNOWN
        }
    }
}
