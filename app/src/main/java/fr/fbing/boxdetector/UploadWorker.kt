package fr.fbing.boxdetector

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.IOException

/**
 * Drains the local upload queue to the Apps Script endpoint. Runs only with
 * network (WorkManager constraint); failures retry with backoff, records are
 * removed from the queue only after a successful upload.
 */
class UploadWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val records = UploadQueue.peekAll(applicationContext)
        if (records.isEmpty()) return Result.success()

        val client = SheetsClient(applicationContext)
        if (!client.isConfigured()) {
            Log.i(TAG, "not configured, ${records.size} record(s) kept queued")
            return Result.success()
        }

        return try {
            client.appendRecords(records)
            UploadQueue.remove(applicationContext, records.size)
            Log.i(TAG, "queue drained (${records.size} record(s))")
            Result.success()
        } catch (e: IOException) {
            Log.w(TAG, "upload failed (${e.message}), will retry")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "UploadWorker"
    }
}
