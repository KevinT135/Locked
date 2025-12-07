package com.example.locked.ml

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.*

/**
 * TensorFlow Lite model inference for risk prediction
 * Uses trained neural network to predict unlock risk
 */
class MLRiskPredictor(context: Context) {

    private var interpreter: Interpreter? = null
    private val inputSize = 12 // Number of features

    init {
        try {
            // Load the TFLite model from assets
            val model = loadModelFile(context, "risk_model.tflite")
            interpreter = Interpreter(model)
        } catch (e: Exception) {
            e.printStackTrace()
            // Fall back to rule-based if model not available
        }
    }

    /**
     * Predict risk score using ML model
     * Returns probability (0.0 - 1.0) of high risk
     */
    fun predictRisk(
        dayOfWeek: Int,
        hourOfDay: Int,
        sessionDurationMin: Float,
        timeSinceLastUseMin: Float,
        dailyAppLaunches: Int,
        totalDailyScreenTimeMin: Float,
        cumulativeDailyScreenTime: Long,
        appCategoryEncoded: Int
    ): Float {

        if (interpreter == null) {
            // Fall back to rule-based prediction
            return predictRiskRuleBased(
                hourOfDay,
                sessionDurationMin,
                timeSinceLastUseMin,
                dailyAppLaunches,
                totalDailyScreenTimeMin
            )
        }

        try {
            // Create input tensor
            val input = createInputTensor(
                dayOfWeek, hourOfDay, sessionDurationMin, timeSinceLastUseMin,
                dailyAppLaunches, totalDailyScreenTimeMin, cumulativeDailyScreenTime,
                appCategoryEncoded
            )

            // Create output tensor
            val output = Array(1) { FloatArray(1) }

            // Run inference
            interpreter?.run(input, output)

            return output[0][0]

        } catch (e: Exception) {
            e.printStackTrace()
            // Fall back to rule-based
            return predictRiskRuleBased(
                hourOfDay,
                sessionDurationMin,
                timeSinceLastUseMin,
                dailyAppLaunches,
                totalDailyScreenTimeMin
            )
        }
    }

    private fun createInputTensor(
        dayOfWeek: Int,
        hourOfDay: Int,
        sessionDurationMin: Float,
        timeSinceLastUseMin: Float,
        dailyAppLaunches: Int,
        totalDailyScreenTimeMin: Float,
        cumulativeDailyScreenTime: Long,
        appCategoryEncoded: Int
    ): Array<FloatArray> {

        // Engineer features (same as training)
        val isBedtime = if (hourOfDay >= 22 || hourOfDay <= 2) 1f else 0f
        val isMorning = if (hourOfDay in 6..9) 1f else 0f
        val isEvening = if (hourOfDay in 18..22) 1f else 0f
        val isWeekend = if (dayOfWeek == 1 || dayOfWeek == 7) 1f else 0f

        // Feature order must match training:
        // dayOfWeek, hourOfDay, sessionDuration_min, timeSinceLastUse_min,
        // dailyAppLaunches, totalDailyScreenTime_min, cumulativeDailyScreenTime,
        // appCategory_encoded, is_bedtime, is_morning, is_evening, is_weekend

        return arrayOf(
            floatArrayOf(
                dayOfWeek.toFloat(),
                hourOfDay.toFloat(),
                sessionDurationMin,
                timeSinceLastUseMin,
                dailyAppLaunches.toFloat(),
                totalDailyScreenTimeMin,
                cumulativeDailyScreenTime.toFloat(),
                appCategoryEncoded.toFloat(),
                isBedtime,
                isMorning,
                isEvening,
                isWeekend
            )
        )
    }

    /**
     * Rule-based fallback if ML model is not available
     */
    private fun predictRiskRuleBased(
        hourOfDay: Int,
        sessionDurationMin: Float,
        timeSinceLastUseMin: Float,
        dailyAppLaunches: Int,
        totalDailyScreenTimeMin: Float
    ): Float {
        var risk = 0f

        // Bedtime risk
        if (hourOfDay >= 22 || hourOfDay <= 2) risk += 0.3f

        // Frequency risk
        if (dailyAppLaunches >= 10) risk += 0.2f
        else if (dailyAppLaunches >= 5) risk += 0.1f

        // Duration risk
        if (sessionDurationMin >= 20) risk += 0.2f
        else if (sessionDurationMin >= 10) risk += 0.1f

        // Recency risk
        if (timeSinceLastUseMin < 5) risk += 0.2f
        else if (timeSinceLastUseMin < 15) risk += 0.1f

        // Cumulative risk
        if (totalDailyScreenTimeMin >= 180) risk += 0.2f
        else if (totalDailyScreenTimeMin >= 120) risk += 0.1f

        return risk.coerceIn(0f, 1f)
    }

    /**
     * Load TFLite model from assets
     */
    private fun loadModelFile(context: Context, modelPath: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(modelPath)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    /**
     * Get category encoding for app category
     */
    fun getCategoryEncoding(category: String): Int {
        // Must match the encoding from training
        return when (category.uppercase()) {
            "GAME" -> 0
            "NEWS" -> 1
            "OTHER" -> 2
            "PRODUCTIVITY" -> 3
            "SOCIAL" -> 4
            "VIDEO" -> 5
            else -> 2 // Default to OTHER
        }
    }

    fun close() {
        interpreter?.close()
    }
}