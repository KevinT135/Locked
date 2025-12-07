package com.example.locked

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Fullscreen activity that blocks access to restricted apps
 * Shows a clear message and requires NFC scan to dismiss
 * Actively closes the blocked app in the background
 */
class BlockingActivity : ComponentActivity() {

    private var blockedAppName: String = ""
    private var blockedPackageName: String = ""
    private val handler = Handler(Looper.getMainLooper())
    private var killAppRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Make this activity show over lockscreen and turn screen on
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        // Keep screen on while blocking
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Make fullscreen (hide status bar and navigation)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController?.apply {
            hide(WindowInsetsCompat.Type.statusBars())
            hide(WindowInsetsCompat.Type.navigationBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // Get the blocked app info
        blockedAppName = intent.getStringExtra("blocked_app_name") ?: "This app"
        blockedPackageName = intent.getStringExtra("blocked_package_name") ?: ""

        // Aggressively kill the blocked app
        killBlockedApp()

        // Keep trying to kill it in case it restarts
        killAppRunnable = object : Runnable {
            override fun run() {
                killBlockedApp()
                handler.postDelayed(this, 500) // Check every 500ms
            }
        }
        handler.post(killAppRunnable!!)

        // Handle back button press - send user to home instead of previous app
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Go to home screen instead of returning to blocked app
                goToHomeScreen()
            }
        })

        setContent {
            BlockingScreen(
                appName = blockedAppName,
                onDismiss = {
                    // Go to main app to allow unlocking
                    val intent = Intent(this, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        putExtra("show_blocked_message", true)
                    }
                    startActivity(intent)
                    finish()
                },
                onGoHome = {
                    goToHomeScreen()
                }
            )
        }
    }

    private fun killBlockedApp() {
        if (blockedPackageName.isEmpty()) return

        try {
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

            // Kill background processes
            activityManager.killBackgroundProcesses(blockedPackageName)

            // Note: We can't force-stop apps without FORCE_STOP_PACKAGES permission
            // But killing background processes helps reduce the app's presence
        } catch (e: Exception) {
            // Ignore exceptions - not critical if this fails
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Stop the kill loop
        killAppRunnable?.let { handler.removeCallbacks(it) }
    }

    private fun goToHomeScreen() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(homeIntent)
        finish()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // User pressed home button - close this activity
        finish()
    }

    // Prevent the activity from being minimized or moved to background while blocking
    override fun onPause() {
        super.onPause()
        // If we're pausing and haven't explicitly called finish(), bring ourselves back
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val tasks = activityManager.appTasks
        if (tasks.isNotEmpty() && !isFinishing) {
            // Re-launch ourselves if we detect the blocked app trying to come forward
            handler.postDelayed({
                if (!isFinishing) {
                    val intent = Intent(this, BlockingActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        putExtra("blocked_app_name", blockedAppName)
                        putExtra("blocked_package_name", blockedPackageName)
                    }
                    startActivity(intent)
                }
            }, 100)
        }
    }
}

@Composable
fun BlockingScreen(
    appName: String,
    onDismiss: () -> Unit,
    onGoHome: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.errorContainer),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .padding(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // Lock icon
                Text(
                    text = "🔒",
                    fontSize = 80.sp
                )

                // Title
                Text(
                    text = "App Blocked",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                // App name
                Text(
                    text = appName,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center
                )

                HorizontalDivider()

                // Instructions
                Text(
                    text = "This app is currently blocked.",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = "To unlock, scan your NFC tag in the Locked app.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Action buttons
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text("Open Locked App to Unlock")
                    }

                    OutlinedButton(
                        onClick = onGoHome,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Go to Home Screen")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Tip
                Text(
                    text = "💡 Tip: Keep your NFC tag accessible for quick unlocking",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}