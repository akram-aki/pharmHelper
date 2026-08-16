package fr.fbing.boxdetector

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Owns everything the CNAS alert feature remembers between runs, plus the
 * scheduling of the checks that feed it.
 *
 * State lives in one small JSON file in `filesDir`, guarded by a lock, matching
 * how [UploadQueue] and [FactureUploadQueue] persist their queues — the app uses
 * no SharedPreferences anywhere.
 *
 * Two keys drive the whole feature, and because Firebase push keys sort both
 * lexicographically and chronologically, "newer than" is a string comparison:
 *
 *  - `notified_key` — the newest alert already raised as a notification, so a
 *    repeated poll of the same record stays quiet.
 *  - `acked_key` — the newest alert the pharmacist dismissed, so the banner
 *    stays down until something genuinely newer arrives.
 */
object CnasAlertMonitor {

    private val lock = Any()

    /** Schedules the once-a-day check. Safe to call from every app start. */
    fun scheduleDailyCheck(context: Context) {
        val request = PeriodicWorkRequestBuilder<CnasAlertWorker>(1, TimeUnit.DAYS)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .build()
        // KEEP, so re-scheduling on each launch doesn't restart the 24 h timer and
        // starve the check on a phone that gets opened often.
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_DAILY, ExistingPeriodicWorkPolicy.KEEP, request
        )
    }

    /**
     * Checks right now — used when the app is opened, so the banner reflects
     * reality even if the periodic job was delayed or killed. Runs under its own
     * unique name so it can never cancel the daily job.
     */
    fun checkNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<CnasAlertWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NOW, ExistingWorkPolicy.REPLACE, request
        )
    }

    /**
     * Records what a fetch returned and returns the alert that deserves a
     * notification — the newest one not yet notified about — or null if there is
     * nothing new.
     */
    fun recordFetch(context: Context, alerts: List<CnasAlert>): CnasAlert? = synchronized(lock) {
        val newest = alerts.lastOrNull() ?: return null
        val state = readState(context)
        val notified = state.optString(KEY_NOTIFIED).takeIf { it.isNotEmpty() }

        state.put(KEY_LATEST, newest.toJson())
        val isNew = notified == null || newest.key > notified
        if (isNew) state.put(KEY_NOTIFIED, newest.key)
        writeState(context, state)

        return if (isNew) newest else null
    }

    /** The newest alert the pharmacist hasn't dismissed yet, or null. */
    fun latestUnacknowledged(context: Context): CnasAlert? = synchronized(lock) {
        val state = readState(context)
        val latest = state.optJSONObject(KEY_LATEST) ?: return null
        val alert = CnasAlert.fromJson(latest)
        if (alert.key.isEmpty()) return null
        val acked = state.optString(KEY_ACKED)
        return if (acked.isEmpty() || alert.key > acked) alert else null
    }

    /** Marks the current alert handled; the banner returns only for a newer one. */
    fun acknowledge(context: Context) = synchronized(lock) {
        val state = readState(context)
        val latest = state.optJSONObject(KEY_LATEST) ?: return
        val key = latest.optString("key")
        if (key.isEmpty()) return
        state.put(KEY_ACKED, key)
        writeState(context, state)
    }

    private fun readState(context: Context): JSONObject {
        val file = stateFile(context)
        if (!file.exists()) return JSONObject()
        return try {
            JSONObject(file.readText())
        } catch (e: Exception) {
            // A truncated write shouldn't wedge the feature — start over.
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

    private const val TAG = "CnasAlertMonitor"
    private const val STATE_FILE = "cnas_alert.json"
    private const val KEY_NOTIFIED = "notified_key"
    private const val KEY_ACKED = "acked_key"
    private const val KEY_LATEST = "latest"

    const val WORK_DAILY = "cnas-alert-daily"
    const val WORK_NOW = "cnas-alert-now"
}
