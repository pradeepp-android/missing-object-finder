package com.example.missingobjectdetection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min

class OptimizedScrewDetector(private val context: Context) {
    private var interpreter: Interpreter? = null
    private val inputSize = 640

    // Configuration
    private val confidenceThreshold = 0.25f
    private val iouThreshold = 0.45f

    // Buffers for better performance
    private lateinit var inputBuffer: ByteBuffer
    private lateinit var pixelsBuffer: IntArray
    private lateinit var outputBuffer: Array<Array<FloatArray>> // Correct shape: [1, 37, 8400]

    init {
        initialize()
    }

    private fun initialize() {
        try {
            val model = loadModelFile("best_float32.tflite")
            val options = Interpreter.Options()
            options.setNumThreads(4)
            interpreter = Interpreter(model, options)

            // Get input dimensions
            val inputShape = interpreter?.getInputTensor(0)?.shape()
            Log.d("OptimizedScrewDetector", "Input shape: ${inputShape?.contentToString()}")

            // Get output dimensions
            val outputShape = interpreter?.getOutputTensor(0)?.shape()
            Log.d("OptimizedScrewDetector", "Output shape: ${outputShape?.contentToString()}")

            // Allocate buffers
            val bufferSize = 1 * inputSize * inputSize * 3 * 4 // BHWC, float32
            inputBuffer = ByteBuffer.allocateDirect(bufferSize)
            inputBuffer.order(ByteOrder.nativeOrder())

            pixelsBuffer = IntArray(inputSize * inputSize)

            // CORRECTED: YOLOv8 output: [1, 37, 8400]
            outputBuffer = Array(1) { Array(37) { FloatArray(8400) } }

            Log.d("OptimizedScrewDetector", "✅ Detector initialized successfully")
            Log.d("OptimizedScrewDetector", "Output buffer shape: [1, 37, 8400]")

        } catch (e: Exception) {
            Log.e("OptimizedScrewDetector", "❌ Failed to initialize detector", e)
            throw RuntimeException("Failed to initialize detector", e)
        }
    }

    private fun loadModelFile(modelName: String): ByteBuffer {
        try {
            val assetFileDescriptor = context.assets.openFd(modelName)
            val inputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
            val fileChannel = inputStream.channel
            val startOffset = assetFileDescriptor.startOffset
            val declaredLength = assetFileDescriptor.declaredLength
            return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
        } catch (e: Exception) {
            throw RuntimeException("Error loading model $modelName", e)
        }
    }

    fun detectScrews(bitmap: Bitmap): DetectionResult {
        if (interpreter == null) {
            throw IllegalStateException("Detector not initialized")
        }

        Log.d("OptimizedScrewDetector", "Detecting screws in ${bitmap.width}x${bitmap.height} image")

        val input = preprocess(bitmap)

        // Run inference
        interpreter!!.run(input, outputBuffer)

        return postprocess(outputBuffer[0], bitmap.width, bitmap.height)
    }

    private fun preprocess(bitmap: Bitmap): ByteBuffer {
        // Resize bitmap to match model input
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)

        // Clear and reuse buffer
        inputBuffer.rewind()

        // Extract pixels
        resizedBitmap.getPixels(pixelsBuffer, 0, inputSize, 0, 0, inputSize, inputSize)

        // Convert to normalized float values
        var pixel = 0
        for (y in 0 until inputSize) {
            for (x in 0 until inputSize) {
                val value = pixelsBuffer[pixel++]

                val r = ((value shr 16) and 0xFF) / 255.0f
                val g = ((value shr 8) and 0xFF) / 255.0f
                val b = (value and 0xFF) / 255.0f

                inputBuffer.putFloat(r)
                inputBuffer.putFloat(g)
                inputBuffer.putFloat(b)
            }
        }

        // Recycle if different from original
        if (resizedBitmap != bitmap) {
            resizedBitmap.recycle()
        }

