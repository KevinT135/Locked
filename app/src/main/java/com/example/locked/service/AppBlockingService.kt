package com.example.locked.service

import android.annotation.SuppressLint
import android.app.*
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.locked.MainActivity
import com.example.locked.R
import com.example.locked.data.LockedRepository
import kotlinx.coroutines.*

/**
 * Foreground service that continuously monitors app usage and blocks configured apps
 * Uses UsageStatsManager to detect app launches
 */
class AppBlockingService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private lateinit var repository: LockedRepository
    private var isLocked = true
    private var monitoringJob: Job? = null

    private val NOTIFICATION_CHANNEL_ID = "app_blocking_channel"
    private val NOTIFICATION_ID = 1
    private val CHECK_INTERVAL = 500L // Check every 500ms for better responsiveness

    private var lastForegroundApp: String? = null
    private var lastCheckTime: Long = 0

    companion object {
        const val ACTION_LOCK = "com.example.locked.LOCK"
        const val ACTION_UNLOCK = "com.example.locked.UNLOCK"
        const val ACTION_CHECK_STATUS = "com.example.locked.CHECK_STATUS"

        private const val TAG = "AppBlockingService"
        private var isServiceRunning = false

        fun isRunning(): Boolean = isServiceRunning
    }

    override fun onCreate() {
        super.onCreate()
        repository = LockedRepository(applicationContext)
        createNotificationChannel()
        isServiceRunning = true
        Log.d(TAG, "Service created")
    }

    @SuppressLint("ForegroundServiceType")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: action=${intent?.action}")

        when (intent?.action) {
            ACTION_LOCK -> {
                isLocked = true
                serviceScope.launch {
                    repository.startBlockingSession()
                }
                startMonitoring()
                updateNotification("Apps LOCKED - Monitoring active")
                Log.d(TAG, "Apps locked, monitoring started")
            }
            ACTION_UNLOCK -> {
                isLocked = false
                serviceScope.launch {
                    repository.endBlockingSession("nfc")
                }
                stopMonitoring()
                updateNotification("Apps UNLOCKED")
                Log.d(TAG, "Apps unlocked, monitoring stopped")
            }
            else -> {
                if (isLocked) {
                    startMonitoring()
                    updateNotification("Apps LOCKED - Monitoring active")
                } else {
                    updateNotification("Apps UNLOCKED")
                }
            }
        }

        startForeground(NOTIFICATION_ID, createNotification("Service running"))

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopMonitoring()
        serviceScope.cancel()
        isServiceRunning = false
        Log.d(TAG, "Service destroyed")
    }

    private fun startMonitoring() {
        stopMonitoring()
        Log.d(TAG, "Starting app monitoring")
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
        Log.d(TAG, "Stopped app monitoring")
    }

    private suspend fun checkAndBlockApps() {
        try {
            val currentApp = getForegroundApp()

            if (currentApp != null &&
                currentApp != packageName &&
                currentApp != lastForegroundApp) {

                Log.d(TAG, "Detected app: $currentApp")
                lastForegroundApp = currentApp

                // Check if app is blocked
                val isBlocked = repository.isAppBlocked(currentApp)

                if (isBlocked && isLocked) {
                    Log.d(TAG, "BLOCKING app: $currentApp")

                    // Record the attempt
                    withContext(Dispatchers.IO) {
                        repository.recordUsageEvent(
                            packageName = currentApp,
                            appName = getAppName(currentApp),
                            category = getCategoryForPackage(currentApp),
                            sessionDuration = 0,
                            wasBlocked = true,
                            unlockAttempted = true,
                            unlockSucceeded = false
                        )
                    }

                    // Block the app by bringing our app to foreground
                    blockApp()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking apps", e)
        }
    }

    private fun getForegroundApp(): String? {
        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        if (usageStatsManager == null) {
            Log.e(TAG, "UsageStatsManager is null - permission not granted?")
            return null
        }

        val currentTime = System.currentTimeMillis()

        // Query usage events from the last 1 second
        val events = usageStatsManager.queryEvents(currentTime - 1000, currentTime)

        var lastResumedApp: String? = null
        val event = UsageEvents.Event()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)

            // Track the most recent ACTIVITY_RESUMED event
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                lastResumedApp = event.packageName
            }
        }

        return lastResumedApp
    }

    private fun blockApp() {
        // Bring the blocking activity to foreground
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
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
        return try {
            val pm = packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                when (appInfo.category) {
                    android.content.pm.ApplicationInfo.CATEGORY_GAME -> "GAME"
                    android.content.pm.ApplicationInfo.CATEGORY_SOCIAL -> "SOCIAL"
                    android.content.pm.ApplicationInfo.CATEGORY_VIDEO -> "VIDEO"
                    android.content.pm.ApplicationInfo.CATEGORY_NEWS -> "NEWS"
                    android.content.pm.ApplicationInfo.CATEGORY_PRODUCTIVITY -> "PRODUCTIVITY"
                    else -> "OTHER"
                }
            } else {
                "OTHER"
            }
        } catch (e: Exception) {
            "OTHER"
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
                setShowBadge(false)
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
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(content: String) {
        val notification = createNotification(content)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.notify(NOTIFICATION_ID, notification)
    }
}