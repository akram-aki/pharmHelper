package fr.fbing.boxdetector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import kotlin.math.max
import kotlin.math.min

class BoxDetector(context: Context) {

    private val interpreter: Interpreter
    private var gpuDelegate: GpuDelegate? = null

    private val inputSize: Int
    private val inputType: DataType
    private val outputShape: IntArray
    private val outputType: DataType
    private val outputScale: Float
    private val outputZeroPoint: Int
    private val isQuantized: Boolean
    private val isYoloV8: Boolean
    private val numDetections: Int
    private val rowSize: Int

    private val imageProcessor: ImageProcessor

    init {
        val model = FileUtil.loadMappedFile(context, MODEL_FILE)

        // Inspect dtype with a no-delegate interpreter first so we can pick the
        // right delegate for the model flavour (FP -> GPU, INT8 -> CPU+XNNPACK).
        val probe = Interpreter(model, Interpreter.Options())
        val probedInputType = probe.getInputTensor(0).dataType()
        probe.close()

        val quantized = probedInputType != DataType.FLOAT32
        val options = Interpreter.Options().apply { numThreads = 4 }

        gpuDelegate = if (!quantized) {
            try {
                GpuDelegate().also {
                    options.addDelegate(it)
                    Log.i(TAG, "GPU delegate enabled (FP model)")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "GPU delegate unavailable, using CPU", t)
                null
            }
        } else {
            Log.i(TAG, "Quantized model — skipping GPU delegate, using CPU+XNNPACK")
            null
        }

        interpreter = try {
            Interpreter(model, options)
        } catch (t: Throwable) {
            Log.w(TAG, "Interpreter init failed with delegate, retrying CPU-only", t)
            gpuDelegate?.close()
            gpuDelegate = null
            Interpreter(model, Interpreter.Options().apply { numThreads = 4 })
        }

        val inputTensor = interpreter.getInputTensor(0)
        val outputTensor = interpreter.getOutputTensor(0)
        val inputShape = inputTensor.shape()

        inputSize = inputShape[1]
        inputType = inputTensor.dataType()
        outputShape = outputTensor.shape()
        outputType = outputTensor.dataType()
        isQuantized = inputType != DataType.FLOAT32

        val outputQuant = outputTensor.quantizationParams()
        outputScale = outputQuant.scale
        outputZeroPoint = outputQuant.zeroPoint

        Log.i(TAG, "Input ${inputShape.toList()} $inputType  Output ${outputShape.toList()} $outputType")
        if (outputType != DataType.FLOAT32) {
            Log.i(TAG, "Output dequant: scale=$outputScale zeroPoint=$outputZeroPoint")
        }

        // YOLOv5: [1, N, 5+nc] channels-last.
        // YOLOv8/v11: [1, 4+nc, N] channels-first.
        isYoloV8 = outputShape[1] < outputShape[2]
        numDetections = if (isYoloV8) outputShape[2] else outputShape[1]
        rowSize = if (isYoloV8) outputShape[1] else outputShape[2]
        Log.i(TAG, "Format: ${if (isYoloV8) "YOLOv8/v11" else "YOLOv5"}  candidates=$numDetections  row=$rowSize")

        // FP32 inputs expect [0,1] floats. UINT8 inputs take raw pixel values
        // and the model's internal quant params handle the scaling.
        imageProcessor = ImageProcessor.Builder().apply {
            if (inputType == DataType.FLOAT32) add(NormalizeOp(0f, 255f))
        }.build()
    }

