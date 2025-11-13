package com.example.locked

import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private var nfcAdapter: NfcAdapter? = null
    private lateinit var statusText: TextView
    private lateinit var blockedAppsText: TextView
    private var isLocked = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize NFC adapter
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        // Check if device has NFC
        if (nfcAdapter == null) {
            Toast.makeText(this, R.string.toast_nfc_unavailable, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // Initialize UI elements
        statusText = findViewById(R.id.statusText)
        blockedAppsText = findViewById(R.id.blockedAppsText)
        val selectAppsButton: Button = findViewById(R.id.selectAppsButton)
        val enableBlockingButton: Button = findViewById(R.id.enableBlockingButton)

        // Button click listeners
        selectAppsButton.setOnClickListener {
            Toast.makeText(this, R.string.toast_app_selection_soon, Toast.LENGTH_SHORT).show()
        }

        enableBlockingButton.setOnClickListener {
            Toast.makeText(this, R.string.toast_enable_accessibility, Toast.LENGTH_LONG).show()
        }

        updateUI()
    }

    override fun onResume() {
        super.onResume()
        enableNfcForegroundDispatch()
    }

    override fun onPause() {
        super.onPause()
        disableNfcForegroundDispatch()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (NfcAdapter.ACTION_TAG_DISCOVERED == intent.action ||
            NfcAdapter.ACTION_NDEF_DISCOVERED == intent.action ||
            NfcAdapter.ACTION_TECH_DISCOVERED == intent.action) {

            val tag: Tag? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
            }
            handleNfcTag(tag)
        }
    }

    private fun enableNfcForegroundDispatch() {
        val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_MUTABLE
        )
        val filters = arrayOf(IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED))
        nfcAdapter?.enableForegroundDispatch(this, pendingIntent, filters, null)
    }

    private fun disableNfcForegroundDispatch() {
        nfcAdapter?.disableForegroundDispatch(this)
    }

    private fun handleNfcTag(tag: Tag?) {
        if (tag != null) {
            isLocked = !isLocked
            updateUI()

            val messageResId = if (isLocked) R.string.toast_apps_locked else R.string.toast_apps_unlocked
            Toast.makeText(this, messageResId, Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateUI() {
        val statusResId = if (isLocked) R.string.status_locked else R.string.status_unlocked
        statusText.setText(statusResId)
    }
}