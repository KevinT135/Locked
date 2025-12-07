package com.example.locked.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.example.locked.BlockingActivity
import com.example.locked.data.LockedRepository
import kotlinx.coroutines.*

/**
 * AccessibilityService for real-time app blocking
 * This is the GOLD STANDARD approach used by professional parental control apps
 *
 * How it works:
 * 1. Monitors all app switches via accessibility events
 * 2. Detects when a blocked app comes to foreground
 * 3. Immediately launches blocking screen (< 100ms response time)
 * 4. Optionally navigates user to home screen
 *
 * Advantages over UsageStatsManager polling:
 * - Real-time detection (not 300ms delayed)
 * - No battery drain from polling
 * - Can interact with UI (go home, press back)
 * - Works reliably across all Android versions
 */
class AppBlockingAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var repository: LockedRepository
    private var isLocked = false
    private var lastBlockedApp: String? = null
    private var lastBlockTime: Long = 0
    private val BLOCK_COOLDOWN = 1000L // 1 second cooldown

    companion object {
        private const val TAG = "AppBlockingA11y"
        var isServiceRunning = false
        var currentInstance: AppBlockingAccessibilityService? = null

        fun setLockState(locked: Boolean) {
            currentInstance?.isLocked = locked
            Log.d(TAG, "Lock state updated: $locked")
        }

        fun isRunning(): Boolean = isServiceRunning
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        repository = LockedRepository(applicationContext)
        isServiceRunning = true
        currentInstance = this

        // Configure the service
        val info = AccessibilityServiceInfo().apply {
            // We want to detect when apps are launched
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED

            // We need to know package names
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC

            // Don't need to inspect detailed content
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS

            // No delay - respond immediately
            notificationTimeout = 0
        }

        serviceInfo = info

        Log.d(TAG, "AccessibilityService connected and configured")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Only care about window state changes (app switches)
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return
        }

        // Check if blocking is enabled
        if (!isLocked) {
            return
        }

        // Get the package name of the app that just came to foreground
        val packageName = event.packageName?.toString() ?: return

        // Ignore our own app and system UI
        if (packageName == "com.example.locked" ||
            packageName == "com.android.systemui" ||
            packageName == "android" ||
            packageName.startsWith("com.android.launcher")) {
            return
        }

        // Check cooldown to prevent spam
        val currentTime = System.currentTimeMillis()
        if (packageName == lastBlockedApp && currentTime - lastBlockTime < BLOCK_COOLDOWN) {
            return
        }

        // Check if this app is blocked
        serviceScope.launch {
            try {
                val isBlocked = repository.isAppBlocked(packageName)

                if (isBlocked) {
                    Log.d(TAG, "🚫 BLOCKING APP: $packageName")

                    lastBlockedApp = packageName
                    lastBlockTime = currentTime

                    // Record the blocking event
                    val appName = getAppName(packageName)
                    repository.recordUsageEvent(
                        packageName = packageName,
                        appName = appName,
                        category = "BLOCKED",
                        sessionDuration = 0,
                        wasBlocked = true,
                        unlockAttempted = true,
                        unlockSucceeded = false
                    )

                    // Launch blocking screen immediately
                    launchBlockingScreen(packageName, appName)

                    // Optional: Also navigate to home after a brief delay
                    // This ensures the blocked app can't stay in background
                    serviceScope.launch {
                        delay(200) // Give blocking screen time to appear
                        performGlobalAction(GLOBAL_ACTION_HOME)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking blocked app", e)
            }
        }
    }

    private fun launchBlockingScreen(packageName: String, appName: String) {
        val intent = Intent(this, BlockingActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            putExtra("blocked_app_name", appName)
            putExtra("blocked_package_name", packageName)
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

    override fun onInterrupt() {
        Log.d(TAG, "Service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        currentInstance = null
        serviceScope.cancel()
        Log.d(TAG, "Service destroyed")
    }

    /**
     * Navigate user to home screen
     * This is a powerful feature of AccessibilityService
     */
    fun goToHome() {
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    /**
     * Press back button
     */
    fun goBack() {
        performGlobalAction(GLOBAL_ACTION_BACK)
    }
}