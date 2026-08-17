package fr.fbing.boxdetector

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Remembers which bordereaux this phone has already seen paid, so that a
 * virement landing later can be given a date.
 *
 * The export carries no date for the virement itself: `mont_vir` simply flips
 * from "0.00" to an amount, and `updated_at` is a single export-wide timestamp
 * shared by all 316 records — it says when the PC last exported, not when this
 * row changed. So the only date available is when this phone first observed the
 * amount. That is what gets recorded here, and it is as accurate as the sweep
 * that feeds it: daily via [CnasAlertWorker], plus every time the bordereau
 * screen loads.
 *
 * State lives in one small JSON file in `filesDir` guarded by a lock, the same
 * way [CnasAlertMonitor] and the upload queues persist theirs.
 */
object VirementLog {

    private val lock = Any()

    /**
     * Folds [records] against what was seen before, stamping every virement that
     * has just appeared with [now], and returns the records with
     * [Bordereau.firstSeenVireAt] filled in.
     *
     * The first ever call only seeds: every bordereau already paid is filed as
     * known-but-undated, so the virements that predate this feature don't all
     * claim to have arrived today. Genuine dates start accruing from the second
     * sweep onwards.
     */
    fun record(context: Context, records: List<Bordereau>, now: Long): List<Bordereau> =
        synchronized(lock) {
            val state = readState(context)
            val seen = state.optJSONObject(KEY_SEEN) ?: JSONObject()
            val seeding = !state.optBoolean(KEY_SEEDED, false)

            var stamped = 0
            records.forEach { bordereau ->
                if (!bordereau.isVire || bordereau.idBord.isEmpty()) return@forEach
                if (seen.has(bordereau.idBord)) return@forEach
                seen.put(bordereau.idBord, if (seeding) UNDATED else now)
                if (!seeding) stamped++
            }

            state.put(KEY_SEEN, seen)
            state.put(KEY_SEEDED, true)
            writeState(context, state)

            when {
                seeding -> Log.i(TAG, "seeded with ${seen.length()} virement(s) already present")
                stamped > 0 -> Log.i(TAG, "dated $stamped new virement(s)")
            }

            return records.map { bordereau ->
                val at = seen.optLong(bordereau.idBord, UNDATED)
                if (at > UNDATED) bordereau.copy(firstSeenVireAt = at) else bordereau
            }
        }

    private fun readState(context: Context): JSONObject {
        val file = stateFile(context)
        if (!file.exists()) return JSONObject()
        return try {
            JSONObject(file.readText())
        } catch (e: Exception) {
            // A truncated write shouldn't wedge the feature. Resetting costs the
            // recorded dates but re-seeds cleanly rather than dating everything
            // as brand new.
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

    private const val TAG = "VirementLog"
    private const val STATE_FILE = "virement_log.json"
    private const val KEY_SEEN = "seen_at"
    private const val KEY_SEEDED = "seeded"

    /** Recorded for virements that were already paid when the log was seeded. */
    private const val UNDATED = 0L
}
