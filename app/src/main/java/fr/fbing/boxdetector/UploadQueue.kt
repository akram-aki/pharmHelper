package fr.fbing.boxdetector

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class ExpiredRecord(
    val timestamp: String,
    val nom: String,
    val dosage: String,
    val conditionnement: String,
    val forme: String,
    val ppa: String,
    val datePeremption: String,
    val quantite: Int
) {
    fun toJson(): JSONObject = JSONObject()
        .put("timestamp", timestamp)
        .put("nom", nom)
        .put("dosage", dosage)
        .put("conditionnement", conditionnement)
        .put("forme", forme)
        .put("ppa", ppa)
        .put("datePeremption", datePeremption)
        .put("quantite", quantite)

    companion object {
        fun fromJson(json: JSONObject): ExpiredRecord? = try {
            ExpiredRecord(
                timestamp = json.getString("timestamp"),
                nom = json.getString("nom"),
                dosage = json.optString("dosage"),
                conditionnement = json.optString("conditionnement"),
                forme = json.optString("forme"),
                ppa = json.optString("ppa"),
                datePeremption = json.optString("datePeremption"),
                quantite = json.getInt("quantite")
            )
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Durable local queue of records awaiting upload: one JSON object per line in
 * filesDir/upload_queue.jsonl. Records survive restarts and missing network;
 * WorkManager drains the queue whenever connectivity is available.
 */
object UploadQueue {

    private val lock = Any()

    fun enqueue(context: Context, record: ExpiredRecord) {
        synchronized(lock) {
            queueFile(context).appendText(record.toJson().toString() + "\n")
        }
        Log.d(TAG, "queued ${record.nom} x${record.quantite}")
        scheduleUpload(context)
    }

    fun peekAll(context: Context): List<ExpiredRecord> = synchronized(lock) {
        val file = queueFile(context)
        if (!file.exists()) return emptyList()
        file.readLines()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parsed = runCatching { ExpiredRecord.fromJson(JSONObject(line)) }.getOrNull()
                if (parsed == null) Log.w(TAG, "dropping corrupt queue line")
                parsed
            }
    }

    /** Removes the first [count] lines once they have been uploaded. */
    fun remove(context: Context, count: Int) {
        synchronized(lock) {
            val file = queueFile(context)
            if (!file.exists()) return
            val remaining = file.readLines().filter { it.isNotBlank() }.drop(count)
            file.writeText(if (remaining.isEmpty()) "" else remaining.joinToString("\n") + "\n")
        }
    }

    fun scheduleUpload(context: Context) {
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // REPLACE (not APPEND_OR_REPLACE): each enqueue/app-start cancels any
        // backed-off retry and attempts immediately — APPEND chains wedge on
        // MIUI when a member sits in retry limbo.
        WorkManager.getInstance(context)
            .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    private fun queueFile(context: Context) = File(context.filesDir, QUEUE_FILE)

    private const val TAG = "UploadQueue"
    private const val QUEUE_FILE = "upload_queue.jsonl"
    private const val WORK_NAME = "sheets-upload"
}
