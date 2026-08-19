package fr.fbing.boxdetector

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One paid bordereau, reduced to what the alert screen needs to name it. */
data class PaidBordereau(val numBord: String, val amount: String) {

    fun toJson(): JSONObject = JSONObject()
        .put("num_bord", numBord)
        .put("amount", amount)

    companion object {
        fun fromJson(json: JSONObject) = PaidBordereau(
            numBord = json.optString("num_bord"),
            amount = json.optString("amount")
        )

        fun of(bordereau: Bordereau) = PaidBordereau(
            numBord = bordereau.numBord,
            amount = bordereau.formatAmount()
        )
    }
}

/**
 * Everything the virement alarm remembers between runs: the ringtone the
 * pharmacist chose, the payments waiting to be acknowledged, and the day the
 * last check ran so 10:00 can only fire once per day.
 *
 * One JSON file in `filesDir` guarded by a lock, mirroring [CnasAlertMonitor].
 */
object VirementAlertStore {

    private val lock = Any()

    /** The chosen alarm sound, or null to fall back to the system default. */
    fun ringtone(context: Context): Uri? = synchronized(lock) {
        val raw = readState(context).optString(KEY_RINGTONE)
        return if (raw.isEmpty()) null else runCatching { Uri.parse(raw) }.getOrNull()
    }

    fun setRingtone(context: Context, uri: Uri?) = synchronized(lock) {
        val state = readState(context)
        state.put(KEY_RINGTONE, uri?.toString().orEmpty())
        writeState(context, state)
    }

    /** Payments the pharmacist has not dismissed yet; empty when all is quiet. */
    fun pending(context: Context): List<PaidBordereau> = synchronized(lock) {
        val array = readState(context).optJSONArray(KEY_PENDING) ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            array.optJSONObject(i)?.let(PaidBordereau::fromJson)
        }
    }

    /**
     * Adds [paid] to whatever is already waiting. Accumulates rather than
     * replaces: two payments on consecutive days with no dismissal in between
     * should both still be named.
     */
    fun addPending(context: Context, paid: List<PaidBordereau>): Unit = synchronized(lock) {
        if (paid.isEmpty()) return
        val state = readState(context)
        val array = state.optJSONArray(KEY_PENDING) ?: JSONArray()
        val known = (0 until array.length())
            .mapNotNull { array.optJSONObject(it)?.optString("num_bord") }
            .toMutableSet()
        paid.forEach { item ->
            if (known.add(item.numBord)) array.put(item.toJson())
        }
        state.put(KEY_PENDING, array)
        writeState(context, state)
    }

    /** Dismissal: silences the alarm's aftermath and clears the home banner. */
    fun clearPending(context: Context) = synchronized(lock) {
        val state = readState(context)
        state.put(KEY_PENDING, JSONArray())
        writeState(context, state)
    }

    /**
     * Whether the once-a-day fetch has already happened. The 10:00 alarm is
     * exact, but a reboot or a re-arm can replay it.
     */
    fun hasCheckedToday(context: Context, now: Long): Boolean = synchronized(lock) {
        return readState(context).optString(KEY_LAST_CHECK) == DAY_FMT.format(Date(now))
    }

    /**
     * Records the day as done. Called only once the fetch has actually
     * succeeded, so a morning with no network leaves the day open to retry
     * rather than silently burning it.
     */
    fun markCheckedToday(context: Context, now: Long) = synchronized(lock) {
        val state = readState(context)
        state.put(KEY_LAST_CHECK, DAY_FMT.format(Date(now)))
        writeState(context, state)
    }

    private fun readState(context: Context): JSONObject {
        val file = stateFile(context)
        if (!file.exists()) return JSONObject()
        return try {
            JSONObject(file.readText())
        } catch (e: Exception) {
            Log.w(TAG, "unreadable $STATE_FILE (${e.javaClass.simpleName}) — resetting")
            JSONObject()
        }
    }

    private fun writeState(context: Context, state: JSONObject) {
        try {
            stateFile(context).writeText(state.toString())
        } catch (e: Exception) {
            Log.w(TAG, "could not persist $STATE_FILE: ${e.javaClass.simpleName}")
        }
    }

    private fun stateFile(context: Context) = File(context.filesDir, STATE_FILE)

    private val DAY_FMT = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private const val TAG = "VirementAlertStore"
    private const val STATE_FILE = "virement_alert.json"
    private const val KEY_RINGTONE = "ringtone"
    private const val KEY_PENDING = "pending"
    private const val KEY_LAST_CHECK = "last_check"
}
