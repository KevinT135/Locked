package com.example.locked

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
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
    private lateinit var nfcCardManager: NfcCardManager

    private var isLocked by mutableStateOf(true)
    private var hasUsagePermission by mutableStateOf(false)
    private var hasOverlayPermission by mutableStateOf(false)
    private var isCardPaired by mutableStateOf(false)
    private var showPairingDialog by mutableStateOf(false)
    private var pairingMode by mutableStateOf(false)

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(this, "Notification permission recommended", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val OVERLAY_PERMISSION_REQUEST_CODE = 1234
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        repository = LockedRepository(applicationContext)
        predictor = UsagePredictor(applicationContext)
        nfcCardManager = NfcCardManager(applicationContext)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        // Check permissions and pairing status
        hasUsagePermission = hasUsageStatsPermission()
        hasOverlayPermission = canDrawOverlays()
        isCardPaired = nfcCardManager.isCardPaired()

        Log.d(TAG, "Has usage permission: $hasUsagePermission")
        Log.d(TAG, "Has overlay permission: $hasOverlayPermission")
        Log.d(TAG, "Card paired: $isCardPaired")

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
                    hasOverlayPermission = hasOverlayPermission,
                    isCardPaired = isCardPaired,
                    showBlockedMessage = showBlockedMessage,
                    showPairingDialog = showPairingDialog,
                    pairingMode = pairingMode,
                    onStartPairing = {
                        pairingMode = true
                        showPairingDialog = true
                    },
                    onCancelPairing = {
                        pairingMode = false
                        showPairingDialog = false
                    },
                    onUnpairCard = { handleUnpairCard() },
                    repository = repository,
                    predictor = predictor,
                    nfcCardManager = nfcCardManager,
                    onRequestUsagePermission = { requestUsageStatsPermission() },
                    onRequestOverlayPermission = { requestOverlayPermission() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        enableNfcForegroundDispatch()

        // Recheck permission status
        val hadUsagePermission = hasUsagePermission
        val hadOverlayPermission = hasOverlayPermission

        hasUsagePermission = hasUsageStatsPermission()
        hasOverlayPermission = canDrawOverlays()
        isCardPaired = nfcCardManager.isCardPaired()

        if (!hadUsagePermission && hasUsagePermission) {
            Toast.makeText(this, "✓ Usage permission granted!", Toast.LENGTH_SHORT).show()
        }

        if (!hadOverlayPermission && hasOverlayPermission) {
            Toast.makeText(this, "✓ Overlay permission granted!", Toast.LENGTH_SHORT).show()
        }

        Log.d(TAG, "onResume - Usage: $hasUsagePermission, Overlay: $hasOverlayPermission, Card: $isCardPaired")
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
                handleNfcScan(tag)
            }
        }
    }

    private fun handleNfcScan(tag: Tag) {
        // If in pairing mode, pair the card
        if (pairingMode) {
            handlePairCard(tag)
            return
        }

        // Check if card is paired
        if (!isCardPaired) {
            Toast.makeText(
                this,
                "No card paired! Please pair an NFC card first.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        // Verify the card matches
        if (!nfcCardManager.verifyCard(tag)) {
            Toast.makeText(
                this,
                "⚠️ Wrong NFC card! Only your paired card can unlock.",
                Toast.LENGTH_LONG
            ).show()
            Log.w(TAG, "Card verification failed - wrong card used")
            return
        }

        // Card verified, proceed with toggle
        handleNfcToggle()
    }

    private fun handlePairCard(tag: Tag) {
        val success = nfcCardManager.pairCard(tag)

        if (success) {
            isCardPaired = true
            pairingMode = false
            showPairingDialog = false

            val cardId = nfcCardManager.getPairedCardDisplayId()
            Toast.makeText(
                this,
                "✓ NFC card paired successfully!\nCard ID: $cardId",
                Toast.LENGTH_LONG
            ).show()

            Log.d(TAG, "Card paired successfully")
        } else {
            Toast.makeText(
                this,
                "Failed to pair card. Please try again.",
                Toast.LENGTH_SHORT
            ).show()

            Log.e(TAG, "Failed to pair card")
        }
    }

    private fun handleUnpairCard() {
        val success = nfcCardManager.unpairCard()

        if (success) {
            isCardPaired = false
            isLocked = true

            // Stop the service if running
            val serviceIntent = Intent(this, AppBlockingService::class.java)
            stopService(serviceIntent)

            Toast.makeText(
                this,
                "Card unpaired. You'll need to pair a new card.",
                Toast.LENGTH_SHORT
            ).show()

            Log.d(TAG, "Card unpaired")
        } else {
            Toast.makeText(
                this,
                "Failed to unpair card",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun handleNfcToggle() {
        // Check all required permissions
        if (!hasUsageStatsPermission()) {
            Toast.makeText(
                this,
                "Please grant Usage Access permission first!",
                Toast.LENGTH_LONG
            ).show()
            requestUsageStatsPermission()
            return
        }

        if (!canDrawOverlays()) {
            Toast.makeText(
                this,
                "Please grant Display Over Other Apps permission!",
                Toast.LENGTH_LONG
            ).show()
            requestOverlayPermission()
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
                "🔒 Apps locked! Overlay blocking active."
            } else {
                "🔓 Apps unlocked!"
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

            Log.d(TAG, "Service command sent: ${if (isLocked) "LOCK" else "UNLOCK"}")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting service", e)
            Toast.makeText(this, "Error starting service: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                try {
                    startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST_CODE)
                    Toast.makeText(
                        this,
                        "Please enable 'Display over other apps' permission",
                        Toast.LENGTH_LONG
                    ).show()
                } catch (e: Exception) {
                    Toast.makeText(
                        this,
                        "Go to: Settings > Apps > Special Access > Display over other apps",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } else {
                Toast.makeText(this, "Overlay permission already granted!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun canDrawOverlays(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
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
    hasOverlayPermission: Boolean,
    isCardPaired: Boolean,
    showBlockedMessage: Boolean,
    showPairingDialog: Boolean,
    pairingMode: Boolean,
    onStartPairing: () -> Unit,
    onCancelPairing: () -> Unit,
    onUnpairCard: () -> Unit,
    repository: LockedRepository,
    predictor: UsagePredictor,
    nfcCardManager: NfcCardManager,
    onRequestUsagePermission: () -> Unit,
    onRequestOverlayPermission: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) }

    // Show pairing dialog
    if (showPairingDialog) {
        PairingDialog(
            onDismiss = onCancelPairing
        )
    }

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
            // Card pairing warning banner
            if (!isCardPaired) {
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
                                "No NFC Card Paired",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "Pair an NFC card to enable locking",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Button(onClick = onStartPairing) {
                            Text("Pair Card")
                        }
                    }
                }
            }

            // Overlay permission warning banner
            if (!hasOverlayPermission) {
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
                                "Overlay Permission Required",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "Required to show blocking screen",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Button(onClick = onRequestOverlayPermission) {
                            Text("Grant")
                        }
                    }
                }
            }

            // Usage permission warning banner
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
                                "Usage Access Required",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "Required to detect app usage",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Button(onClick = onRequestUsagePermission) {
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
                    hasOverlayPermission = hasOverlayPermission,
                    isCardPaired = isCardPaired,
                    showBlockedMessage = showBlockedMessage,
                    predictor = predictor,
                    nfcCardManager = nfcCardManager,
                    onRequestUsagePermission = onRequestUsagePermission,
                    onRequestOverlayPermission = onRequestOverlayPermission,
                    onStartPairing = onStartPairing,
                    onUnpairCard = onUnpairCard
                )
                1 -> AppSelectionScreen(repository = repository, isLocked = isLocked)
                2 -> InsightsScreen(repository = repository)
            }
        }
    }
}

