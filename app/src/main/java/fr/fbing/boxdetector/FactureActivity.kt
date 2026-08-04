package fr.fbing.boxdetector

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * "Scan factures": launches the ML Kit Document Scanner (CamScanner-style edge
 * detection + perspective correction + enhancement + multi-page → PDF), then
 * lets the user type the supplier and pick the invoice date (calendar dialog)
 * before enqueuing the confirmed PDF for durable upload via [FactureUploadQueue].
 * A "Mes factures" button opens the shared Drive folder to browse saved PDFs.
 */
class FactureActivity : AppCompatActivity() {

    private lateinit var panelIntro: View
    private lateinit var panelReview: View
    private lateinit var pendingStatus: TextView
    private lateinit var uploadProgress: View
    private lateinit var uploadProgressText: TextView
    private lateinit var thumbnail: ImageView
    private lateinit var pageCountView: TextView
    private lateinit var inputSupplier: TextInputEditText
    private lateinit var inputDate: TextInputEditText
    private lateinit var dateInputLayout: TextInputLayout
    private lateinit var btnScan: MaterialButton
    private lateinit var btnFactures: MaterialButton
    private lateinit var btnSave: MaterialButton

    private lateinit var io: ExecutorService

    private var pendingPdfUri: Uri? = null
    private var pendingPageCount: Int = 1

    /** Chosen invoice date as "dd/MM/yyyy" (empty until picked), plus the
     *  picker's current UTC-ms selection (defaults to today). */
    private var invoiceDate: String = ""
    private var dateSelection: Long = MaterialDatePicker.todayInUtcMilliseconds()

    /** True while a facture upload is queued or running — blocks new scans. */
    private var uploadInFlight: Boolean = false

