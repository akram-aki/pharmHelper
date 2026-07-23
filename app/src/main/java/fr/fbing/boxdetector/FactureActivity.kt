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
import com.google.android.material.textfield.TextInputEditText
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * "Scan factures": launches the ML Kit Document Scanner (CamScanner-style edge
 * detection + perspective correction + enhancement + multi-page → PDF), reads
 * the first page with [FactureOcr] to pre-fill supplier/date, and enqueues the
 * confirmed PDF for durable upload via [FactureUploadQueue].
 */
class FactureActivity : AppCompatActivity() {

    private lateinit var panelIntro: View
    private lateinit var panelReview: View
    private lateinit var pendingStatus: TextView
    private lateinit var thumbnail: ImageView
    private lateinit var pageCountView: TextView
    private lateinit var readingStatus: TextView
    private lateinit var inputSupplier: TextInputEditText
    private lateinit var inputDate: TextInputEditText
    private lateinit var btnScan: MaterialButton
    private lateinit var btnSave: MaterialButton

    private lateinit var ocr: FactureOcr
    private lateinit var io: ExecutorService

    private var pendingPdfUri: Uri? = null
    private var pendingPageCount: Int = 1

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
        readingStatus = findViewById(R.id.reading_status)
        inputSupplier = findViewById(R.id.input_supplier)
        inputDate = findViewById(R.id.input_date)
        btnScan = findViewById(R.id.btn_scan)
        btnSave = findViewById(R.id.btn_save)

        btnScan.setOnClickListener { launchScanner() }
        btnSave.setOnClickListener { save() }

        ocr = FactureOcr()
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

        val firstPage = result.pages?.firstOrNull()?.imageUri
        if (firstPage != null) {
            thumbnail.setImageURI(firstPage)
            readingStatus.visibility = View.VISIBLE
            ocr.suggest(this, firstPage) { s ->
                readingStatus.visibility = View.GONE
                // Only pre-fill fields the user hasn't already typed into.
                if (inputSupplier.text.isNullOrBlank()) s.supplier?.let { inputSupplier.setText(it) }
                if (inputDate.text.isNullOrBlank()) s.invoiceDate?.let { inputDate.setText(it) }
            }
        } else {
            thumbnail.setImageDrawable(null)
            readingStatus.visibility = View.GONE
        }
    }

    private fun showReviewState(pageCount: Int) {
        inputSupplier.setText("")
        inputDate.setText("")
        pageCountView.text = getString(R.string.facture_pages, pageCount)
        panelIntro.visibility = View.GONE
        panelReview.visibility = View.VISIBLE
        btnScan.visibility = View.GONE
        btnSave.visibility = View.VISIBLE
        btnSave.isEnabled = true
    }

    private fun save() {
        val pdfUri = pendingPdfUri ?: return
        val supplier = inputSupplier.text?.toString()?.trim().orEmpty()
        val invoiceDate = inputDate.text?.toString()?.trim().orEmpty()
        val scanTimestamp = TIMESTAMP_FMT.format(Date())
        val pageCount = pendingPageCount

        btnSave.isEnabled = false
        io.execute {
            val record = FactureUploadQueue.enqueue(
                this, pdfUri, supplier, invoiceDate, scanTimestamp, pageCount
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
        ocr.close()
        io.shutdown()
    }

    companion object {
        private const val MAX_PAGES = 15
        private val TIMESTAMP_FMT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
    }
}
