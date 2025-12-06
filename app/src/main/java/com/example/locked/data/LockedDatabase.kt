package com.example.locked.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [UsageEvent::class, BlockedApp::class, BlockingSession::class],
    version = 1,
    exportSchema = false
)
abstract class LockedDatabase : RoomDatabase() {
    abstract fun usageEventDao(): UsageEventDao
    abstract fun blockedAppDao(): BlockedAppDao
    abstract fun blockingSessionDao(): BlockingSessionDao

    companion object {
        @Volatile
        private var INSTANCE: LockedDatabase? = null

        fun getDatabase(context: Context): LockedDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    LockedDatabase::class.java,
                    "locked_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}