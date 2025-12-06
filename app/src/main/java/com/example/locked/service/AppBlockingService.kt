package com.example.locked.service

import android.annotation.SuppressLint
import android.app.*
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.locked.MainActivity
import com.example.locked.R
import com.example.locked.data.LockedRepository
import kotlinx.coroutines.*
import java.util.*

/**
 * Foreground service that continuously monitors app usage and blocks configured apps
 * Uses UsageStatsManager to detect app launches as described in proposal
 */
class AppBlockingService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private lateinit var repository: LockedRepository
    private var isLocked = true
    private var monitoringJob: Job? = null

    private val NOTIFICATION_CHANNEL_ID = "app_blocking_channel"
    private val NOTIFICATION_ID = 1
    private val CHECK_INTERVAL = 1000L // Check every second

    private var lastCheckedApp: String? = null
    private var lastCheckedTime: Long = 0

    companion object {
        const val ACTION_LOCK = "com.example.locked.LOCK"
        const val ACTION_UNLOCK = "com.example.locked.UNLOCK"
        const val ACTION_CHECK_STATUS = "com.example.locked.CHECK_STATUS"

        private var isServiceRunning = false

        fun isRunning(): Boolean = isServiceRunning
    }

    override fun onCreate() {
        super.onCreate()
        repository = LockedRepository(applicationContext)
        createNotificationChannel()
        isServiceRunning = true
    }

    @SuppressLint("ForegroundServiceType")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_LOCK -> {
                isLocked = true
                serviceScope.launch {
                    repository.startBlockingSession()
                }
                startMonitoring()
                updateNotification("Apps are LOCKED")
            }
            ACTION_UNLOCK -> {
                isLocked = false
                serviceScope.launch {
                    repository.endBlockingSession("nfc")
                }
                stopMonitoring()
                updateNotification("Apps are UNLOCKED")
            }
            else -> {
                if (isLocked) {
                    startMonitoring()
                    updateNotification("Apps are LOCKED")
                } else {
                    updateNotification("Apps are UNLOCKED")
                }
            }
        }

        startForeground(NOTIFICATION_ID, createNotification("Service started"))

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopMonitoring()
        serviceScope.cancel()
        isServiceRunning = false
    }

    private fun startMonitoring() {
        stopMonitoring()
        monitoringJob = serviceScope.launch {
            while (isActive && isLocked) {
                checkAndBlockApps()
                delay(CHECK_INTERVAL)
            }
        }
    }

    private fun stopMonitoring() {
        monitoringJob?.cancel()
        monitoringJob = null
    }

    private suspend fun checkAndBlockApps() {
        try {
            val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val currentTime = System.currentTimeMillis()

            // Get usage stats for the last 2 seconds
            val stats = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_BEST,
                currentTime - 2000,
                currentTime
            )

            // Find the most recently used app
            val sortedStats = stats?.sortedByDescending { it.lastTimeUsed }
            val currentApp = sortedStats?.firstOrNull()

            if (currentApp != null && currentApp.packageName != packageName) {
                // Avoid checking the same app multiple times rapidly
                if (currentApp.packageName == lastCheckedApp &&
                    currentTime - lastCheckedTime < 500) {
                    return
                }

                lastCheckedApp = currentApp.packageName
                lastCheckedTime = currentTime

                // Check if app is blocked
                val isBlocked = repository.isAppBlocked(currentApp.packageName)

                if (isBlocked && isLocked) {
                    // Record the attempt
                    repository.recordUsageEvent(
                        packageName = currentApp.packageName,
                        appName = getAppName(currentApp.packageName),
                        category = getCategoryForPackage(currentApp.packageName),
                        sessionDuration = 0,
                        wasBlocked = true,
                        unlockAttempted = true,
                        unlockSucceeded = false
                    )

                    // Block the app by bringing our app to foreground
                    blockApp()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun blockApp() {
        // Bring the blocking activity to foreground
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("show_blocked_message", true)
        }
        startActivity(intent)
    }

    private fun getAppName(packageName: String): String {
        return try {
            val pm = packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName
        }
    }

    private fun getCategoryForPackage(packageName: String): String {
        // Simple category detection based on package name
        // In a real implementation, use ApplicationInfo.category (API 26+)
        return when {
            packageName.contains("social", ignoreCase = true) -> "SOCIAL"
            packageName.contains("game", ignoreCase = true) -> "GAME"
            packageName.contains("video", ignoreCase = true) -> "VIDEO"
            packageName.contains("news", ignoreCase = true) -> "NEWS"
            else -> "OTHER"
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "App Blocking Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitors and blocks configured apps"
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(content: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Locked - App Blocker")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(content: String) {
        val notification = createNotification(content)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}