        return inputBuffer
    }

    private fun postprocess(
        outputData: Array<FloatArray>, // Shape: [37, 8400]
        srcWidth: Int,
        srcHeight: Int
    ): DetectionResult {
        val boxes = mutableListOf<RectF>()
        val scores = mutableListOf<Float>()

        val scaleX = srcWidth / inputSize.toFloat()
        val scaleY = srcHeight / inputSize.toFloat()

        Log.d("OptimizedScrewDetector", "Processing output data with shape [37, 8400]")

        // Process each detection (8400 detections in YOLOv8)
        // CORRECTED: Each detection has 37 values in the first dimension
        for (i in 0 until 8400) {
            // Get the confidence score for this detection
            val confidence = outputData[4][i] // This part is correct

            if (confidence < confidenceThreshold) continue

            // Get bounding box coordinates - THESE ARE NORMALIZED COORDINATES [0,1]
            val cx = outputData[0][i] // center x (normalized)
            val cy = outputData[1][i] // center y (normalized)
            val w = outputData[2][i]  // width (normalized)
            val h = outputData[3][i]  // height (normalized)

            // Convert normalized coordinates to pixel coordinates
            // First scale to inputSize (model input), then to original image
            val x1 = (cx - w / 2) * inputSize * scaleX
            val y1 = (cy - h / 2) * inputSize * scaleY
            val x2 = (cx + w / 2) * inputSize * scaleX
            val y2 = (cy + h / 2) * inputSize * scaleY

            // Clamp to image boundaries
            val clampedX1 = max(0f, min(x1, srcWidth.toFloat()))
            val clampedY1 = max(0f, min(y1, srcHeight.toFloat()))
            val clampedX2 = max(0f, min(x2, srcWidth.toFloat()))
            val clampedY2 = max(0f, min(y2, srcHeight.toFloat()))

            boxes.add(RectF(clampedX1, clampedY1, clampedX2, clampedY2))
            scores.add(confidence)

            Log.v("OptimizedScrewDetector", "Detection $i: conf=$confidence, raw=[$cx, $cy, $w, $h], scaled=[$clampedX1, $clampedY1, $clampedX2, $clampedY2]")
        }

        Log.d("OptimizedScrewDetector", "Raw detections before NMS: ${boxes.size}")

        // Apply NMS
        val keepIndices = nms(boxes, scores)

        val finalDetections = mutableListOf<Detection>()
        for (index in keepIndices) {
            // Extract mask coefficients for this detection
            val maskCoefficients = FloatArray(32)
            for (j in 0 until 32) {
                maskCoefficients[j] = outputData[5 + j][index]
            }

            finalDetections.add(Detection(boxes[index], scores[index], maskCoefficients))
        }

        Log.d("OptimizedScrewDetector", "Final detections after NMS: ${finalDetections.size}")

        return DetectionResult(finalDetections, srcWidth, srcHeight)
    }
