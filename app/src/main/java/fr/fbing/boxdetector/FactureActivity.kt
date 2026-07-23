package fr.fbing.boxdetector

import android.app.Activity
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
 */
class FactureActivity : AppCompatActivity() {

    private lateinit var panelIntro: View
    private lateinit var panelReview: View
    private lateinit var pendingStatus: TextView
    private lateinit var thumbnail: ImageView
    private lateinit var pageCountView: TextView
    private lateinit var inputSupplier: TextInputEditText
    private lateinit var inputDate: TextInputEditText
    private lateinit var dateInputLayout: TextInputLayout
    private lateinit var btnScan: MaterialButton
    private lateinit var btnSave: MaterialButton

    private lateinit var io: ExecutorService

    private var pendingPdfUri: Uri? = null
    private var pendingPageCount: Int = 1

    /** Chosen invoice date as "dd/MM/yyyy" (empty until picked), plus the
     *  picker's current UTC-ms selection (defaults to today). */
    private var invoiceDate: String = ""
    private var dateSelection: Long = MaterialDatePicker.todayInUtcMilliseconds()

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
        thumbnail = findViewById(R.id.thumbnail)
        pageCountView = findViewById(R.id.page_count)
        inputSupplier = findViewById(R.id.input_supplier)
        inputDate = findViewById(R.id.input_date)
        dateInputLayout = findViewById(R.id.date_input_layout)
        btnScan = findViewById(R.id.btn_scan)
        btnSave = findViewById(R.id.btn_save)

        btnScan.setOnClickListener { launchScanner() }
        btnSave.setOnClickListener { save() }
        // The date field is not typeable — tapping it (or its calendar icon)
        // opens the picker.
        inputDate.setOnClickListener { showDatePicker() }
        dateInputLayout.setEndIconOnClickListener { showDatePicker() }

        io = Executors.newSingleThreadExecutor()

        // Retry any factures still queued from previous offline sessions.
        FactureUploadQueue.scheduleUpload(this)
    }

    override fun onResume() {
        super.onResume()
        updatePendingStatus()
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
        btnSave.visibility = View.VISIBLE
        btnSave.isEnabled = true
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
        updatePendingStatus()
    }

    private fun updatePendingStatus() {
        io.execute {
            val n = FactureUploadQueue.pendingCount(this)
            runOnUiThread {
                if (n > 0) {
                    pendingStatus.text = getString(R.string.facture_pending_status, n)
                    pendingStatus.visibility = View.VISIBLE
                } else {
                    pendingStatus.visibility = View.GONE
                }
            }
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
