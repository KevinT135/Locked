package com.example.locked.ml

import android.content.Context
import com.example.locked.data.LockedRepository
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.FileWriter

/**
 * Utility to export usage data for ML model training
 */
class DataExporter(private val context: Context) {

    private val repository = LockedRepository(context)

    /**
     * Export all usage events to CSV file for ML training
     * File saved to: /storage/emulated/0/Download/locked_training_data.csv
     */
    suspend fun exportToCSV(): File {
        val events = repository.getRecentEvents(10000).first() // Get up to 10k events

        // Create CSV file in Downloads folder
        val downloadsDir = context.getExternalFilesDir(null)
        val file = File(downloadsDir, "locked_training_data.csv")

        FileWriter(file).use { writer ->
            // Write header
            writer.append("timestamp,dayOfWeek,hourOfDay,packageName,appCategory,")
            writer.append("sessionDuration,timeSinceLastUse,dailyAppLaunches,")
            writer.append("totalDailyScreenTime,cumulativeDailyScreenTime,")
            writer.append("wasBlocked,unlockAttempted,unlockSucceeded,riskScore\n")

            // Write data rows
            events.forEach { event ->
                writer.append("${event.timestamp},")
                writer.append("${event.dayOfWeek},")
                writer.append("${event.hourOfDay},")
                writer.append("${event.packageName},")
                writer.append("${event.appCategory},")
                writer.append("${event.sessionDuration},")
                writer.append("${event.timeSinceLastUse},")
                writer.append("${event.dailyAppLaunches},")
                writer.append("${event.totalDailyScreenTime},")
                writer.append("${event.cumulativeDailyScreenTime},")
                writer.append("${if (event.wasBlocked) 1 else 0},")
                writer.append("${if (event.unlockAttempted) 1 else 0},")
                writer.append("${if (event.unlockSucceeded) 1 else 0},")
                writer.append("${event.riskScore}\n")
            }
        }

        return file
    }

    /**
     * Export session data to CSV
     */
    suspend fun exportSessionsToCSV(): File {
        val sessions = repository.getRecentSessions(1000).first()

        val downloadsDir = context.getExternalFilesDir(null)
        val file = File(downloadsDir, "locked_sessions.csv")

        FileWriter(file).use { writer ->
            writer.append("id,startTime,endTime,duration,unlockMethod\n")

            sessions.forEach { session ->
                writer.append("${session.id},")
                writer.append("${session.startTime},")
                writer.append("${session.endTime ?: ""},")
                writer.append("${session.duration ?: ""},")
                writer.append("${session.unlockMethod}\n")
            }
        }

        return file
    }
}