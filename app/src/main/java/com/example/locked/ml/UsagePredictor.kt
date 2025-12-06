package com.example.locked.ml

import android.content.Context
import com.example.locked.data.LockedRepository
import com.example.locked.data.UsageEvent
import kotlinx.coroutines.flow.first
import java.util.*

/**
 * Hybrid ML/Rule-based predictor for high-risk usage detection
 * Phase 1: Rule-based heuristics (immediate functionality)
 * Phase 2: TensorFlow classifier trained on collected data
 */
class UsagePredictor(private val context: Context) {

    private val repository = LockedRepository(context)

    /**
     * Assess risk of unlocking based on current context
     * Returns risk score 0.0-1.0 where higher = riskier to unlock
     */
    suspend fun assessUnlockRisk(): RiskAssessment {
        val recentEvents = repository.getRecentEvents(50).first()
        val now = System.currentTimeMillis()
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = now

        val hourOfDay = calendar.get(Calendar.HOUR_OF_DAY)
        val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)

        // Calculate risk factors based on research [2] from proposal
        val riskFactors = mutableMapOf<String, Float>()

        // 1. Time-based risk (bedtime usage is high risk)
        riskFactors["bedtime_risk"] = calculateBedtimeRisk(hourOfDay)

        // 2. Usage frequency (high recent usage = high risk)
        riskFactors["frequency_risk"] = calculateFrequencyRisk(recentEvents, now)

        // 3. Session duration patterns (long recent sessions = high risk)
        riskFactors["duration_risk"] = calculateDurationRisk(recentEvents)

        // 4. Time since last use (short time = high risk, indicates urge)
        riskFactors["recency_risk"] = calculateRecencyRisk(recentEvents, now)

        // 5. Daily cumulative screen time
        riskFactors["cumulative_risk"] = calculateCumulativeRisk(recentEvents, now)

        // 6. Day of week patterns (weekends may differ)
        riskFactors["day_risk"] = calculateDayRisk(dayOfWeek, recentEvents)

        // Weighted combination of risk factors
        val weights = mapOf(
            "bedtime_risk" to 0.25f,
            "frequency_risk" to 0.20f,
            "duration_risk" to 0.15f,
            "recency_risk" to 0.20f,
            "cumulative_risk" to 0.15f,
            "day_risk" to 0.05f
        )

        val totalRisk = riskFactors.entries.sumOf {
            (it.value * weights.getOrDefault(it.key, 0.1f)).toDouble()
        }.toFloat()

        val normalizedRisk = totalRisk.coerceIn(0f, 1f)

        return RiskAssessment(
            riskScore = normalizedRisk,
            riskLevel = when {
                normalizedRisk >= 0.7f -> RiskLevel.HIGH
                normalizedRisk >= 0.4f -> RiskLevel.MEDIUM
                else -> RiskLevel.LOW
            },
            factors = riskFactors,
            recommendation = generateRecommendation(normalizedRisk, riskFactors)
        )
    }

    /**
     * Bedtime risk based on research showing smartphone use before bed is problematic
     * Peak risk: 10 PM - 2 AM
     */
    private fun calculateBedtimeRisk(hourOfDay: Int): Float {
        return when (hourOfDay) {
            22, 23, 0, 1 -> 1.0f  // 10 PM - 2 AM: High risk
            20, 21, 2, 3 -> 0.6f  // 8-10 PM, 2-4 AM: Medium risk
            else -> 0.2f           // Other times: Low risk
        }
    }

    /**
     * Frequency risk: More launches in recent period = higher risk
     */
    private fun calculateFrequencyRisk(events: List<UsageEvent>, now: Long): Float {
        val last30Minutes = now - (30 * 60 * 1000)
        val recentLaunches = events.count { it.timestamp >= last30Minutes }

        return when {
            recentLaunches >= 10 -> 1.0f
            recentLaunches >= 5 -> 0.6f
            recentLaunches >= 2 -> 0.3f
            else -> 0.1f
        }
    }

    /**
     * Duration risk: Longer recent sessions indicate problematic usage
     */
    private fun calculateDurationRisk(events: List<UsageEvent>): Float {
        if (events.isEmpty()) return 0f

        val avgDuration = events.take(10).map { it.sessionDuration }.average()
        val minutes = avgDuration / (60 * 1000)

        return when {
            minutes >= 20 -> 1.0f
            minutes >= 10 -> 0.7f
            minutes >= 5 -> 0.4f
            else -> 0.1f
        }
    }

    /**
     * Recency risk: Very recent usage suggests strong urge
     */
    private fun calculateRecencyRisk(events: List<UsageEvent>, now: Long): Float {
        if (events.isEmpty()) return 0f

        val lastEvent = events.firstOrNull() ?: return 0f
        val minutesSinceLast = (now - lastEvent.timestamp) / (60 * 1000)

        return when {
            minutesSinceLast < 5 -> 1.0f   // < 5 min: Very high risk
            minutesSinceLast < 15 -> 0.7f  // < 15 min: High risk
            minutesSinceLast < 30 -> 0.4f  // < 30 min: Medium risk
            else -> 0.1f                    // > 30 min: Low risk
        }
    }

    /**
     * Cumulative risk: High daily screen time suggests problematic pattern
     */
    private fun calculateCumulativeRisk(events: List<UsageEvent>, now: Long): Float {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = now
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        val dayStart = calendar.timeInMillis

        val todayEvents = events.filter { it.timestamp >= dayStart }
        val totalMinutes = todayEvents.sumOf { it.sessionDuration } / (60 * 1000)

        return when {
            totalMinutes >= 180 -> 1.0f  // 3+ hours: High risk
            totalMinutes >= 120 -> 0.7f  // 2+ hours: Medium-high risk
            totalMinutes >= 60 -> 0.4f   // 1+ hours: Medium risk
            else -> 0.1f                  // < 1 hour: Low risk
        }
    }

    /**
     * Day risk: Weekends may have different patterns
     */
    private fun calculateDayRisk(dayOfWeek: Int, events: List<UsageEvent>): Float {
        // Saturday = 7, Sunday = 1 in Calendar
        val isWeekend = dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY

        // Slight increase on weekends when people tend to use phones more
        return if (isWeekend) 0.6f else 0.4f
    }

    private fun generateRecommendation(riskScore: Float, factors: Map<String, Float>): String {
        return when {
            riskScore >= 0.7f -> {
                val topFactor = factors.maxByOrNull { it.value }?.key
                when (topFactor) {
                    "bedtime_risk" -> "It's late - better to avoid phone use before bed for better sleep quality."
                    "frequency_risk" -> "You've been using your phone frequently. Take a longer break."
                    "cumulative_risk" -> "You've had significant screen time today. Consider extending your break."
                    else -> "High risk detected. Consider keeping apps locked for now."
                }
            }
            riskScore >= 0.4f -> "Medium risk detected. Be mindful of your usage."
            else -> "Low risk - you're managing your usage well."
        }
    }
}

data class RiskAssessment(
    val riskScore: Float,
    val riskLevel: RiskLevel,
    val factors: Map<String, Float>,
    val recommendation: String
)

enum class RiskLevel {
    LOW, MEDIUM, HIGH
}