package fr.fbing.boxdetector

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.File

/**
 * Drains the facture upload queue to the Apps Script endpoint, one PDF per
 * request. Runs only with network (WorkManager constraint); failures retry with
 * backoff. A record is removed — and its local PDF deleted — only after a
 * successful upload, so a lost response never loses a facture; the server
 * deduplicates by facture id, so re-sending one is harmless.
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
            // Don't start another multi-MB POST if WorkManager wants us to stop;
            // the remaining records stay queued for the next run.
            if (isStopped) {
                Log.i(TAG, "stopped, ${records.size} facture(s) left queued")
                return Result.retry()
            }

            val file = File(record.localPath)
            if (!file.exists()) {
                Log.w(TAG, "local PDF gone for ${record.id} — cannot upload, removing from queue")
                FactureUploadQueue.remove(applicationContext, record.id)
                continue
            }
            try {
                val base64 = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
                client.uploadFacture(record, base64)
                FactureUploadQueue.remove(applicationContext, record.id)
                file.delete()
                Log.i(TAG, "uploaded facture ${record.id}")
            } catch (t: Throwable) {
                // Throwable, not IOException: an OutOfMemoryError while base64ing
                // a 15-page PDF, or a JSON error, would otherwise escape doWork()
                // and WorkManager would mark this failed — no retry, facture stuck.
                Log.w(TAG, "facture ${record.id} upload failed (${t.javaClass.simpleName}: ${t.message}), will retry")
                return Result.retry()
            }
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "FactureUploadWorker"
    }
}
