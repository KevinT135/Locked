package com.example.locked.service

import android.annotation.SuppressLint
import android.app.*
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.example.locked.MainActivity
import com.example.locked.R
import com.example.locked.data.LockedRepository
import kotlinx.coroutines.*

/**
 * SIMPLIFIED OVERLAY APPROACH
 *
 * Instead of trying to close apps, we just show a full-screen overlay
 * that blocks the user from interacting with the blocked app.
 *
 * Much simpler and more reliable!
 */
class AppBlockingService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private lateinit var repository: LockedRepository
    private var isLocked = true
    private var monitoringJob: Job? = null
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null

    private val NOTIFICATION_CHANNEL_ID = "app_blocking_channel"
    private val NOTIFICATION_ID = 1
    private val CHECK_INTERVAL = 200L // Check every 200ms

    private var lastForegroundApp: String? = null
    private var isOverlayShowing = false

    companion object {
        const val ACTION_LOCK = "com.example.locked.LOCK"
        const val ACTION_UNLOCK = "com.example.locked.UNLOCK"

        private const val TAG = "AppBlockingService"
        private var isServiceRunning = false

        fun isRunning(): Boolean = isServiceRunning
    }

    override fun onCreate() {
        super.onCreate()
        repository = LockedRepository(applicationContext)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
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
                updateNotification("🔒 Apps LOCKED - Monitoring active")
                Log.d(TAG, "Apps locked, monitoring started")
            }
            ACTION_UNLOCK -> {
                isLocked = false
                serviceScope.launch {
                    repository.endBlockingSession("nfc")
                }
                stopMonitoring()
                removeOverlay()
                updateNotification("🔓 Apps UNLOCKED")
                Log.d(TAG, "Apps unlocked, monitoring stopped")
            }
            else -> {
                if (isLocked) {
                    startMonitoring()
                    updateNotification("🔒 Apps LOCKED - Monitoring active")
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
        Log.d(TAG, "Starting app monitoring with overlay approach")
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
                currentApp != "com.example.locked" &&
                !currentApp.startsWith("com.android.launcher")) {

                // Check if app is blocked
                val isBlocked = repository.isAppBlocked(currentApp)

                if (isBlocked && isLocked) {
                    if (currentApp != lastForegroundApp || !isOverlayShowing) {
                        val appName = getAppName(currentApp)
                        Log.d(TAG, "🚫 BLOCKING APP: $currentApp ($appName)")

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

                        // Show blocking overlay
                        withContext(Dispatchers.Main) {
                            showBlockingOverlay(appName)
                        }
                    }
                } else {
                    // App is not blocked, remove overlay if showing
                    if (currentApp != lastForegroundApp && isOverlayShowing) {
                        withContext(Dispatchers.Main) {
                            removeOverlay()
                        }
                    }
                    lastForegroundApp = currentApp
                }
            } else if (currentApp == "com.example.locked" || currentApp?.startsWith("com.android.launcher") == true) {
                // User is in home or our app, remove overlay
                if (isOverlayShowing) {
                    withContext(Dispatchers.Main) {
                        removeOverlay()
                    }
                }
                lastForegroundApp = currentApp
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking apps", e)
        }
    }

    @SuppressLint("InflateParams")
    private fun showBlockingOverlay(blockedAppName: String) {
        // Remove existing overlay first
        removeOverlay()

        try {
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
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
            }

            // Inflate custom layout
            val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
            overlayView = inflater.inflate(R.layout.blocking_overlay, null)

            // Set the blocked app name
            overlayView?.findViewById<TextView>(R.id.blockedAppNameText)?.text = blockedAppName

            // Set up buttons
            overlayView?.findViewById<Button>(R.id.openLockedButton)?.setOnClickListener {
                openMainApp()
            }

            overlayView?.findViewById<Button>(R.id.goHomeButton)?.setOnClickListener {
                goToHome()
            }

            windowManager?.addView(overlayView, params)
            isOverlayShowing = true

            Log.d(TAG, "Overlay shown for: $blockedAppName")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show overlay", e)
            isOverlayShowing = false
        }
    }

    private fun removeOverlay() {
        try {
            overlayView?.let {
                windowManager?.removeView(it)
                overlayView = null
                isOverlayShowing = false
                Log.d(TAG, "Overlay removed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove overlay", e)
        }
    }

    private fun openMainApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("show_blocked_message", true)
        }
        startActivity(intent)
    }

    private fun goToHome() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(homeIntent)
        removeOverlay()
    }

    private fun getForegroundApp(): String? {
        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        if (usageStatsManager == null) {
            Log.e(TAG, "UsageStatsManager is null")
            return null
        }

        val currentTime = System.currentTimeMillis()
        val events = usageStatsManager.queryEvents(currentTime - 1000, currentTime)

        var lastResumedApp: String? = null
        val event = UsageEvents.Event()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
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