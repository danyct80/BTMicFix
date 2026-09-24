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
 * Release 0.5 keeps ONE priority device. Selecting a new device automatically
 * removes the previous CDM associations, preventing an Android Auto head unit
 * from being treated as a microphone target by mistake.
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
     * Keep only the selected association and make it the only automatic target.
     */
    fun makeExclusivePriority(associationId: Int): Boolean {
        val selected = getAssociations().firstOrNull { it.id == associationId } ?: return false
        return makeExclusivePriority(selected)
    }

    private fun makeExclusivePriority(selected: AssociationInfo): Boolean {
        val cdm = companionDeviceManager ?: return false

        savePriority(selected)
        startObservingPresence(selected)

        getAssociations()
            .filter { it.id != selected.id }
            .forEach { old ->
                try {
                    cdm.disassociate(old.id)
                    Logger.i("Removed old CDM association ${old.id}; priority=${selected.id}")
                } catch (e: Exception) {
                    Logger.e("Failed to remove old association ${old.id}", e)
                }
            }
        return true
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