    fun detect(image: Bitmap, rotationDegrees: Int): DetectionResult {
        val upright = if (rotationDegrees % 360 == 0) {
            image
        } else {
            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            Bitmap.createBitmap(image, 0, 0, image.width, image.height, matrix, true)
        }
        val srcW = upright.width
        val srcH = upright.height

        val scale = min(inputSize.toFloat() / srcW, inputSize.toFloat() / srcH)
        val newW = (srcW * scale).toInt()
        val newH = (srcH * scale).toInt()
        val padX = (inputSize - newW) / 2
        val padY = (inputSize - newH) / 2

        val letterbox = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
        Canvas(letterbox).apply {
            drawColor(Color.GRAY)
            drawBitmap(
                Bitmap.createScaledBitmap(upright, newW, newH, true),
                padX.toFloat(),
                padY.toFloat(),
                null
            )
        }

        val tensorImage = TensorImage(inputType)
        tensorImage.load(letterbox)
        val processed = imageProcessor.process(tensorImage)

        val outputBuffer = TensorBuffer.createFixedSize(outputShape, outputType)

        val t0 = SystemClock.elapsedRealtime()
        interpreter.run(processed.buffer, outputBuffer.buffer.rewind())
        val inferenceMs = SystemClock.elapsedRealtime() - t0

        val flat: FloatArray = if (outputType == DataType.FLOAT32) {
            outputBuffer.floatArray
        } else {
            val rawInts = outputBuffer.intArray
            FloatArray(rawInts.size) { (rawInts[it] - outputZeroPoint) * outputScale }
        }

        val raw = ArrayList<Detection>()
        for (i in 0 until numDetections) {
            val cx: Float
            val cy: Float
            val w: Float
            val h: Float
            val conf: Float
            if (isYoloV8) {
                cx = flat[0 * numDetections + i]
                cy = flat[1 * numDetections + i]
                w  = flat[2 * numDetections + i]
                h  = flat[3 * numDetections + i]
                conf = flat[4 * numDetections + i]
            } else {
                val base = i * rowSize
                cx = flat[base]
                cy = flat[base + 1]
                w  = flat[base + 2]
                h  = flat[base + 3]
                // v5 has obj_conf at 4, class_conf at 5. 1-class exports often
                // collapse them; multiply when both present, else use index 4.
                conf = if (rowSize >= 6) flat[base + 4] * flat[base + 5] else flat[base + 4]
            }
            if (conf < CONFIDENCE_THRESHOLD) continue

            val x1m = cx - w / 2f
            val y1m = cy - h / 2f
            val x2m = cx + w / 2f
            val y2m = cy + h / 2f

            // Ultralytics exports usually give pixel coords [0..inputSize].
            // Some FP32 exports use normalized [0..1]; detect by magnitude.
            val unit = if (max(x2m, y2m) <= 1.5f) inputSize.toFloat() else 1f

            val x1 = ((x1m * unit) - padX) / scale
            val y1 = ((y1m * unit) - padY) / scale
            val x2 = ((x2m * unit) - padX) / scale
            val y2 = ((y2m * unit) - padY) / scale

            raw.add(
                Detection(
                    box = RectF(
                        x1.coerceIn(0f, srcW.toFloat()),
                        y1.coerceIn(0f, srcH.toFloat()),
                        x2.coerceIn(0f, srcW.toFloat()),
                        y2.coerceIn(0f, srcH.toFloat())
                    ),
                    confidence = conf
                )
            )
        }

        val kept = nms(raw, IOU_THRESHOLD)
        Log.d(TAG, "inf=${inferenceMs}ms raw=${raw.size} kept=${kept.size}")

        return DetectionResult(kept, srcW, srcH)
    }

    private fun nms(detections: List<Detection>, iouThreshold: Float): List<Detection> {
        val sorted = detections.sortedByDescending { it.confidence }.toMutableList()
        val keep = ArrayList<Detection>()
        while (sorted.isNotEmpty()) {
            val top = sorted.removeAt(0)
            keep.add(top)
            sorted.removeAll { iou(top.box, it.box) > iouThreshold }
        }
        return keep
    }

    private fun iou(a: RectF, b: RectF): Float {
        val x1 = max(a.left, b.left)
        val y1 = max(a.top, b.top)
        val x2 = min(a.right, b.right)
        val y2 = min(a.bottom, b.bottom)
        val inter = max(0f, x2 - x1) * max(0f, y2 - y1)
        val areaA = (a.right - a.left) * (a.bottom - a.top)
        val areaB = (b.right - b.left) * (b.bottom - b.top)
        val union = areaA + areaB - inter
        return if (union <= 0f) 0f else inter / union
    }

    fun close() {
        interpreter.close()
        gpuDelegate?.close()
        gpuDelegate = null
    }

    companion object {
        private const val TAG = "BoxDetector"
        private const val MODEL_FILE = "box_detector.tflite"
        private const val CONFIDENCE_THRESHOLD = 0.25f
        private const val IOU_THRESHOLD = 0.45f
    }
}
