package com.example.missingobjectdetection

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class ResultActivity : AppCompatActivity() {
    private lateinit var ivResult: ImageView
    private lateinit var tvDetectionCount: TextView
    private lateinit var tvDetectionDetails: TextView
    private lateinit var btnBack: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_result)

        initViews()
        setupClickListeners()
        displayResults()
    }

    private fun initViews() {
        ivResult = findViewById(R.id.ivResult)
        tvDetectionCount = findViewById(R.id.tvDetectionCount)
        tvDetectionDetails = findViewById(R.id.tvDetectionDetails)
        btnBack = findViewById(R.id.btnBack)
    }

    private fun setupClickListeners() {
        btnBack.setOnClickListener {
            finish() // Go back to camera screen
        }
    }
    private fun displayResults() {
        try {
            // Get image from intent
            val imageBytes = intent.getByteArrayExtra("image_bytes")
            val detectionCount = intent.getIntExtra("detection_count", 0)
            val boxes = intent.getFloatArrayExtra("boxes") ?: floatArrayOf()
            val scores = intent.getFloatArrayExtra("scores") ?: floatArrayOf()

            if (imageBytes == null || imageBytes.isEmpty()) {
                showError("No image data received")
                return
            }

            val originalBitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            if (originalBitmap == null) {
                showError("Failed to decode image")
                return
            }

            val resultBitmap = drawDetectionsOnBitmap(originalBitmap, boxes, scores)

            ivResult.setImageBitmap(resultBitmap)
            tvDetectionCount.text = "Detected: $detectionCount screws"

            // Show detection details
            val details = buildDetectionDetails(scores)
            tvDetectionDetails.text = details

        } catch (e: Exception) {
            Log.e("ResultActivity", "Error displaying results", e)
            showError("Error displaying results: ${e.message}")
        }
    }

    private fun showError(message: String) {
        tvDetectionCount.text = "Error"
        tvDetectionDetails.text = message
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

//    private fun displayResults() {
//        // Get image from intent
//        val imageBytes = intent.getByteArrayExtra("image_bytes")
//        val detectionCount = intent.getIntExtra("detection_count", 0)
//        val imageWidth = intent.getIntExtra("image_width", 0)
//        val imageHeight = intent.getIntExtra("image_height", 0)
//        val boxes = intent.getFloatArrayExtra("boxes") ?: floatArrayOf()
//        val scores = intent.getFloatArrayExtra("scores") ?: floatArrayOf()
//
//        if (imageBytes != null) {
//            val originalBitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
//            val resultBitmap = drawDetectionsOnBitmap(originalBitmap, boxes, scores, imageWidth, imageHeight)
//
//            ivResult.setImageBitmap(resultBitmap)
//            tvDetectionCount.text = "Detected: $detectionCount screws"
//
//            // Show detection details
//            val details = buildDetectionDetails(scores)
//            tvDetectionDetails.text = details
//        }
//    }

    private fun drawDetectionsOnBitmap(
        originalBitmap: Bitmap,
        boxes: FloatArray,
        scores: FloatArray
    ): Bitmap {
        val resultBitmap = originalBitmap.copy(Bitmap.Config.ARGB_8888, true)
//        val canvas = Canvas(resultBitmap)
        val canvas = Canvas(resultBitmap)

        val boxPaint = Paint().apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = 4f
            isAntiAlias = true
        }

        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 36f
            isAntiAlias = true
            setShadowLayer(4f, 2f, 2f, Color.BLACK)
        }

        val textBgPaint = Paint().apply {
            color = Color.argb(128, 0, 0, 0)
            isAntiAlias = true
        }

        for (i in scores.indices) {
            val boxIndex = i * 4
            if (boxIndex + 3 < boxes.size) {
                val left = boxes[boxIndex]
                val top = boxes[boxIndex + 1]
                val right = boxes[boxIndex + 2]
                val bottom = boxes[boxIndex + 3]
                val confidence = scores[i]

                // Draw bounding box
                canvas.drawRect(left, top, right, bottom, boxPaint)

                // Draw confidence text with background
                val confidenceText = "Screw: ${(confidence * 100).toInt()}%"
                val textWidth = textPaint.measureText(confidenceText)
                val textHeight = textPaint.textSize

                // Draw text background
                canvas.drawRect(
                    left,
                    top - textHeight - 8,
                    left + textWidth + 8,
                    top - 4,
                    textBgPaint
                )

                // Draw text
                canvas.drawText(confidenceText, left + 4, top - 8, textPaint)
            }
        }

        return resultBitmap
    }

    private fun buildDetectionDetails(scores: FloatArray): String {
        if (scores.isEmpty()) return "No screws detected"

        val avgConfidence = scores.average() * 100
        val maxConfidence = scores.maxOrNull()!! * 100
        val minConfidence = scores.minOrNull()!! * 100

        return "Confidence: ${avgConfidence.toInt()}% avg\n" +
                "Max: ${maxConfidence.toInt()}%, Min: ${minConfidence.toInt()}%"
    }
}