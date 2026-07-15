package fr.fbing.boxdetector

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.RectF
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    /** Latest analysis frame whose best detection cleared the confidence threshold. */
    private class ScanCandidate(val frame: Bitmap, val box: RectF, val at: Long)

    private lateinit var previewView: PreviewView
    private lateinit var overlay: BoxOverlay
    private lateinit var scanButton: MaterialButton
    private lateinit var detector: BoxDetector
    private lateinit var textReader: TextReader
    private lateinit var parser: VignetteParser
    private lateinit var cameraExecutor: ExecutorService

    private var imageCapture: ImageCapture? = null
    @Volatile private var candidate: ScanCandidate? = null
    private var scanning = false

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

            // 4:3 like the sensor and the still — 16:9 would be a FOV crop and
            // break box scaling between the analysis frame and the still.
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setResolutionSelector(resolutionSelector(ANALYSIS_SIZE))
                .build()
                .also { it.setAnalyzer(cameraExecutor, ::analyzeFrame) }

            val capture = ImageCapture.Builder()
                .setCaptureMode(CAPTURE_MODE)
                .setResolutionSelector(resolutionSelector(STILL_SIZE))
                .build()

            provider.unbindAll()
            try {
                provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis, capture
                )
                imageCapture = capture
            } catch (e: Exception) {
                Log.w(TAG, "ImageCapture unsupported, scanning from analysis frames", e)
                imageCapture = null
                provider.unbindAll()
                provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
                )
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun resolutionSelector(bound: Size): ResolutionSelector =
        ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(bound, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
            )
            .build()

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

    /**
     * Button click: capture a high-res still, locate the vignette in it, OCR,
     * parse, open the result screen. Falls back to the (lower-res) analysis
     * frame if capture is unavailable or fails.
     */
    private fun scan() {
        val c = candidate
        if (c == null || !candidateIsFresh()) {
            Toast.makeText(this, R.string.toast_no_box, Toast.LENGTH_SHORT).show()
            return
        }
        setScanning(true)

        val capture = imageCapture
        if (capture == null) {
            runOcr(c.frame, c.box)
            return
        }
        capture.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val still = image.decodeUpright(MAX_STILL_SIDE)
                    val box = locateBoxInStill(still, c)
                    runOcr(still, box)
                } catch (t: Throwable) {
                    Log.w(TAG, "still processing failed, falling back to analysis frame", t)
                    runOcr(c.frame, c.box)
                } finally {
                    image.close()
                }
            }

            override fun onError(e: ImageCaptureException) {
                Log.w(TAG, "capture failed, falling back to analysis frame", e)
                runOcr(c.frame, c.box)
            }
        })
    }

    /** Decodes a JPEG ImageProxy, downsampled to [maxSide] and rotated upright. */
    private fun ImageProxy.decodeUpright(maxSide: Int): Bitmap {
        val buffer = planes[0].buffer
        val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        val longest = max(bounds.outWidth, bounds.outHeight)
        while (longest / sample > maxSide) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        Log.d(TAG, "still ${bounds.outWidth}x${bounds.outHeight} sample=$sample -> ${decoded.width}x${decoded.height}")

        // decodeByteArray ignores EXIF; CameraX reports the needed rotation.
        val degrees = imageInfo.rotationDegrees
        if (degrees % 360 == 0) return decoded
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (rotated !== decoded) decoded.recycle()
        return rotated
    }

    /** Re-detects the vignette in the still; falls back to scaling the analysis box. */
    private fun locateBoxInStill(still: Bitmap, c: ScanCandidate): RectF {
        val best = detector.detect(still, 0).detections.maxByOrNull { it.confidence }
        if (best != null && best.confidence >= STILL_BOX_THRESHOLD) return best.box

        Log.d(TAG, "still re-detect below threshold, scaling analysis box")
        val sx = still.width.toFloat() / c.frame.width
        val sy = still.height.toFloat() / c.frame.height
        return RectF(c.box.left * sx, c.box.top * sy, c.box.right * sx, c.box.bottom * sy)
            .apply { inset(-width() * 0.05f, -height() * 0.05f) }
    }

    /** Shared OCR + parse + show pipeline; callable from any thread. */
    private fun runOcr(source: Bitmap, box: RectF) {
        textReader.read(
            source, box,
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
                runOnUiThread {
                    setScanning(false)
                    val msg = when (reason) {
                        TextReader.FailReason.BUSY -> null
                        TextReader.FailReason.TOO_SMALL -> R.string.toast_too_small
                        TextReader.FailReason.BLURRY -> R.string.toast_blurry
                        TextReader.FailReason.NO_TEXT -> R.string.toast_no_text
                    }
                    msg?.let { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
                }
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
                .putStringArrayListExtra(
                    TextActivity.EXTRA_NAME_OPTIONS,
                    ArrayList(info.nameCandidates.map { it.name })
                )
                .putExtra(
                    TextActivity.EXTRA_NAME_OPTION_CONFS,
                    info.nameCandidates.map { it.confidence }.toIntArray()
                )
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
        private const val TAG = "MainActivity"
        private const val OCR_CONFIDENCE_THRESHOLD = 0.70f
        private const val STILL_BOX_THRESHOLD = 0.50f
        private const val CANDIDATE_TTL_MS = 3000L
        private const val MAX_STILL_SIDE = 2304
        private const val CAPTURE_MODE = ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
        // Landscape (sensor) orientation, both 4:3.
        private val ANALYSIS_SIZE = Size(1440, 1080)
        private val STILL_SIZE = Size(2048, 1536)
    }
}
