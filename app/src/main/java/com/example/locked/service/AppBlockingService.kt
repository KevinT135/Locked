package com.example.locked.service

import android.annotation.SuppressLint
import android.app.*
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.app.NotificationCompat
import com.example.locked.BlockingActivity
import com.example.locked.MainActivity
import com.example.locked.R
import com.example.locked.data.LockedRepository
import kotlinx.coroutines.*

/**
 * SIMPLIFIED APPROACH - Faster polling + System Overlay
 *
 * Key improvements:
 * 1. Faster polling (100ms instead of 300ms)
 * 2. Full-screen overlay that covers everything
 * 3. Immediately bring blocking activity to front
 * 4. Send HOME intent to close blocked app
 */
class AppBlockingService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private lateinit var repository: LockedRepository
    private var isLocked = true
    private var monitoringJob: Job? = null
    private var windowManager: WindowManager? = null
    private var overlayView: FrameLayout? = null

    private val NOTIFICATION_CHANNEL_ID = "app_blocking_channel"
    private val NOTIFICATION_ID = 1
    private val CHECK_INTERVAL = 100L // Faster! 100ms instead of 300ms

    private var lastForegroundApp: String? = null
    private var lastBlockTime: Long = 0
    private val BLOCK_COOLDOWN = 500L // Reduced cooldown

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
                updateNotification("🔒 Apps LOCKED - Blocking active")
                Log.d(TAG, "Apps locked, monitoring started")
            }
            ACTION_UNLOCK -> {
                isLocked = false
                serviceScope.launch {
                    repository.endBlockingSession("nfc")
                }
                stopMonitoring()
                updateNotification("🔓 Apps UNLOCKED")
                Log.d(TAG, "Apps unlocked, monitoring stopped")
            }
            else -> {
                if (isLocked) {
                    startMonitoring()
                    updateNotification("🔒 Apps LOCKED - Blocking active")
                } else {
                    updateNotification("🔓 Apps UNLOCKED")
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
        removeOverlay()
        serviceScope.cancel()
        isServiceRunning = false
        Log.d(TAG, "Service destroyed")
    }

    private fun startMonitoring() {
        stopMonitoring()
        Log.d(TAG, "Starting app monitoring (100ms polling)")
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
        removeOverlay()
        Log.d(TAG, "Stopped app monitoring")
    }

    private suspend fun checkAndBlockApps() {
        try {
            val currentApp = getForegroundApp()
            val currentTime = System.currentTimeMillis()

            if (currentApp != null &&
                currentApp != packageName &&
                currentApp != "com.example.locked" &&
                !currentApp.startsWith("com.android.launcher")) {

                // Only log if it's a new app
                if (currentApp != lastForegroundApp) {
                    Log.d(TAG, "Detected app: $currentApp")
                }

                // Check if app is blocked
                val isBlocked = repository.isAppBlocked(currentApp)

                if (isBlocked && isLocked) {
                    // Check cooldown
                    if (currentApp != lastForegroundApp ||
                        currentTime - lastBlockTime > BLOCK_COOLDOWN) {

                        val appName = getAppName(currentApp)
                        Log.d(TAG, "🚫 BLOCKING APP: $currentApp ($appName)")

                        lastBlockTime = currentTime
                        lastForegroundApp = currentApp

                        // Record the attempt
                        withContext(Dispatchers.IO) {
                            repository.recordUsageEvent(
                                packageName = currentApp,
                                appName = appName,
                                category = getCategoryForPackage(currentApp),
                                sessionDuration = 0,
                                wasBlocked = true,
                                unlockAttempted = true,
                                unlockSucceeded = false
                            )
                        }

                        // AGGRESSIVE BLOCKING - Use multiple methods
                        blockAppAggressively(currentApp, appName)
                    }
                } else {
                    lastForegroundApp = currentApp
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking apps", e)
        }
    }

    private fun blockAppAggressively(packageName: String, appName: String) {
        // Method 1: Launch blocking activity with aggressive flags
        val blockIntent = Intent(this, BlockingActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            putExtra("blocked_app_name", appName)
            putExtra("blocked_package_name", packageName)
        }
        startActivity(blockIntent)

        // Method 2: Also send HOME intent to close the blocked app
        serviceScope.launch {
            delay(150) // Brief delay for blocking activity to appear
            sendHomeIntent()
        }

        // Method 3: Show system overlay (if permission granted)
        if (canDrawOverlays()) {
            showBlockingOverlay()
        }

        Log.d(TAG, "Executed aggressive blocking for: $packageName")
    }

    private fun sendHomeIntent() {
        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(homeIntent)
            Log.d(TAG, "Sent HOME intent")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send HOME intent", e)
        }
    }

    private fun canDrawOverlays(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    @SuppressLint("InflateParams")
    private fun showBlockingOverlay() {
        try {
            if (overlayView != null) return // Already showing

            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

            overlayView = FrameLayout(this).apply {
                setBackgroundColor(android.graphics.Color.parseColor("#F44336")) // Red background
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
            }

            windowManager?.addView(overlayView, params)

            // Remove overlay after a brief moment (blocking activity should be shown)
            serviceScope.launch {
                delay(1000)
                removeOverlay()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show overlay", e)
        }
    }

    private fun removeOverlay() {
        try {
            overlayView?.let {
                windowManager?.removeView(it)
                overlayView = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove overlay", e)
        }
    }

    private fun getForegroundApp(): String? {
        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        if (usageStatsManager == null) {
            Log.e(TAG, "UsageStatsManager is null - permission not granted?")
            return null
        }

        val currentTime = System.currentTimeMillis()

        // Query events from the last 500ms (wider window for better detection)
        val events = usageStatsManager.queryEvents(currentTime - 500, currentTime)

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