    private val scannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            onScanResult(result.data)
        }
        // Cancelled/failed: stay on the current screen silently.
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_facture)

        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        panelIntro = findViewById(R.id.panel_intro)
        panelReview = findViewById(R.id.panel_review)
        pendingStatus = findViewById(R.id.pending_status)
        uploadProgress = findViewById(R.id.upload_progress)
        uploadProgressText = findViewById(R.id.upload_progress_text)
        thumbnail = findViewById(R.id.thumbnail)
        pageCountView = findViewById(R.id.page_count)
        inputSupplier = findViewById(R.id.input_supplier)
        inputDate = findViewById(R.id.input_date)
        dateInputLayout = findViewById(R.id.date_input_layout)
        btnScan = findViewById(R.id.btn_scan)
        btnFactures = findViewById(R.id.btn_factures)
        btnSave = findViewById(R.id.btn_save)

        btnScan.setOnClickListener {
            if (uploadInFlight) {
                Toast.makeText(this, R.string.facture_upload_blocked, Toast.LENGTH_SHORT).show()
            } else {
                launchScanner()
            }
        }
        btnFactures.setOnClickListener { openFacturesFolder() }
        btnSave.setOnClickListener { save() }
        // The date field is not typeable — tapping it (or its calendar icon)
        // opens the picker.
        inputDate.setOnClickListener { showDatePicker() }
        dateInputLayout.setEndIconOnClickListener { showDatePicker() }
        // Waiting queue shown as "… — Réessayer": tap to attempt right away.
        pendingStatus.setOnClickListener { FactureUploadQueue.forceUpload(this) }

        io = Executors.newSingleThreadExecutor()

        // Retry any factures still queued from previous offline sessions. KEEP
        // policy, so this never cancels an upload already in flight.
        FactureUploadQueue.scheduleUpload(this)
        observeUploads()
    }

    override fun onResume() {
        super.onResume()
        updatePendingStatus()
    }

    /**
     * Tracks the upload worker so the screen reflects progress live and refuses
     * new scans until the queue drains.
     */
    private fun observeUploads() {
        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData(FactureUploadQueue.WORK_NAME)
            .observe(this) { infos ->
                val running = infos.any { it.state == WorkInfo.State.RUNNING }
                if (io.isShutdown) return@observe
                io.execute {
                    val pending = FactureUploadQueue.pendingCount(this)
                    runOnUiThread { renderUploadState(running, pending) }
                }
            }
    }

    /**
     * One facture at a time: while anything is queued the scan button stays
     * disabled. A queue that is waiting (no network yet) shows a tappable
     * "Réessayer" so the user is never stuck without recourse.
     */
    private fun renderUploadState(running: Boolean, pending: Int) {
        uploadInFlight = pending > 0
        when {
            pending == 0 -> {
                uploadProgress.visibility = View.GONE
                pendingStatus.visibility = View.GONE
            }
            running -> {
                uploadProgressText.text = getString(R.string.facture_uploading, pending)
                uploadProgress.visibility = View.VISIBLE
                pendingStatus.visibility = View.GONE
            }
            else -> {
                uploadProgress.visibility = View.GONE
                pendingStatus.text = getString(R.string.facture_pending_status, pending) +
                    " — " + getString(R.string.facture_retry_button)
                pendingStatus.visibility = View.VISIBLE
            }
        }
        btnScan.isEnabled = pending == 0
        if (panelReview.visibility == View.VISIBLE) {
            btnSave.isEnabled = pending == 0 && pendingPdfUri != null
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun launchScanner() {
        val options = GmsDocumentScannerOptions.Builder()
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .setGalleryImportAllowed(true)
            .setPageLimit(MAX_PAGES)
            .setResultFormats(
                GmsDocumentScannerOptions.RESULT_FORMAT_JPEG,
                GmsDocumentScannerOptions.RESULT_FORMAT_PDF
            )
            .build()
        GmsDocumentScanning.getClient(options)
            .getStartScanIntent(this)
            .addOnSuccessListener { intentSender ->
                scannerLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            }
            .addOnFailureListener {
                Toast.makeText(this, R.string.facture_scanner_unavailable, Toast.LENGTH_LONG).show()
            }
    }

    /** Opens the shared Drive folder (the "Mes factures" web page) externally. */
    private fun openFacturesFolder() {
        val url = getString(R.string.facture_folder_url)
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.facture_no_browser, Toast.LENGTH_SHORT).show()
        }
    }

    private fun onScanResult(data: Intent?) {
        val result = GmsDocumentScanningResult.fromActivityResultIntent(data)
        val pdf = result?.pdf
        if (result == null || pdf == null) {
            Toast.makeText(this, R.string.facture_scan_failed, Toast.LENGTH_SHORT).show()
            return
        }
        pendingPdfUri = pdf.uri
        pendingPageCount = pdf.pageCount

        showReviewState(pdf.pageCount)
        // Page-1 preview; setImageURI(null) safely clears if there's no page image.
        thumbnail.setImageURI(result.pages?.firstOrNull()?.imageUri)
    }

    private fun showReviewState(pageCount: Int) {
        inputSupplier.setText("")
        inputDate.setText("")
        invoiceDate = ""
        dateSelection = MaterialDatePicker.todayInUtcMilliseconds()
        pageCountView.text = getString(R.string.facture_pages, pageCount)
        panelIntro.visibility = View.GONE
        panelReview.visibility = View.VISIBLE
        btnScan.visibility = View.GONE
        btnFactures.visibility = View.GONE
        btnSave.visibility = View.VISIBLE
        btnSave.isEnabled = !uploadInFlight
    }

    private fun showDatePicker() {
        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText(R.string.facture_date)
            .setSelection(dateSelection)
            .build()
        picker.addOnPositiveButtonClickListener { millis ->
            // MaterialDatePicker returns UTC-midnight ms — format in UTC so the
            // displayed day can't shift by a timezone offset.
            dateSelection = millis
            invoiceDate = DATE_FMT.format(Date(millis))
            inputDate.setText(invoiceDate)
        }
        picker.show(supportFragmentManager, "facture_date")
    }

    private fun save() {
        val pdfUri = pendingPdfUri ?: return
        val supplier = inputSupplier.text?.toString()?.trim().orEmpty()
        val date = invoiceDate
        val scanTimestamp = TIMESTAMP_FMT.format(Date())
        val pageCount = pendingPageCount

        btnSave.isEnabled = false
        io.execute {
            val record = FactureUploadQueue.enqueue(
                this, pdfUri, supplier, date, scanTimestamp, pageCount
            )
            val configured = SheetsClient(this).isConfigured()
            runOnUiThread {
                if (record == null) {
                    btnSave.isEnabled = true
                    Toast.makeText(this, R.string.facture_save_failed, Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                Toast.makeText(
                    this,
                    if (configured) R.string.facture_queued else R.string.facture_not_configured,
                    Toast.LENGTH_SHORT
                ).show()
                resetToIntro()
            }
        }
    }

    private fun resetToIntro() {
        pendingPdfUri = null
        pendingPageCount = 1
        invoiceDate = ""
        dateSelection = MaterialDatePicker.todayInUtcMilliseconds()
        thumbnail.setImageDrawable(null)
        inputSupplier.setText("")
        inputDate.setText("")
        panelReview.visibility = View.GONE
        panelIntro.visibility = View.VISIBLE
        btnSave.visibility = View.GONE
        btnScan.visibility = View.VISIBLE
        btnFactures.visibility = View.VISIBLE
        updatePendingStatus()
    }

    private fun updatePendingStatus() {
        if (io.isShutdown) return
        io.execute {
            val n = FactureUploadQueue.pendingCount(this)
            runOnUiThread { renderUploadState(running = false, pending = n) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }

    companion object {
        private const val MAX_PAGES = 15
        private val TIMESTAMP_FMT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        private val DATE_FMT = SimpleDateFormat("dd/MM/yyyy", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }
}
