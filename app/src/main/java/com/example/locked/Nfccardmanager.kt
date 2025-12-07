package com.example.locked

import android.content.Context
import android.content.SharedPreferences
import android.nfc.Tag
import android.util.Log

/**
 * Manages NFC card pairing and verification
 * Ensures only the paired NFC card can lock/unlock apps
 */
class NfcCardManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "NfcCardManager"
        private const val PREFS_NAME = "nfc_card_prefs"
        private const val KEY_PAIRED_CARD_ID = "paired_card_id"
    }

    /**
     * Get the unique ID from an NFC tag
     */
    fun getTagId(tag: Tag): String {
        val id = tag.id
        return id.joinToString(":") { byte -> "%02X".format(byte) }
    }

    /**
     * Check if a card is currently paired
     */
    fun isCardPaired(): Boolean {
        return prefs.getString(KEY_PAIRED_CARD_ID, null) != null
    }

    /**
     * Get the paired card ID
     */
    fun getPairedCardId(): String? {
        return prefs.getString(KEY_PAIRED_CARD_ID, null)
    }

    /**
     * Pair a new NFC card
     * Returns true if successful
     */
    fun pairCard(tag: Tag): Boolean {
        val cardId = getTagId(tag)
        Log.d(TAG, "Pairing card with ID: $cardId")

        return prefs.edit().putString(KEY_PAIRED_CARD_ID, cardId).commit()
    }

    /**
     * Unpair the current card
     */
    fun unpairCard(): Boolean {
        Log.d(TAG, "Unpairing card")
        return prefs.edit().remove(KEY_PAIRED_CARD_ID).commit()
    }

    /**
     * Verify if a scanned tag matches the paired card
     * Returns true if the tag matches the paired card
     */
    fun verifyCard(tag: Tag): Boolean {
        val pairedId = getPairedCardId()
        if (pairedId == null) {
            Log.w(TAG, "No card paired yet")
            return false
        }

        val scannedId = getTagId(tag)
        val isMatch = scannedId == pairedId

        Log.d(TAG, "Card verification: ${if (isMatch) "MATCH" else "NO MATCH"}")
        Log.d(TAG, "Paired ID: $pairedId")
        Log.d(TAG, "Scanned ID: $scannedId")

        return isMatch
    }

    /**
     * Get a formatted display string for the paired card
     */
    fun getPairedCardDisplayId(): String? {
        val fullId = getPairedCardId() ?: return null
        // Show only last 8 characters for privacy
        return if (fullId.length > 8) {
            "***${fullId.takeLast(8)}"
        } else {
            fullId
        }
    }
}