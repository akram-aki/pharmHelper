package fr.fbing.boxdetector

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.IOException

/**
 * The app's one daily wake-up. It polls `/notifications` for CNAS alerts and
 * raises one notification per newly seen alert, then sweeps `/bordereau` so
 * [VirementLog] can date any virement that has just landed.
 *
 * The two checks share a worker deliberately: both need the network and both
 * want to run about once a day, so one wake-up costs the battery less than two.
 * Scheduled by [CnasAlertMonitor.scheduleDailyCheck], and run once more each
 * time the app is opened via [CnasAlertMonitor.checkNow].
 */
class CnasAlertWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val alertResult = checkAlerts()
        // Independent of the alert check, and never allowed to fail the job: a
        // missed sweep only costs precision on one virement's date.
        sweepVirements()
        return alertResult
    }

    private fun checkAlerts(): Result {
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

    /**
     * Records today's `mont_vir` values so a virement that appears between two
     * openings of the app still gets dated to the day it landed rather than to
     * whenever the pharmacist next looks.
     */
    private fun sweepVirements() {
        val client = BordereauClient(applicationContext)
        if (!client.isConfigured()) return
        try {
            VirementLog.record(
                applicationContext, client.fetchAll(), System.currentTimeMillis()
            )
        } catch (e: IOException) {
            Log.w(TAG, "virement sweep failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "CnasAlertWorker"
    }
}
