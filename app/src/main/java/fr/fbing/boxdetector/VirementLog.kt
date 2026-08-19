package fr.fbing.boxdetector

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Remembers which bordereaux this phone has already seen paid, so that a
 * virement landing later can be given a date — and so the 10:00 alarm knows
 * which ones are genuinely new.
 *
 * The export carries no date for the virement itself: `mont_vir` simply flips
 * from "0.00" to an amount, and `updated_at` is a single export-wide timestamp
 * shared by all records — it says when the PC last exported, not when this row
 * changed. So the only date available is when this phone first observed the
 * amount.
 *
 * State lives in one small JSON file in `filesDir` guarded by a lock, the same
 * way [CnasAlertMonitor] and the upload queues persist theirs.
 */
object VirementLog {

    private val lock = Any()

    /**
     * What one sweep saw.
     *
     * [all] is every record, annotated with any date already on file, and is
     * what the list screen renders. [fresh] holds only the bordereaux whose
     * virement appeared on *this* pass — the alarm's trigger, and empty on the
     * seeding pass.
     */
    data class Sweep(val all: List<Bordereau>, val fresh: List<Bordereau>)

    /**
     * Folds [records] against what was seen before, stamping every virement that
     * has just appeared with [now].
     *
     * The first ever call only seeds: every bordereau already paid is filed as
     * known-but-undated and [Sweep.fresh] comes back empty, so the virements
     * that predate this feature neither claim to have arrived today nor set the
     * alarm off. Genuine detections start from the second sweep onwards.
     */
    fun record(context: Context, records: List<Bordereau>, now: Long): Sweep =
        synchronized(lock) {
            val state = readState(context)
            val seen = state.optJSONObject(KEY_SEEN) ?: JSONObject()
            val seeding = !state.optBoolean(KEY_SEEDED, false)

            val fresh = mutableListOf<Bordereau>()
            records.forEach { bordereau ->
                if (!bordereau.isVire || bordereau.idBord.isEmpty()) return@forEach
                if (seen.has(bordereau.idBord)) return@forEach
                seen.put(bordereau.idBord, if (seeding) UNDATED else now)
                if (!seeding) fresh += bordereau.copy(firstSeenVireAt = now)
            }

            state.put(KEY_SEEN, seen)
            state.put(KEY_SEEDED, true)
            writeState(context, state)

            when {
                seeding -> Log.i(TAG, "seeded with ${seen.length()} virement(s) already present")
                fresh.isNotEmpty() -> Log.i(TAG, "dated ${fresh.size} new virement(s)")
            }

            val all = records.map { bordereau ->
                val at = seen.optLong(bordereau.idBord, UNDATED)
                if (at > UNDATED) bordereau.copy(firstSeenVireAt = at) else bordereau
            }
            return Sweep(all, fresh)
        }

    private fun readState(context: Context): JSONObject {
        val file = stateFile(context)
        if (!file.exists()) return JSONObject()
        return try {
            JSONObject(file.readText())
        } catch (e: Exception) {
            // A truncated write shouldn't wedge the feature. Resetting costs the
            // recorded dates but re-seeds cleanly rather than dating everything
            // as brand new — and, more importantly, rather than alarming on all
            // of them at the next 10:00.
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
