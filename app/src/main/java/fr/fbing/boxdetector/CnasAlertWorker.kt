package fr.fbing.boxdetector

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.IOException

/**
 * Polls `/notifications` for CNAS alerts and raises one notification per newly
 * seen alert. Scheduled daily by [CnasAlertMonitor.scheduleDailyCheck], and once
 * more each time the app is opened via [CnasAlertMonitor.checkNow].
 *
 * Deliberately alert-only. The virement sweep lives on its own 10:00 alarm
 * ([VirementAlarmScheduler]) and must stay there: if this worker swept too, it
 * would consume a newly paid bordereau before 10:00 and the alarm would never
 * sound for it.
 */
class CnasAlertWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val client = CnasAlertClient(applicationContext)
        if (!client.isConfigured()) {
            // Matches the upload workers: a build without the config asset stays
            // quiet rather than failing and retrying forever.
            Log.i(TAG, "not configured — skipping CNAS alert check")
            return Result.success()
        }

        val alerts = try {
            client.fetchRecent()
        } catch (e: IOException) {
            Log.w(TAG, "CNAS alert check failed: ${e.message}")
            return Result.retry()
        }

        if (alerts.isEmpty()) {
            // No alert has ever been written, or none recently — the healthy case.
            Log.d(TAG, "no CNAS alerts")
            return Result.success()
        }

        CnasAlertMonitor.recordFetch(applicationContext, alerts)?.let { fresh ->
            Log.i(TAG, "new CNAS alert ${fresh.key}")
            CnasNotifications.show(applicationContext, fresh)
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "CnasAlertWorker"
    }
}
