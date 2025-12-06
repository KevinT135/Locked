package com.example.locked.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity representing app usage events for ML training data collection
 * Features align with proposal: time-based, contextual, and behavioral patterns
 */
@Entity(tableName = "usage_events")
data class UsageEvent(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    // Temporal features
    val timestamp: Long,
    val dayOfWeek: Int,        // 1-7 (Monday-Sunday)
    val hourOfDay: Int,         // 0-23

    // Session features
    val packageName: String,
    val appCategory: String,
    val sessionDuration: Long,  // milliseconds
    val timeSinceLastUse: Long, // milliseconds

    // Behavioral features
    val dailyAppLaunches: Int,
    val totalDailyScreenTime: Long,
    val cumulativeDailyScreenTime: Long,

    // Context
    val wasBlocked: Boolean,
    val unlockAttempted: Boolean,
    val unlockSucceeded: Boolean,

    // Risk assessment (will be populated by ML model later)
    val riskScore: Float = 0f
)

/**
 * Entity for storing blocked apps configuration
 */
@Entity(tableName = "blocked_apps")
data class BlockedApp(
    @PrimaryKey
    val packageName: String,
    val appName: String,
    val category: String,
    val isBlocked: Boolean = true,
    val addedTimestamp: Long
)

/**
 * Entity for storing blocking sessions
 */
@Entity(tableName = "blocking_sessions")
data class BlockingSession(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val startTime: Long,
    val endTime: Long?,
    val unlockMethod: String,  // "nfc", "override", etc.
    val duration: Long?
)