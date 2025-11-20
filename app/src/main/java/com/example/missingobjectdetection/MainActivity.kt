package com.example.missingobjectdetection


import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val cameraPermission = Manifest.permission.CAMERA
    private val permissionCode = 100
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var screwDetector: OptimizedScrewDetector
    private var isDetecting = false

    // UI components
    private lateinit var viewFinder: PreviewView
    private lateinit var btnCapture: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvResult: TextView
//    private lateinit var ivResult: ImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initializeViews()
        setupScrewDetector()
        setupClickListeners()
    }

    override fun onStart() {
        super.onStart()
        if (ContextCompat.checkSelfPermission(this, cameraPermission) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(cameraPermission), permissionCode)
        } else {
            startCamera()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permissionCode && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show()
        }
    }

    private fun setupScrewDetector() {
        try {
            screwDetector = OptimizedScrewDetector(this)
            cameraExecutor = Executors.newSingleThreadExecutor()
            Toast.makeText(this, "Detector initialized successfully", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize detector", e)
            Toast.makeText(this, "Failed to initialize detector: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun initializeViews() {
        viewFinder = findViewById(R.id.viewFinder)
        btnCapture = findViewById(R.id.btnCapture)
        progressBar = findViewById(R.id.progressBar)
        tvResult = findViewById(R.id.tvResult)
//        ivResult = findViewById(R.id.ivResult)

        // Set initial text
        tvResult.text = "Ready to detect screws\nPress Capture button"
    }

    private fun setupClickListeners() {
        btnCapture.setOnClickListener {
            if (!isDetecting) {
                captureAndAnalyze()
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            try {
                val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder()
                    .build()
                    .also {
                        it.setSurfaceProvider(viewFinder.surfaceProvider)
                    }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview)

            } catch (exc: Exception) {
                Log.e(TAG, "Camera setup failed", exc)
                Toast.makeText(this, "Camera setup failed: ${exc.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun captureAndAnalyze() {
        isDetecting = true
        progressBar.visibility = View.VISIBLE
        btnCapture.isEnabled = false
        tvResult.text = "Detecting screws..."

        // Get current frame from camera
        val bitmap = viewFinder.bitmap ?: run {
            showError("Failed to capture image")
            return
        }

        // Run detection in background
        cameraExecutor.execute {
            try {
                Log.d(TAG, "Starting detection...")
                val result = screwDetector.detectScrews(bitmap)
                Log.d(TAG, "Detection completed: ${result.detections.size} screws found")

                runOnUiThread {
                    navigateToResultScreen(bitmap,result)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Detection failed", e)
                runOnUiThread {
                    showError("Detection failed: ${e.message}")
                }
            } finally {
                runOnUiThread {
                    isDetecting = false
                    progressBar.visibility = View.GONE
                    btnCapture.isEnabled = true
                }
            }
        }
    }

//    private fun displayDetectionResult(result: DetectionResult, originalBitmap: Bitmap) {
//        // Create a copy of the bitmap to draw on
//        val resultBitmap = originalBitmap.copy(Bitmap.Config.ARGB_8888, true)
//        val canvas = Canvas(resultBitmap)
//
//        // Create paints for drawing
//        val boxPaint = Paint().apply {
//            style = Paint.Style.STROKE
//            strokeWidth = 6f
//            color = Color.GREEN
//            isAntiAlias = true
//        }
//
//        val textPaint = Paint().apply {
//            color = Color.WHITE
//            style = Paint.Style.FILL
//            textSize = 36f
//            isAntiAlias = true
//            strokeWidth = 2f
//            setShadowLayer(4f, 2f, 2f, Color.BLACK)
//        }
//
//        val detectedCount = result.detections.size
//
//        // Draw detected screws
//        result.detections.forEachIndexed { index, detection ->
//            // Draw bounding box
//            canvas.drawRect(detection.boundingBox, boxPaint)
//
//            // Draw label with confidence
//            val label = "Screw ${index + 1}: ${(detection.confidence * 100).toInt()}%"
//            val textY = maxOf(detection.boundingBox.top - 10, textPaint.textSize)
//            canvas.drawText(
//                label,
//                detection.boundingBox.left,
//                textY,
//                textPaint
//            )
//
//            Log.d(TAG, "Screw ${index + 1}: ${detection.boundingBox} - ${detection.confidence}")
//        }
//
//        // Update result text
//        tvResult.text = "Detected Screws: $detectedCount\n" +
//                "Confidence threshold: 25%"
//
//        // Display the analyzed image
////        ivResult.setImageBitmap(resultBitmap)
////        ivResult.visibility = View.VISIBLE
//
//        // Show toast with results
//        Toast.makeText(this, "Found $detectedCount screws", Toast.LENGTH_LONG).show()
//    }

    private fun navigateToResultScreen(originalBitmap: Bitmap, result: DetectionResult) {
        progressBar.visibility = View.GONE
        btnCapture.isEnabled = true

        val intent = Intent(this, ResultActivity::class.java).apply {
            // Resize bitmap to avoid TransactionTooLargeException
            val resizedBitmap = resizeBitmap(originalBitmap, 1024) // Max width 1024px

            // Convert bitmap to byte array with compression
            val stream = ByteArrayOutputStream()
            resizedBitmap.compress(Bitmap.CompressFormat.JPEG, 70, stream) // Use JPEG with 70% quality
            val imageBytes = stream.toByteArray()

            putExtra("image_bytes", imageBytes)
            putExtra("detection_count", result.detections.size)
            putExtra("image_width", resizedBitmap.width)
            putExtra("image_height", resizedBitmap.height)

            // Pass detection data
            putExtra("boxes", convertBoxesToFloatArray(result.detections))
            putExtra("scores", convertScoresToFloatArray(result.detections))
        }

        startActivity(intent)
    }

    private fun resizeBitmap(bitmap: Bitmap, maxWidth: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        if (width <= maxWidth) {
            return bitmap
        }

        val scale = maxWidth.toFloat() / width
        val newHeight = (height * scale).toInt()

        return Bitmap.createScaledBitmap(bitmap, maxWidth, newHeight, true)
    }

//    private fun navigateToResultScreen(originalBitmap: Bitmap, result: DetectionResult) {
//        progressBar.visibility = View.GONE
//        btnCapture.isEnabled = true
//
//        val intent = Intent(this, ResultActivity::class.java).apply {
//            // Convert bitmap to byte array for passing to next activity
//            val stream = ByteArrayOutputStream()
//            originalBitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
//            putExtra("image_bytes", stream.toByteArray())
//            putExtra("detection_count", result.detections.size)
//            putExtra("image_width", originalBitmap.width)
//            putExtra("image_height", originalBitmap.height)
//
//            // Pass detection data
//            putExtra("boxes", convertBoxesToFloatArray(result.detections))
//            putExtra("scores", convertScoresToFloatArray(result.detections))
//        }
//
//        startActivity(intent)
//    }
    private fun convertBoxesToFloatArray(detections: List<Detection>): FloatArray {
        val floatArray = FloatArray(detections.size * 4)
        detections.forEachIndexed { index, detection ->
            floatArray[index * 4] = detection.boundingBox.left
            floatArray[index * 4 + 1] = detection.boundingBox.top
            floatArray[index * 4 + 2] = detection.boundingBox.right
            floatArray[index * 4 + 3] = detection.boundingBox.bottom
        }
        return floatArray
    }
    private fun convertScoresToFloatArray(detections: List<Detection>): FloatArray {
        return FloatArray(detections.size) { detections[it].confidence }
    }


    private fun showError(message: String) {
        isDetecting = false
        progressBar.visibility = View.GONE
        btnCapture.isEnabled = true
        tvResult.text = "Error: $message"
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        screwDetector.close()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "ScrewDetector"
    }
}