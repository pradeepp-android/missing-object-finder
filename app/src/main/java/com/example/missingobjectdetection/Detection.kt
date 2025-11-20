package com.example.missingobjectdetection
import android.graphics.RectF

data class Detection(
    val boundingBox: RectF,
    val confidence: Float,
    val maskCoefficients: FloatArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Detection

        if (boundingBox != other.boundingBox) return false
        if (confidence != other.confidence) return false
        if (!maskCoefficients.contentEquals(other.maskCoefficients)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = boundingBox.hashCode()
        result = 31 * result + confidence.hashCode()
        result = 31 * result + maskCoefficients.contentHashCode()
        return result
    }
}

data class DetectionResult(
    val detections: List<Detection>,
    val imageWidth: Int,
    val imageHeight: Int
)