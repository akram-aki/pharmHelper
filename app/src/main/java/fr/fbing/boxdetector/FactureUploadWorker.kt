package fr.fbing.boxdetector

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.File
import java.io.IOException

/**
 * Drains the facture upload queue to the Apps Script endpoint, one PDF per
 * request. Runs only with network (WorkManager constraint); failures retry
 * with backoff. A record is removed — and its local PDF deleted — only after a
 * successful upload; a record whose local PDF has gone missing is dropped
 * rather than retried forever.
 */
class FactureUploadWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val records = FactureUploadQueue.peekAll(applicationContext)
        if (records.isEmpty()) return Result.success()

        val client = SheetsClient(applicationContext)
        if (!client.isConfigured()) {
            Log.i(TAG, "not configured, ${records.size} facture(s) kept queued")
            return Result.success()
        }

        for (record in records) {
            val file = File(record.localPath)
            if (!file.exists()) {
                Log.w(TAG, "local PDF missing for ${record.id}, dropping")
                FactureUploadQueue.remove(applicationContext, record.id)
                continue
            }
            try {
                val base64 = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
                client.uploadFacture(record, base64)
                FactureUploadQueue.remove(applicationContext, record.id)
                file.delete()
                Log.i(TAG, "uploaded facture ${record.id}")
            } catch (e: IOException) {
                Log.w(TAG, "facture upload failed (${e.message}), will retry")
                return Result.retry()
            }
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "FactureUploadWorker"
    }
}
