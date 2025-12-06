package com.example.locked.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import java.util.*

class LockedRepository(context: Context) {
    private val database = LockedDatabase.getDatabase(context)
    private val usageEventDao = database.usageEventDao()
    private val blockedAppDao = database.blockedAppDao()
    private val sessionDao = database.blockingSessionDao()

    // Usage Event operations
    suspend fun recordUsageEvent(
        packageName: String,
        appName: String,
        category: String,
        sessionDuration: Long,
        wasBlocked: Boolean,
        unlockAttempted: Boolean,
        unlockSucceeded: Boolean
    ) {
        val now = System.currentTimeMillis()
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = now

        // Get daily stats
        val dayStart = calendar.apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val dailyLaunches = usageEventDao.getDailyLaunchCount(packageName, dayStart)

        // Calculate time since last use
        val recentEvents = usageEventDao.getEventsSince(dayStart)
        val lastUse = recentEvents.filter { it.packageName == packageName }
            .maxByOrNull { it.timestamp }
        val timeSinceLastUse = if (lastUse != null) now - lastUse.timestamp else 0L

        // Calculate cumulative daily screen time
        val totalDailyScreenTime = recentEvents.sumOf { it.sessionDuration }

        val event = UsageEvent(
            timestamp = now,
            dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK),
            hourOfDay = calendar.get(Calendar.HOUR_OF_DAY),
            packageName = packageName,
            appCategory = category,
            sessionDuration = sessionDuration,
            timeSinceLastUse = timeSinceLastUse,
            dailyAppLaunches = dailyLaunches + 1,
            totalDailyScreenTime = totalDailyScreenTime,
            cumulativeDailyScreenTime = totalDailyScreenTime + sessionDuration,
            wasBlocked = wasBlocked,
            unlockAttempted = unlockAttempted,
            unlockSucceeded = unlockSucceeded
        )

        usageEventDao.insertEvent(event)
    }

    fun getRecentEvents(limit: Int = 100): Flow<List<UsageEvent>> {
        return usageEventDao.getRecentEvents(limit)
    }

    suspend fun cleanOldEvents(daysToKeep: Int = 30) {
        val cutoff = System.currentTimeMillis() - (daysToKeep * 24 * 60 * 60 * 1000L)
        usageEventDao.deleteOldEvents(cutoff)
    }

    // Blocked App operations
    suspend fun addBlockedApp(packageName: String, appName: String, category: String) {
        val app = BlockedApp(
            packageName = packageName,
            appName = appName,
            category = category,
            isBlocked = true,
            addedTimestamp = System.currentTimeMillis()
        )
        blockedAppDao.insertBlockedApp(app)
    }

    fun getAllBlockedApps(): Flow<List<BlockedApp>> {
        return blockedAppDao.getAllBlockedApps()
    }

    suspend fun setAppBlocked(packageName: String, isBlocked: Boolean) {
        blockedAppDao.setAppBlocked(packageName, isBlocked)
    }

    suspend fun isAppBlocked(packageName: String): Boolean {
        return blockedAppDao.getBlockedApp(packageName)?.isBlocked ?: false
    }

    // Blocking Session operations
    suspend fun startBlockingSession(): Long {
        val session = BlockingSession(
            startTime = System.currentTimeMillis(),
            endTime = null,
            unlockMethod = "nfc",
            duration = null
        )
        return sessionDao.insertSession(session)
    }

    suspend fun endBlockingSession(unlockMethod: String) {
        val currentSession = sessionDao.getCurrentSession()
        if (currentSession != null) {
            val endTime = System.currentTimeMillis()
            val updatedSession = currentSession.copy(
                endTime = endTime,
                unlockMethod = unlockMethod,
                duration = endTime - currentSession.startTime
            )
            sessionDao.updateSession(updatedSession)
        }
    }

    fun getRecentSessions(limit: Int = 20): Flow<List<BlockingSession>> {
        return sessionDao.getRecentSessions(limit)
    }
}