@Composable
fun PairingDialog(
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Text("📱", style = MaterialTheme.typography.headlineLarge) },
        title = { Text("Pair NFC Card") },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Hold your NFC card near the back of your phone to pair it.",
                    style = MaterialTheme.typography.bodyMedium
                )

                CircularProgressIndicator(modifier = Modifier.padding(16.dp))

                Text(
                    "Waiting for NFC card...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    "⚠️ Only this specific card will work after pairing",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun StatusScreen(
    isLocked: Boolean,
    hasUsagePermission: Boolean,
    hasOverlayPermission: Boolean,
    isCardPaired: Boolean,
    showBlockedMessage: Boolean,
    predictor: UsagePredictor,
    nfcCardManager: NfcCardManager,
    onRequestUsagePermission: () -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onStartPairing: () -> Unit,
    onUnpairCard: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var riskAssessment by remember { mutableStateOf<com.example.locked.ml.RiskAssessment?>(null) }
    var showUnpairDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        scope.launch {
            try {
                riskAssessment = predictor.assessUnlockRisk()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // Unpair confirmation dialog
    if (showUnpairDialog) {
        AlertDialog(
            onDismissRequest = { showUnpairDialog = false },
            title = { Text("Unpair NFC Card?") },
            text = {
                Text("This will remove the current paired card. You'll need to pair a new card to use the app.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        onUnpairCard()
                        showUnpairDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Unpair")
                }
            },
            dismissButton = {
                TextButton(onClick = { showUnpairDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (showBlockedMessage) {
            item {
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
                        Text("This app is currently blocked. Scan your paired NFC card to unlock.")
                    }
                }
            }
        }

        // NFC Card Status
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "NFC Card Status",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )

                        if (isCardPaired) {
                            Text(
                                "✓ Paired",
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        } else {
                            Text(
                                "Not Paired",
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    HorizontalDivider()

                    if (isCardPaired) {
                        val cardId = nfcCardManager.getPairedCardDisplayId()
                        Text(
                            "Paired Card ID: $cardId",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { showUnpairDialog = true },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Unpair Card")
                            }
                        }
                    } else {
                        Text(
                            "No card paired. Pair an NFC card to enable locking.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Button(
                            onClick = onStartPairing,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Pair NFC Card")
                        }
                    }
                }
            }
        }

        // Lock Status
        item {
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
                            "Scan your paired NFC card to unlock apps"
                        else
                            "Scan your paired NFC card to lock apps",
                        style = MaterialTheme.typography.bodyLarge
                    )

                    if (isLocked && hasUsagePermission && hasOverlayPermission && isCardPaired) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "✓ Overlay blocking active",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }

        // Risk Assessment
        riskAssessment?.let { assessment ->
            item {
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
        }

        // Setup Checklist
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Setup Checklist",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (isCardPaired) "✅" else "❌")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("1. Pair an NFC card")
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (hasOverlayPermission) "✅" else "❌")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("2. Enable Display Over Other Apps")
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (hasUsagePermission) "✅" else "❌")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("3. Enable Usage Access permission")
                    }

                    Text("   4. Select apps to block in 'Select Apps' tab")
                    Text("   5. Scan your NFC card to lock")
                    Text("   6. Try opening a blocked app to test")

                    val allPermissionsGranted = hasOverlayPermission && hasUsagePermission

                    if (!allPermissionsGranted) {
                        Spacer(modifier = Modifier.height(16.dp))

                        if (!hasOverlayPermission) {
                            Button(
                                onClick = onRequestOverlayPermission,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Grant Overlay Permission")
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        if (!hasUsagePermission) {
                            Button(
                                onClick = onRequestUsagePermission,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Grant Usage Access Permission")
                            }
                        }
                    }
                }
            }
        }
    }
}

// Keep the same AppSelectionScreen and InsightsScreen composables from before
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
        .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
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