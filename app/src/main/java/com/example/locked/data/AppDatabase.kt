package com.example.locked.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface UsageEventDao {
    @Insert
    suspend fun insertEvent(event: UsageEvent)

    @Query("SELECT * FROM usage_events ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentEvents(limit: Int = 100): Flow<List<UsageEvent>>

    @Query("SELECT * FROM usage_events WHERE timestamp >= :startTime")
    suspend fun getEventsSince(startTime: Long): List<UsageEvent>

    @Query("SELECT AVG(sessionDuration) FROM usage_events WHERE packageName = :packageName")
    suspend fun getAverageSessionDuration(packageName: String): Float?

    @Query("SELECT COUNT(*) FROM usage_events WHERE packageName = :packageName AND timestamp >= :dayStart")
    suspend fun getDailyLaunchCount(packageName: String, dayStart: Long): Int

    @Query("DELETE FROM usage_events WHERE timestamp < :cutoffTime")
    suspend fun deleteOldEvents(cutoffTime: Long)

    @Query("DELETE FROM usage_events")
    suspend fun deleteAllEvents()
}

@Dao
interface BlockedAppDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBlockedApp(app: BlockedApp)

    @Query("SELECT * FROM blocked_apps WHERE isBlocked = 1")
    fun getAllBlockedApps(): Flow<List<BlockedApp>>

    @Query("SELECT * FROM blocked_apps WHERE packageName = :packageName")
    suspend fun getBlockedApp(packageName: String): BlockedApp?

    @Update
    suspend fun updateBlockedApp(app: BlockedApp)

    @Delete
    suspend fun deleteBlockedApp(app: BlockedApp)

    @Query("UPDATE blocked_apps SET isBlocked = :isBlocked WHERE packageName = :packageName")
    suspend fun setAppBlocked(packageName: String, isBlocked: Boolean)

    @Query("DELETE FROM blocked_apps")
    suspend fun deleteAllBlockedApps()
}

@Dao
interface BlockingSessionDao {
    @Insert
    suspend fun insertSession(session: BlockingSession): Long

    @Update
    suspend fun updateSession(session: BlockingSession)

    @Query("SELECT * FROM blocking_sessions WHERE endTime IS NULL LIMIT 1")
    suspend fun getCurrentSession(): BlockingSession?

    @Query("SELECT * FROM blocking_sessions ORDER BY startTime DESC LIMIT :limit")
    fun getRecentSessions(limit: Int = 20): Flow<List<BlockingSession>>

    @Query("SELECT AVG(duration) FROM blocking_sessions WHERE duration IS NOT NULL")
    suspend fun getAverageSessionDuration(): Float?

    @Query("DELETE FROM blocking_sessions")
    suspend fun deleteAllSessions()
}