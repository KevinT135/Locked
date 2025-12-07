package com.example.locked

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.example.locked.data.BlockedApp
import com.example.locked.data.LockedRepository
import com.example.locked.ml.RiskLevel
import com.example.locked.ml.UsagePredictor
import com.example.locked.service.AppBlockingService
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var nfcAdapter: NfcAdapter? = null
    private lateinit var repository: LockedRepository
    private lateinit var predictor: UsagePredictor
    private var isLocked by mutableStateOf(true)
    private var hasUsagePermission by mutableStateOf(false)

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(this, "Notification permission required for service", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        repository = LockedRepository(applicationContext)
        predictor = UsagePredictor(applicationContext)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        // Check permissions
        hasUsagePermission = hasUsageStatsPermission()
        Log.d(TAG, "Has usage permission: $hasUsagePermission")

        // Check if we should show blocked message
        val showBlockedMessage = intent.getBooleanExtra("show_blocked_message", false)

        // Request notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            MaterialTheme {
                LockedApp(
                    isLocked = isLocked,
                    hasUsagePermission = hasUsagePermission,
                    showBlockedMessage = showBlockedMessage,
                    onNfcScan = { handleNfcToggle() },
                    repository = repository,
                    predictor = predictor,
                    onRequestUsagePermission = { requestUsageStatsPermission() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        enableNfcForegroundDispatch()

        // Recheck permission status
        val hadPermission = hasUsagePermission
        hasUsagePermission = hasUsageStatsPermission()

        if (!hadPermission && hasUsagePermission) {
            Toast.makeText(this, "Usage permission granted! You can now lock apps.", Toast.LENGTH_LONG).show()
        }

        Log.d(TAG, "onResume - Has usage permission: $hasUsagePermission")
    }

    override fun onPause() {
        super.onPause()
        disableNfcForegroundDispatch()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (NfcAdapter.ACTION_TAG_DISCOVERED == intent.action ||
            NfcAdapter.ACTION_NDEF_DISCOVERED == intent.action) {
            val tag: Tag? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
            }
            if (tag != null) {
                handleNfcToggle()
            }
        }
    }

    private fun handleNfcToggle() {
        if (!hasUsageStatsPermission()) {
            Toast.makeText(
                this,
                "Please grant Usage Access permission first!",
                Toast.LENGTH_LONG
            ).show()
            requestUsageStatsPermission()
            return
        }

        isLocked = !isLocked
        Log.d(TAG, "NFC toggle - isLocked: $isLocked")

        val serviceIntent = Intent(this, AppBlockingService::class.java).apply {
            action = if (isLocked) AppBlockingService.ACTION_LOCK else AppBlockingService.ACTION_UNLOCK
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }

            lifecycleScope.launch {
                if (isLocked) {
                    repository.startBlockingSession()
                } else {
                    repository.endBlockingSession("nfc")
                }
            }

            val message = if (isLocked) {
                "Apps locked! Service is monitoring."
            } else {
                "Apps unlocked!"
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

            Log.d(TAG, "Service command sent: ${if (isLocked) "LOCK" else "UNLOCK"}")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting service", e)
            Toast.makeText(this, "Error starting service: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun enableNfcForegroundDispatch() {
        nfcAdapter?.let { adapter ->
            val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pendingIntent = android.app.PendingIntent.getActivity(
                this, 0, intent, android.app.PendingIntent.FLAG_MUTABLE
            )
            adapter.enableForegroundDispatch(this, pendingIntent, null, null)
        }
    }

    private fun disableNfcForegroundDispatch() {
        nfcAdapter?.disableForegroundDispatch(this)
    }

    private fun requestUsageStatsPermission() {
        if (!hasUsageStatsPermission()) {
            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

            try {
                startActivity(intent)
                Toast.makeText(
                    this,
                    "Find 'Locked' in the list and enable it",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                Toast.makeText(
                    this,
                    "Go to: Settings > Apps > Special Access > Usage Access",
                    Toast.LENGTH_LONG
                ).show()
            }
        } else {
            Toast.makeText(this, "Usage access already granted!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LockedApp(
    isLocked: Boolean,
    hasUsagePermission: Boolean,
    showBlockedMessage: Boolean,
    onNfcScan: () -> Unit,
    repository: LockedRepository,
    predictor: UsagePredictor,
    onRequestUsagePermission: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Locked - NFC App Blocker") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            // Permission warning banner
            if (!hasUsagePermission) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Permission Required",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "App blocking won't work without Usage Access",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        TextButton(onClick = onRequestUsagePermission) {
                            Text("Grant")
                        }
                    }
                }
            }

            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Status") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Select Apps") }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("Insights") }
                )
            }

            when (selectedTab) {
                0 -> StatusScreen(
                    isLocked = isLocked,
                    hasUsagePermission = hasUsagePermission,
                    showBlockedMessage = showBlockedMessage,
                    predictor = predictor,
                    onRequestPermission = onRequestUsagePermission
                )
                1 -> AppSelectionScreen(repository = repository, isLocked = isLocked)
                2 -> InsightsScreen(repository = repository)
            }
        }
    }
}

