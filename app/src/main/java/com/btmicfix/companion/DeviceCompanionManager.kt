package com.btmicfix.companion

import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.IntentSender
import android.os.Build
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.core.content.getSystemService
import com.btmicfix.util.Logger
import com.btmicfix.util.Preferences

/**
 * Manages Companion Device Manager (CDM) associations.
 *
 * Release 0.5.1 keeps ONE priority device without deleting the other CDM
 * associations automatically. Old/accidental associations remain visible and can
 * be removed manually, avoiding CompanionDeviceService lifecycle side effects.
 */
class DeviceCompanionManager(private val context: Context) {

    data class AssociatedDevice(
        val associationId: Int,
        val name: String,
        val address: String?,
        val isPriority: Boolean,
    )

    private val companionDeviceManager: CompanionDeviceManager? =
        context.getSystemService<CompanionDeviceManager>()

    private val preferences = Preferences(context)

    fun isAvailable(): Boolean = companionDeviceManager != null

    fun startAssociation(
        launcher: ActivityResultLauncher<IntentSenderRequest>,
        onAssociated: () -> Unit = {},
    ) {
        val cdm = companionDeviceManager ?: run {
            Logger.e("CompanionDeviceManager not available on this device")
            return
        }

        val deviceFilter = BluetoothDeviceFilter.Builder().build()
        val associationRequest = AssociationRequest.Builder()
            .addDeviceFilter(deviceFilter)
            // false is intentional: show the picker so the user can explicitly choose Cardo.
            .setSingleDevice(false)
            .build()

        Logger.i("Starting CDM association flow")

        val callback = object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(intentSender: IntentSender) {
                Logger.i("CDM association pending, launching picker")
                launcher.launch(IntentSenderRequest.Builder(intentSender).build())
            }

            @Deprecated("Deprecated in API 33+", ReplaceWith("onAssociationCreated"))
            override fun onDeviceFound(intentSender: IntentSender) {
                Logger.i("CDM device found (legacy), launching picker")
                launcher.launch(IntentSenderRequest.Builder(intentSender).build())
            }

            override fun onAssociationCreated(associationInfo: AssociationInfo) {
                Logger.i("CDM association created: ${associationInfo.id}")
                makeExclusivePriority(associationInfo)
                onAssociated()
            }

            override fun onFailure(error: CharSequence?) {
                Logger.e("CDM association failed: $error")
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            cdm.associate(associationRequest, context.mainExecutor, callback)
        } else {
            cdm.associate(associationRequest, callback, null)
        }
    }

    fun startObservingPresence(associationInfo: AssociationInfo) {
        val cdm = companionDeviceManager ?: return
        val address = associationInfo.deviceMacAddress?.toString()
        if (address.isNullOrBlank()) {
            Logger.w("Cannot observe association ${associationInfo.id}: Bluetooth address unavailable")
            return
        }
        try {
            // The String overload expects a Bluetooth MAC address, not an association ID.
            cdm.startObservingDevicePresence(address)
            Logger.i("Started observing presence for association ${associationInfo.id}")
        } catch (e: Exception) {
            Logger.e("Failed to start observing presence", e)
        }
    }

    fun getAssociations(): List<AssociationInfo> {
        val cdm = companionDeviceManager ?: return emptyList()
        return try {
            cdm.myAssociations
        } catch (e: Exception) {
            Logger.e("Error getting associations", e)
            emptyList()
        }
    }

    fun getAssociatedDevices(): List<AssociatedDevice> {
        val preferredAddress = preferences.pairedDeviceAddress
        val preferredName = preferences.pairedDeviceName
        val associations = getAssociations()

        // Migration path: if only one legacy association exists, adopt it as priority.
        if (associations.size == 1 && preferredAddress.isNullOrBlank() && preferredName.isNullOrBlank()) {
            savePriority(associations.first())
        }

        return associations.map { info ->
            val address = info.deviceMacAddress?.toString()
            val name = info.displayName?.toString()
                ?: address
                ?: "Dispositivo sconosciuto"
            AssociatedDevice(
                associationId = info.id,
                name = name,
                address = address,
                isPriority = matchesPreference(info),
            )
        }
    }

    fun getAssociatedDeviceNames(): List<String> = getAssociatedDevices().map { it.name }

    /**
     * Make exactly one association the priority target, but do NOT delete the other
     * associations automatically. Deleting/recreating CDM associations can cause the
     * CompanionDeviceService to be rebound/destroyed and should never be coupled to
     * an audio-routing test. The UI still offers explicit per-device removal.
     */
    fun makeExclusivePriority(associationId: Int): Boolean {
        val selected = getAssociations().firstOrNull { it.id == associationId } ?: return false
        return makeExclusivePriority(selected)
    }

    private fun makeExclusivePriority(selected: AssociationInfo): Boolean {
        if (companionDeviceManager == null) return false

        val previousAddress = preferences.pairedDeviceAddress
        val selectedAddress = selected.deviceMacAddress?.toString()

        if (!previousAddress.isNullOrBlank() &&
            !selectedAddress.isNullOrBlank() &&
            !previousAddress.equals(selectedAddress, ignoreCase = true)
        ) {
            stopObservingPresence(previousAddress)
        }

        savePriority(selected)
        startObservingPresence(selected)
        Logger.i("Priority association changed to ${selected.id}; other CDM associations preserved")
        return true
    }

    private fun stopObservingPresence(address: String) {
        val cdm = companionDeviceManager ?: return
        try {
            cdm.stopObservingDevicePresence(address)
            Logger.i("Stopped observing previous priority device presence")
        } catch (e: Exception) {
            // Safe to ignore when the address was not being observed.
            Logger.d("Previous device presence was not active: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Remove one CDM association only. Bluetooth pairing itself is left untouched.
     */
    fun removeAssociation(associationId: Int): Boolean {
        val cdm = companionDeviceManager ?: return false
        val target = getAssociations().firstOrNull { it.id == associationId }
        return try {
            cdm.disassociate(associationId)
            if (target != null && matchesPreference(target)) {
                preferences.clearPairedDevice()
            }
            Logger.i("Removed CDM association $associationId")
            true
        } catch (e: Exception) {
            Logger.e("Error removing association", e)
            false
        }
    }

    fun removeAllAssociations() {
        getAssociations().forEach { removeAssociation(it.id) }
        preferences.clearPairedDevice()
    }

    /**
     * Observe ONLY the priority device. This prevents stale legacy associations
     * (for example Carplay Tracer) from triggering the routing service.
     */
    fun resumeObservingAllAssociations() {
        val associations = getAssociations()
        if (associations.isEmpty()) return

        val priority = associations.firstOrNull { matchesPreference(it) }
            ?: associations.singleOrNull()?.also { savePriority(it) }

        if (priority == null) {
            Logger.w("Multiple legacy associations found and no unambiguous priority; waiting for user selection")
            return
        }

        startObservingPresence(priority)
        Logger.i("Resumed observing priority association ${priority.id}")
    }

    fun isPriorityAssociation(associationInfo: AssociationInfo): Boolean = matchesPreference(associationInfo)

    private fun savePriority(info: AssociationInfo) {
        preferences.pairedDeviceName = info.displayName?.toString()
        preferences.pairedDeviceAddress = info.deviceMacAddress?.toString()
        Logger.i("Priority device saved: ${preferences.pairedDeviceName ?: "unnamed"}")
    }

    private fun matchesPreference(info: AssociationInfo): Boolean {
        val address = info.deviceMacAddress?.toString()
        val name = info.displayName?.toString()
        return preferences.isPreferredDevice(address, name)
    }
}
