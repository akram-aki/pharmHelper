package fr.fbing.boxdetector

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * One scanned facture awaiting upload. The PDF bytes live on disk at
 * [localPath] (filesDir/factures/<id>.pdf); this record carries the metadata
 * and is persisted as one JSON line in the facture queue.
 */
data class FactureRecord(
    val id: String,
    val localPath: String,
    val supplier: String,
    val invoiceDate: String,
    val scanTimestamp: String,
    val pageCount: Int,
    val sizeBytes: Long
) {
    /** Full record persisted in the local queue file. */
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("localPath", localPath)
        .put("supplier", supplier)
        .put("invoiceDate", invoiceDate)
        .put("scanTimestamp", scanTimestamp)
        .put("pageCount", pageCount)
        .put("sizeBytes", sizeBytes)

    /** Server-facing metadata (no device-local path). */
    fun toMeta(): JSONObject = JSONObject()
        .put("id", id)
        .put("supplier", supplier)
        .put("invoiceDate", invoiceDate)
        .put("scanTimestamp", scanTimestamp)
        .put("pageCount", pageCount)
        .put("sizeBytes", sizeBytes)

    companion object {
        fun fromJson(json: JSONObject): FactureRecord? = try {
            FactureRecord(
                id = json.getString("id"),
                localPath = json.getString("localPath"),
                supplier = json.optString("supplier"),
                invoiceDate = json.optString("invoiceDate"),
                scanTimestamp = json.getString("scanTimestamp"),
                pageCount = json.optInt("pageCount", 1),
                sizeBytes = json.optLong("sizeBytes", 0)
            )
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Durable local queue of scanned factures awaiting upload: one JSON object per
 * line in filesDir/facture_queue.jsonl, with each PDF copied into
 * filesDir/factures/. Mirrors [UploadQueue] but carries a file per record;
 * WorkManager ([FactureUploadWorker]) drains it whenever connectivity allows.
 */
object FactureUploadQueue {

    private val lock = Any()

    /**
     * Copies the scanned [pdfUri] into app storage and enqueues a record.
     * Blocking file IO — call off the main thread. Returns the stored record,
     * or null if the PDF could not be read/copied.
     */
    fun enqueue(
        context: Context,
        pdfUri: Uri,
        supplier: String,
        invoiceDate: String,
        scanTimestamp: String,
        pageCount: Int
    ): FactureRecord? {
        val id = "facture_" + UUID.randomUUID().toString()
        val dir = File(context.filesDir, FACTURE_DIR).apply { mkdirs() }
        val dest = File(dir, "$id.pdf")

        val bytes = try {
            context.contentResolver.openInputStream(pdfUri)?.use { it.readBytes() }
        } catch (e: Exception) {
            Log.w(TAG, "cannot read scanned PDF", e)
            null
        } ?: return null

        try {
            dest.writeBytes(bytes)
        } catch (e: Exception) {
            Log.w(TAG, "cannot write PDF to app storage", e)
            return null
        }

        val record = FactureRecord(
            id = id,
            localPath = dest.absolutePath,
            supplier = supplier,
            invoiceDate = invoiceDate,
            scanTimestamp = scanTimestamp,
            pageCount = pageCount,
            sizeBytes = bytes.size.toLong()
        )
        synchronized(lock) {
            queueFile(context).appendText(record.toJson().toString() + "\n")
        }
        Log.d(TAG, "queued facture $id (${bytes.size} bytes, $pageCount page(s))")
        scheduleUpload(context)
        return record
    }

    fun peekAll(context: Context): List<FactureRecord> = synchronized(lock) {
        val file = queueFile(context)
        if (!file.exists()) return emptyList()
        file.readLines()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parsed = runCatching { FactureRecord.fromJson(JSONObject(line)) }.getOrNull()
                if (parsed == null) Log.w(TAG, "dropping corrupt facture queue line")
                parsed
            }
    }

    /** Removes the record with [id] once it has been uploaded (or is unusable). */
    fun remove(context: Context, id: String) {
        synchronized(lock) {
            val file = queueFile(context)
            if (!file.exists()) return
            val remaining = file.readLines()
                .filter { it.isNotBlank() }
                .filter { line ->
                    runCatching { JSONObject(line).optString("id") }.getOrNull() != id
                }
            file.writeText(if (remaining.isEmpty()) "" else remaining.joinToString("\n") + "\n")
        }
    }

    fun pendingCount(context: Context): Int = peekAll(context).size

    fun scheduleUpload(context: Context) {
        val request = OneTimeWorkRequestBuilder<FactureUploadWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // REPLACE: each enqueue/app-start attempts immediately rather than
        // chaining behind a backed-off retry (mirrors UploadQueue).
        WorkManager.getInstance(context)
            .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    private fun queueFile(context: Context) = File(context.filesDir, QUEUE_FILE)

    private const val TAG = "FactureUploadQueue"
    private const val QUEUE_FILE = "facture_queue.jsonl"
    private const val FACTURE_DIR = "factures"
    private const val WORK_NAME = "facture-upload"
}