@Composable
fun StatusScreen(
    isLocked: Boolean,
    hasUsagePermission: Boolean,
    showBlockedMessage: Boolean,
    predictor: UsagePredictor,
    onRequestPermission: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var riskAssessment by remember { mutableStateOf<com.example.locked.ml.RiskAssessment?>(null) }

    LaunchedEffect(Unit) {
        scope.launch {
            try {
                riskAssessment = predictor.assessUnlockRisk()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (showBlockedMessage) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Blocked App Detected",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text("This app is currently blocked. Scan your NFC tag to unlock.")
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (isLocked)
                    MaterialTheme.colorScheme.errorContainer
                else
                    MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (isLocked) "🔒 LOCKED" else "🔓 UNLOCKED",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (isLocked)
                        "Scan NFC tag to unlock apps"
                    else
                        "Scan NFC tag to lock apps",
                    style = MaterialTheme.typography.bodyLarge
                )

                if (isLocked && hasUsagePermission) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "✓ Service monitoring active",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        riskAssessment?.let { assessment ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "AI Risk Assessment",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Risk Level:")
                        Text(
                            text = assessment.riskLevel.name,
                            color = when (assessment.riskLevel) {
                                RiskLevel.HIGH -> MaterialTheme.colorScheme.error
                                RiskLevel.MEDIUM -> MaterialTheme.colorScheme.tertiary
                                RiskLevel.LOW -> MaterialTheme.colorScheme.primary
                            },
                            fontWeight = FontWeight.Bold
                        )
                    }

                    LinearProgressIndicator(
                        progress = { assessment.riskScore },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    )

                    Text(
                        assessment.recommendation,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Setup Checklist",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (hasUsagePermission) "✅" else "❌")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("1. Enable Usage Access permission")
                }
                Text("   2. Select apps to block in 'Select Apps' tab")
                Text("   3. Scan NFC tag to lock")
                Text("   4. Try opening a blocked app to test")

                if (!hasUsagePermission) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = onRequestPermission,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Grant Usage Access Permission")
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Look for 'Locked' or 'com.example.locked' in the settings list",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun AppSelectionScreen(repository: LockedRepository, isLocked: Boolean) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val blockedApps by repository.getAllBlockedApps().collectAsState(initial = emptyList())
    var availableApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        scope.launch {
            availableApps = getInstalledApps(context)
            isLoading = false
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Warning banner when locked
        if (isLocked) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp, 8.dp, 16.dp, 0.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "⚠️ Apps are currently LOCKED",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Unlock with NFC to modify blocked apps",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        } else if (blockedApps.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp, 8.dp, 16.dp, 0.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Text(
                    "✓ ${blockedApps.size} apps will be blocked when locked",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(availableApps) { app ->
                    val isBlocked = blockedApps.any { it.packageName == app.packageName }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            if (!isLocked) {
                                scope.launch {
                                    if (isBlocked) {
                                        repository.setAppBlocked(app.packageName, false)
                                    } else {
                                        repository.addBlockedApp(
                                            packageName = app.packageName,
                                            appName = app.appName,
                                            category = app.category
                                        )
                                    }
                                }
                            } else {
                                Toast.makeText(
                                    context,
                                    "Unlock first to modify blocked apps",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        colors = CardDefaults.cardColors(
                            containerColor = if (isLocked)
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            else
                                MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    app.appName,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    color = if (isLocked)
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                    else
                                        MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    app.category,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                        alpha = if (isLocked) 0.5f else 1f
                                    )
                                )
                            }
                            Checkbox(
                                checked = isBlocked,
                                onCheckedChange = null,
                                enabled = !isLocked
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun InsightsScreen(repository: LockedRepository) {
    val recentEvents by repository.getRecentEvents(100).collectAsState(initial = emptyList())
    val recentSessions by repository.getRecentSessions(20).collectAsState(initial = emptyList())

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Usage Statistics",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Total Events Recorded: ${recentEvents.size}")
                    Text("Blocking Sessions: ${recentSessions.size}")

                    if (recentEvents.isNotEmpty()) {
                        val avgDuration = recentEvents.map { it.sessionDuration }.average() / 60000
                        Text("Avg Session: %.1f minutes".format(avgDuration))

                        val blockedCount = recentEvents.count { it.wasBlocked }
                        Text("Blocked Attempts: $blockedCount")
                    }
                }
            }
        }

        item {
            Text(
                "Recent Activity",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }

        items(recentEvents.take(10)) { event ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        event.packageName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        "Duration: ${event.sessionDuration / 1000}s | Category: ${event.appCategory}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (event.wasBlocked) {
                        Text(
                            "Blocked attempt",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

data class AppInfo(
    val packageName: String,
    val appName: String,
    val category: String
)

private fun getInstalledApps(context: Context): List<AppInfo> {
    val pm = context.packageManager
    val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)

    return packages
        .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 } // Non-system apps only
        .map { appInfo ->
            AppInfo(
                packageName = appInfo.packageName,
                appName = pm.getApplicationLabel(appInfo).toString(),
                category = getCategoryName(appInfo, pm)
            )
        }
        .sortedBy { it.appName }
}

private fun getCategoryName(appInfo: ApplicationInfo, pm: PackageManager): String {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        when (appInfo.category) {
            ApplicationInfo.CATEGORY_GAME -> "Game"
            ApplicationInfo.CATEGORY_SOCIAL -> "Social"
            ApplicationInfo.CATEGORY_VIDEO -> "Video"
            ApplicationInfo.CATEGORY_NEWS -> "News"
            ApplicationInfo.CATEGORY_PRODUCTIVITY -> "Productivity"
            else -> "Other"
        }
    } else {
        "Other"
    }
}