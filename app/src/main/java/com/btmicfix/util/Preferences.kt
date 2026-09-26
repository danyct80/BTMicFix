package com.btmicfix.util

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** Simple SharedPreferences wrapper for persisting user settings. */
class Preferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("btmicfix_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_PAIRED_ASSOCIATION_ID = "paired_association_id"
        private const val KEY_PAIRED_DEVICE_ADDRESS = "paired_device_address"
        private const val KEY_PAIRED_DEVICE_NAME = "paired_device_name"
        private const val KEY_AUTO_ROUTE_ENABLED = "auto_route_enabled"
        private const val KEY_SHIZUKU_FALLBACK_ENABLED = "shizuku_fallback_enabled"
        private const val KEY_SETUP_COMPLETED = "setup_completed"
        private const val NO_ASSOCIATION_ID = -1
    }

    /**
     * Stable Companion Device association id. This is the primary identity inside BTMicFix.
     * Device names are display-only and must never be used as the authoritative identity.
     */
    var pairedAssociationId: Int?
        get() = prefs.getInt(KEY_PAIRED_ASSOCIATION_ID, NO_ASSOCIATION_ID)
            .takeIf { it != NO_ASSOCIATION_ID }
        set(value) = prefs.edit {
            if (value == null) remove(KEY_PAIRED_ASSOCIATION_ID)
            else putInt(KEY_PAIRED_ASSOCIATION_ID, value)
        }

    /** Bluetooth MAC, used to match real Bluetooth connection events and AudioDeviceInfo when exposed. */
    var pairedDeviceAddress: String?
        get() = prefs.getString(KEY_PAIRED_DEVICE_ADDRESS, null)
        set(value) = prefs.edit {
            if (value.isNullOrBlank()) remove(KEY_PAIRED_DEVICE_ADDRESS)
            else putString(KEY_PAIRED_DEVICE_ADDRESS, value)
        }

    /**
     * Last known friendly name. DISPLAY CACHE ONLY.
     * It is refreshed from Android/CDM/Bluetooth and is never authoritative for identity.
     */
    var pairedDeviceName: String?
        get() = prefs.getString(KEY_PAIRED_DEVICE_NAME, null)
        set(value) = prefs.edit {
            if (value.isNullOrBlank()) remove(KEY_PAIRED_DEVICE_NAME)
            else putString(KEY_PAIRED_DEVICE_NAME, value)
        }

    var autoRouteEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_ROUTE_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_ROUTE_ENABLED, value) }

    var shizukuFallbackEnabled: Boolean
        get() = prefs.getBoolean(KEY_SHIZUKU_FALLBACK_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_SHIZUKU_FALLBACK_ENABLED, value) }

    var setupCompleted: Boolean
        get() = prefs.getBoolean(KEY_SETUP_COMPLETED, false)
        set(value) = prefs.edit { putBoolean(KEY_SETUP_COMPLETED, value) }

    fun hasPreferredDevice(): Boolean =
        pairedAssociationId != null || !pairedDeviceAddress.isNullOrBlank()

    /** Strict identity for a CDM association. */
    fun isPreferredAssociation(associationId: Int, address: String?): Boolean {
        pairedAssociationId?.let { return it == associationId }

        val preferredAddress = pairedDeviceAddress
        return !preferredAddress.isNullOrBlank() &&
            !address.isNullOrBlank() &&
            preferredAddress.equals(address, ignoreCase = true)
    }

    /** Strict identity for a BluetoothDevice connection event. Never match by a stale display name. */
    fun isPreferredDevice(address: String?, name: String? = null): Boolean {
        val preferredAddress = pairedDeviceAddress
        return !preferredAddress.isNullOrBlank() &&
            !address.isNullOrBlank() &&
            preferredAddress.equals(address, ignoreCase = true)
    }

    fun clearPairedDevice() {
        prefs.edit {
            remove(KEY_PAIRED_ASSOCIATION_ID)
            remove(KEY_PAIRED_DEVICE_ADDRESS)
            remove(KEY_PAIRED_DEVICE_NAME)
        }
    }

    /** Clears only BTMicFix configuration; Bluetooth pairings are not touched. */
    fun resetAppConfiguration() {
        prefs.edit { clear() }
    }
}
