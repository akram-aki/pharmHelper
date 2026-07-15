package fr.fbing.boxdetector

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    /** Latest frame whose best detection cleared the confidence threshold. */
    private class ScanCandidate(val frame: Bitmap, val box: RectF, val at: Long)

    private lateinit var previewView: PreviewView
    private lateinit var overlay: BoxOverlay
    private lateinit var scanButton: MaterialButton
    private lateinit var detector: BoxDetector
    private lateinit var textReader: TextReader
    private lateinit var parser: VignetteParser
    private lateinit var cameraExecutor: ExecutorService

    @Volatile private var candidate: ScanCandidate? = null

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startCamera()
        } else {
            Toast.makeText(this, R.string.camera_permission_denied, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.preview_view)
        overlay = findViewById(R.id.box_overlay)
        scanButton = findViewById(R.id.scan_button)
        scanButton.setOnClickListener { scan() }

        detector = BoxDetector(this)
        textReader = TextReader()
        parser = VignetteParser(this)
        cameraExecutor = Executors.newSingleThreadExecutor()

        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) startCamera() else requestPermission.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also { it.setAnalyzer(cameraExecutor, ::analyzeFrame) }

            provider.unbindAll()
            provider.bindToLifecycle(
                this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
            )
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeFrame(image: ImageProxy) {
        try {
            val upright = image.toUprightBitmap()
            val result = detector.detect(upright, 0)
            val best = result.detections.maxByOrNull { it.confidence }
            val eligible = best != null && best.confidence >= OCR_CONFIDENCE_THRESHOLD
            if (eligible) {
                candidate = ScanCandidate(upright, best!!.box, SystemClock.elapsedRealtime())
            }
            runOnUiThread {
                overlay.setDetections(result)
                if (!scanning) scanButton.isEnabled = eligible || candidateIsFresh()
            }
        } finally {
            image.close()
        }
    }

    private fun ImageProxy.toUprightBitmap(): Bitmap {
        val bitmap = toBitmap()
        val degrees = imageInfo.rotationDegrees
        if (degrees % 360 == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun candidateIsFresh(): Boolean {
        val c = candidate ?: return false
        return SystemClock.elapsedRealtime() - c.at <= CANDIDATE_TTL_MS
    }

    private var scanning = false

    /** Button click: OCR the last good detection, parse, open the result screen. */
    private fun scan() {
        val c = candidate
        if (c == null || !candidateIsFresh()) {
            Toast.makeText(this, R.string.toast_no_box, Toast.LENGTH_SHORT).show()
            return
        }
        setScanning(true)
        textReader.read(
            c.frame, c.box,
            onText = { text ->
                cameraExecutor.execute {
                    val info = parser.parse(text)
                    runOnUiThread {
                        setScanning(false)
                        showResult(info)
                    }
                }
            },
            onFail = { reason ->
                setScanning(false)
                val msg = when (reason) {
                    TextReader.FailReason.BUSY -> return@read
                    TextReader.FailReason.TOO_SMALL -> R.string.toast_too_small
                    TextReader.FailReason.BLURRY -> R.string.toast_blurry
                    TextReader.FailReason.NO_TEXT -> R.string.toast_no_text
                }
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun setScanning(active: Boolean) {
        scanning = active
        scanButton.isEnabled = !active
        scanButton.setText(if (active) R.string.scanning else R.string.scan_button)
    }

    private fun showResult(info: VignetteInfo) {
        startActivity(
            Intent(this, TextActivity::class.java)
                .putExtra(TextActivity.EXTRA_NAME, info.name)
                .putExtra(TextActivity.EXTRA_NAME_CONFIDENCE, info.nameConfidence)
                .putExtra(TextActivity.EXTRA_DOSAGE, info.dosage)
                .putExtra(TextActivity.EXTRA_PPA, info.ppa)
                .putExtra(TextActivity.EXTRA_FAB_DATE, info.fabDate)
                .putExtra(TextActivity.EXTRA_EXP_DATE, info.expDate)
                .putExtra(TextActivity.EXTRA_TEXT, info.rawText)
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        detector.close()
        textReader.close()
    }

    companion object {
        private const val OCR_CONFIDENCE_THRESHOLD = 0.70f
        private const val CANDIDATE_TTL_MS = 3000L
    }
}
