package fr.fbing.boxdetector

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.IOException

/**
 * Drains the local upload queue into Google Sheets. Runs only with network
 * (WorkManager constraint); transient failures retry with backoff, records
 * are removed from the queue only after a successful append.
 */
class UploadWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val client = SheetsClient(applicationContext)
        val records = UploadQueue.peekAll(applicationContext)
        if (records.isEmpty()) return Result.success()
        if (!client.isConfigured()) {
            Log.i(TAG, "not configured, ${records.size} record(s) kept queued")
            return Result.success()
        }

        for (record in records) {
            try {
                client.appendRecord(record)
                UploadQueue.remove(applicationContext, 1)
            } catch (e: SheetsHttpException) {
                if (e.code == 400) {
                    // Malformed request for this record; keeping it would poison
                    // the queue forever.
                    Log.w(TAG, "dropping rejected record ${record.nom}", e)
                    UploadQueue.remove(applicationContext, 1)
                } else {
                    Log.w(TAG, "upload failed (HTTP ${e.code}), will retry")
                    return Result.retry()
                }
            } catch (e: IOException) {
                Log.w(TAG, "upload failed (${e.javaClass.simpleName}), will retry")
                return Result.retry()
            }
        }
        Log.i(TAG, "queue drained (${records.size} record(s))")
        return Result.success()
    }

    companion object {
        private const val TAG = "UploadWorker"
    }
}