//    private fun postprocess(
//        outputData: Array<FloatArray>, // Shape: [37, 8400]
//        srcWidth: Int,
//        srcHeight: Int
//    ): DetectionResult {
//        val boxes = mutableListOf<RectF>()
//        val scores = mutableListOf<Float>()
//
//        val scaleX = srcWidth / inputSize.toFloat()
//        val scaleY = srcHeight / inputSize.toFloat()
//
//        Log.d("OptimizedScrewDetector", "Processing output data with shape [37, 8400]")
//
//        // Process each detection (8400 detections in YOLOv8)
//        // YOLOv8 output format: for each of 8400 detections, we have 37 values:
//        // [0]: center_x, [1]: center_y, [2]: width, [3]: height, [4]: confidence,
//        // [5..36]: mask coefficients (32 values)
//        for (i in 0 until 8400) {
//            val confidence = outputData[4][i] // Confidence score at index 4
//
//            if (confidence < confidenceThreshold) continue
//
//            // Get bounding box coordinates
//            val cx = outputData[0][i] // center x
//            val cy = outputData[1][i] // center y
//            val w = outputData[2][i]  // width
//            val h = outputData[3][i]  // height
//
//            // Convert normalized coordinates to pixel coordinates
//            val x1 = (cx - w / 2) * scaleX
//            val y1 = (cy - h / 2) * scaleY
//            val x2 = (cx + w / 2) * scaleX
//            val y2 = (cy + h / 2) * scaleY
//
//            // Clamp to image boundaries
//            val clampedX1 = max(0f, min(x1, srcWidth.toFloat()))
//            val clampedY1 = max(0f, min(y1, srcHeight.toFloat()))
//            val clampedX2 = max(0f, min(x2, srcWidth.toFloat()))
//            val clampedY2 = max(0f, min(y2, srcHeight.toFloat()))
//
//            boxes.add(RectF(clampedX1, clampedY1, clampedX2, clampedY2))
//            scores.add(confidence)
//
//            Log.v("OptimizedScrewDetector", "Detection $i: conf=$confidence at [$clampedX1, $clampedY1, $clampedX2, $clampedY2]")
//        }
//
//        Log.d("OptimizedScrewDetector", "Raw detections before NMS: ${boxes.size}")
//
//        // Apply NMS
//        val keepIndices = nms(boxes, scores)
//
//        val finalDetections = mutableListOf<Detection>()
//        for (index in keepIndices) {
//            // Extract mask coefficients for this detection
//            val maskCoefficients = FloatArray(32)
//            for (j in 0 until 32) {
//                maskCoefficients[j] = outputData[5 + j][index]
//            }
//
//            finalDetections.add(Detection(boxes[index], scores[index], maskCoefficients))
//        }
//
//        Log.d("OptimizedScrewDetector", "Final detections after NMS: ${finalDetections.size}")
//
//        return DetectionResult(finalDetections, srcWidth, srcHeight)
//    }

    private fun nms(boxes: List<RectF>, scores: List<Float>): List<Int> {
        val indices = scores.indices.filter { scores[it] >= confidenceThreshold }.toMutableList()

        // Sort by confidence (descending)
        indices.sortByDescending { scores[it] }

        val keep = mutableListOf<Int>()

        while (indices.isNotEmpty()) {
            val current = indices.removeAt(0)
            keep.add(current)

            // Remove overlapping boxes
            val iterator = indices.iterator()
            while (iterator.hasNext()) {
                val next = iterator.next()
                if (calculateIoU(boxes[current], boxes[next]) > iouThreshold) {
                    iterator.remove()
                }
            }
        }

        return keep
    }

    private fun calculateIoU(box1: RectF, box2: RectF): Float {
        val x1 = max(box1.left, box2.left)
        val y1 = max(box1.top, box2.top)
        val x2 = min(box1.right, box2.right)
        val y2 = min(box1.bottom, box2.bottom)

        if (x2 <= x1 || y2 <= y1) return 0.0f

        val intersection = (x2 - x1) * (y2 - y1)
        val area1 = (box1.right - box1.left) * (box1.bottom - box1.top)
        val area2 = (box2.right - box2.left) * (box2.bottom - box2.top)
        val union = area1 + area2 - intersection

        return intersection / union
    }

    fun close() {
        interpreter?.close()
        Log.d("OptimizedScrewDetector", "Detector closed")
    }
}
//class OptimizedScrewDetector(private val context: Context) {
//    private var interpreter: Interpreter? = null
//    private val inputSize = 640
//
//    // Configuration
//    private val confidenceThreshold = 0.25f
//    private val iouThreshold = 0.45f
//
//    // Buffers for better performance
//    private lateinit var inputBuffer: ByteBuffer
//    private lateinit var pixelsBuffer: IntArray
//    private lateinit var outputBuffer: Array<FloatArray>
//
//    init {
//        initialize()
//    }
//
//    private fun initialize() {
//        try {
//            val model = loadModelFile("best_float32.tflite")
//            val options = Interpreter.Options()
//            options.setNumThreads(4)
//            interpreter = Interpreter(model, options)
//
//            // Get input dimensions
//            val inputShape = interpreter?.getInputTensor(0)?.shape()
//            Log.d("OptimizedScrewDetector", "Input shape: ${inputShape?.contentToString()}")
//
//            // Get output dimensions
//            val outputShape = interpreter?.getOutputTensor(0)?.shape()
//            Log.d("OptimizedScrewDetector", "Output shape: ${outputShape?.contentToString()}")
//
//            // Allocate buffers
//            val bufferSize = 1 * inputSize * inputSize * 3 * 4 // BHWC, float32
//            inputBuffer = ByteBuffer.allocateDirect(bufferSize)
//            inputBuffer.order(ByteOrder.nativeOrder())
//
//            pixelsBuffer = IntArray(inputSize * inputSize)
//
//            // YOLOv8 output: [1, 37, 8400] or [1, 8400, 37]
//            outputBuffer = Array(1) { FloatArray(8400 * 37) }
//
//            Log.d("OptimizedScrewDetector", "✅ Detector initialized successfully")
//
//        } catch (e: Exception) {
//            Log.e("OptimizedScrewDetector", "❌ Failed to initialize detector", e)
//            throw RuntimeException("Failed to initialize detector", e)
//        }
//    }
//
//    private fun loadModelFile(modelName: String): ByteBuffer {
//        try {
//            val assetFileDescriptor = context.assets.openFd(modelName)
//            val inputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
//            val fileChannel = inputStream.channel
//            val startOffset = assetFileDescriptor.startOffset
//            val declaredLength = assetFileDescriptor.declaredLength
//            return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
//        } catch (e: Exception) {
//            throw RuntimeException("Error loading model $modelName", e)
//        }
//    }
//
//    fun detectScrews(bitmap: Bitmap): DetectionResult {
//        if (interpreter == null) {
//            throw IllegalStateException("Detector not initialized")
//        }
//
//        Log.d("OptimizedScrewDetector", "Detecting screws in ${bitmap.width}x${bitmap.height} image")
//
//        val input = preprocess(bitmap)
//
//        // Run inference
//        interpreter!!.run(input, outputBuffer)
//
//        return postprocess(outputBuffer[0], bitmap.width, bitmap.height)
//    }
//
//    private fun preprocess(bitmap: Bitmap): ByteBuffer {
//        // Resize bitmap to match model input
//        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
//
//        // Clear and reuse buffer
//        inputBuffer.rewind()
//
//        // Extract pixels
//        resizedBitmap.getPixels(pixelsBuffer, 0, inputSize, 0, 0, inputSize, inputSize)
//
//        // Convert to normalized float values
//        var pixel = 0
//        for (y in 0 until inputSize) {
//            for (x in 0 until inputSize) {
//                val value = pixelsBuffer[pixel++]
//
//                val r = ((value shr 16) and 0xFF) / 255.0f
//                val g = ((value shr 8) and 0xFF) / 255.0f
//                val b = (value and 0xFF) / 255.0f
//
//                inputBuffer.putFloat(r)
//                inputBuffer.putFloat(g)
//                inputBuffer.putFloat(b)
//            }
//        }
//
//        // Recycle if different from original
//        if (resizedBitmap != bitmap) {
//            resizedBitmap.recycle()
//        }
//
//        return inputBuffer
//    }
//
//    private fun postprocess(
//        outputData: FloatArray,
//        srcWidth: Int,
//        srcHeight: Int
//    ): DetectionResult {
//        val boxes = mutableListOf<RectF>()
//        val scores = mutableListOf<Float>()
//
//        val scaleX = srcWidth / inputSize.toFloat()
//        val scaleY = srcHeight / inputSize.toFloat()
//
//        Log.d("OptimizedScrewDetector", "Processing ${outputData.size} output values")
//
//        // Process each detection (8400 detections in YOLOv8)
//        for (i in 0 until 8400) {
//            val baseIndex = i * 37
//
//            // YOLOv8 format: [x, y, w, h, conf, ...mask_coeffs, ...class_scores]
//            val cx = outputData[baseIndex]
//            val cy = outputData[baseIndex + 1]
//            val w = outputData[baseIndex + 2]
//            val h = outputData[baseIndex + 3]
//            val conf = outputData[baseIndex + 4]
//
//            if (conf < confidenceThreshold) continue
//
//            // Convert normalized coordinates to pixel coordinates
//            val x1 = (cx - w / 2) * scaleX
//            val y1 = (cy - h / 2) * scaleY
//            val x2 = (cx + w / 2) * scaleX
//            val y2 = (cy + h / 2) * scaleY
//
//            // Clamp to image boundaries
//            val clampedX1 = max(0f, min(x1, srcWidth.toFloat()))
//            val clampedY1 = max(0f, min(y1, srcHeight.toFloat()))
//            val clampedX2 = max(0f, min(x2, srcWidth.toFloat()))
//            val clampedY2 = max(0f, min(y2, srcHeight.toFloat()))
//
//            boxes.add(RectF(clampedX1, clampedY1, clampedX2, clampedY2))
//            scores.add(conf)
//
//            Log.v("OptimizedScrewDetector", "Box $i: conf=$conf at [$clampedX1, $clampedY1, $clampedX2, $clampedY2]")
//        }
//
//        Log.d("OptimizedScrewDetector", "Raw detections: ${boxes.size}")
//
//        // Apply NMS
//        val keepIndices = nms(boxes, scores)
//
//        val finalDetections = mutableListOf<Detection>()
//        for (index in keepIndices) {
//            finalDetections.add(Detection(boxes[index], scores[index], FloatArray(32))) // Empty mask coefficients
//        }
//
//        Log.d("OptimizedScrewDetector", "Final detections after NMS: ${finalDetections.size}")
//
//        return DetectionResult(finalDetections, srcWidth, srcHeight)
//    }
//
//    private fun nms(boxes: List<RectF>, scores: List<Float>): List<Int> {
//        val indices = scores.indices.filter { scores[it] >= confidenceThreshold }.toMutableList()
//
//        // Sort by confidence (descending)
//        indices.sortByDescending { scores[it] }
//
//        val keep = mutableListOf<Int>()
//
//        while (indices.isNotEmpty()) {
//            val current = indices.removeAt(0)
//            keep.add(current)
//
//            // Remove overlapping boxes
//            val iterator = indices.iterator()
//            while (iterator.hasNext()) {
//                val next = iterator.next()
//                if (calculateIoU(boxes[current], boxes[next]) > iouThreshold) {
//                    iterator.remove()
//                }
//            }
//        }
//
//        return keep
//    }
//
//    private fun calculateIoU(box1: RectF, box2: RectF): Float {
//        val x1 = max(box1.left, box2.left)
//        val y1 = max(box1.top, box2.top)
//        val x2 = min(box1.right, box2.right)
//        val y2 = min(box1.bottom, box2.bottom)
//
//        if (x2 <= x1 || y2 <= y1) return 0.0f
//
//        val intersection = (x2 - x1) * (y2 - y1)
//        val area1 = (box1.right - box1.left) * (box1.bottom - box1.top)
//        val area2 = (box2.right - box2.left) * (box2.bottom - box2.top)
//        val union = area1 + area2 - intersection
//
//        return intersection / union
//    }
//
//    fun close() {
//        interpreter?.close()
//        Log.d("OptimizedScrewDetector", "Detector closed")
//    }
//}