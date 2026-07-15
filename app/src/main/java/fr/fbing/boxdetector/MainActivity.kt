package fr.fbing.boxdetector

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.view.View
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlay: BoxOverlay
    private lateinit var viewTextButton: MaterialButton
    private lateinit var detector: BoxDetector
    private lateinit var textReader: TextReader
    private lateinit var parser: VignetteParser
    private lateinit var cameraExecutor: ExecutorService

    // Merged across OCR reads: best-confidence name, latest non-null fields.
    @Volatile private var currentInfo = VignetteInfo(null, 0, null, null, null, "")

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
        viewTextButton = findViewById(R.id.view_text_button)
        viewTextButton.setOnClickListener {
            val info = currentInfo
            startActivity(
                Intent(this, TextActivity::class.java)
                    .putExtra(TextActivity.EXTRA_NAME, info.name)
                    .putExtra(TextActivity.EXTRA_NAME_CONFIDENCE, info.nameConfidence)
                    .putExtra(TextActivity.EXTRA_PPA, info.ppa)
                    .putExtra(TextActivity.EXTRA_FAB_DATE, info.fabDate)
                    .putExtra(TextActivity.EXTRA_EXP_DATE, info.expDate)
                    .putExtra(TextActivity.EXTRA_TEXT, info.rawText)
            )
        }

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
            runOnUiThread { overlay.setDetections(result) }
            maybeRunOcr(upright, result)
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

    private fun maybeRunOcr(upright: Bitmap, result: DetectionResult) {
        val best = result.detections.maxByOrNull { it.confidence } ?: return
        if (best.confidence < OCR_CONFIDENCE_THRESHOLD) return
        textReader.maybeRead(upright, best.box) { text -> onTextExtracted(text) }
    }

    /** Called on the main thread with accepted OCR text; parsing runs off it. */
    private fun onTextExtracted(text: String) {
        cameraExecutor.execute {
            val parsed = parser.parse(text)
            val merged = merge(currentInfo, parsed)
            currentInfo = merged
            runOnUiThread { viewTextButton.visibility = View.VISIBLE }
        }
    }

    /** Keep the highest-confidence name and the latest non-null other fields. */
    private fun merge(old: VignetteInfo, new: VignetteInfo): VignetteInfo {
        val keepOldName = old.name != null && old.nameConfidence >= new.nameConfidence
        return VignetteInfo(
            name = if (keepOldName) old.name else new.name ?: old.name,
            nameConfidence = if (keepOldName) old.nameConfidence
                else max(new.nameConfidence, old.nameConfidence),
            ppa = new.ppa ?: old.ppa,
            fabDate = new.fabDate ?: old.fabDate,
            expDate = new.expDate ?: old.expDate,
            rawText = new.rawText
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
    }
}
