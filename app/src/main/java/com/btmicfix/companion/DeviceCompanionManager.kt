package com.btmicfix.companion

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.btmicfix.util.Logger
import com.btmicfix.util.Preferences

/**
 * Manages Companion Device Manager (CDM) associations.
 *
 * 0.5.3 rules:
 * - exactly one explicit priority association;
 * - association id / MAC are identity, never the friendly name;
 * - friendly names are resolved live from Bluetooth when possible;
 * - no automatic adoption of a leftover association;
 * - stale priority preferences are cleared automatically.
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

    private val bluetoothAdapter: BluetoothAdapter? =
        context.getSystemService<BluetoothManager>()?.adapter

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
            @Suppress("DEPRECATION")
            cdm.associate(associationRequest, callback, null)
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

    /** Remove stale preference state left by older builds or manual association cleanup. */
    fun reconcilePriority(): AssociatedDevice? {
        val associations = getAssociations()
        if (!preferences.hasPreferredDevice()) return null

        val selected = associations.firstOrNull { info ->
            preferences.isPreferredAssociation(
                associationId = info.id,
                address = info.deviceMacAddress?.toString(),
            )
        }

        if (selected == null) {
            Logger.w("Stored priority no longer exists in CDM; clearing stale priority")
            preferences.clearPairedDevice()
            return null
        }

        savePriority(selected)
        return selected.toAssociatedDevice(isPriority = true)
    }

    fun getAssociatedDevices(): List<AssociatedDevice> {
        reconcilePriority()
        return getAssociations().map { info ->
            info.toAssociatedDevice(
                isPriority = preferences.isPreferredAssociation(
                    associationId = info.id,
                    address = info.deviceMacAddress?.toString(),
                )
            )
        }
    }

    fun getPriorityDevice(): AssociatedDevice? {
        reconcilePriority()
        return getAssociatedDevicesNoReconcile().firstOrNull { it.isPriority }
    }

    private fun getAssociatedDevicesNoReconcile(): List<AssociatedDevice> =
        getAssociations().map { info ->
            info.toAssociatedDevice(
                isPriority = preferences.isPreferredAssociation(
                    associationId = info.id,
                    address = info.deviceMacAddress?.toString(),
                )
            )
        }

    fun getAssociatedDeviceNames(): List<String> = getAssociatedDevices().map { it.name }

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

    fun removeAssociation(associationId: Int): Boolean {
        val cdm = companionDeviceManager ?: return false
        val target = getAssociations().firstOrNull { it.id == associationId }
        return try {
            cdm.disassociate(associationId)
            if (target != null && preferences.isPreferredAssociation(
                    target.id,
                    target.deviceMacAddress?.toString(),
                )
            ) {
                preferences.clearPairedDevice()
            }
            Logger.i("Removed CDM association $associationId")
            true
        } catch (e: Exception) {
            Logger.e("Error removing association", e)
            false
        }
    }

    /** Removes BTMicFix companion associations and priority state. Bluetooth pairing is untouched. */
    fun resetAssociationsAndPriority() {
        getAssociations().forEach { info ->
            try {
                companionDeviceManager?.disassociate(info.id)
            } catch (e: Exception) {
                Logger.w("Could not remove CDM association ${info.id}: ${e.javaClass.simpleName}")
            }
        }
        preferences.clearPairedDevice()
        Logger.i("BTMicFix associations and priority reset")
    }

    fun startObservingPresence(associationInfo: AssociationInfo) {
        val cdm = companionDeviceManager ?: return
        val address = associationInfo.deviceMacAddress?.toString()
        if (address.isNullOrBlank()) {
            Logger.w("Cannot observe association ${associationInfo.id}: Bluetooth address unavailable")
            return
        }
        try {
            cdm.startObservingDevicePresence(address)
            Logger.i("Started observing presence for association ${associationInfo.id}")
        } catch (e: Exception) {
            Logger.e("Failed to start observing presence", e)
        }
    }

    private fun stopObservingPresence(address: String) {
        val cdm = companionDeviceManager ?: return
        try {
            cdm.stopObservingDevicePresence(address)
            Logger.i("Stopped observing previous priority device presence")
        } catch (e: Exception) {
            Logger.d("Previous device presence was not active: ${e.javaClass.simpleName}")
        }
    }

    /** Observe only an explicit, valid priority. Never auto-select a leftover association. */
    fun resumeObservingPriorityAssociation() {
        val priority = reconcilePriority() ?: return
        val association = getAssociations().firstOrNull { it.id == priority.associationId } ?: return
        startObservingPresence(association)
        Logger.i("Resumed observing priority association ${association.id}")
    }

    // Backward-compatible method name used by older call sites.
    fun resumeObservingAllAssociations() = resumeObservingPriorityAssociation()

    fun isPriorityAssociation(associationInfo: AssociationInfo): Boolean =
        preferences.isPreferredAssociation(
            associationId = associationInfo.id,
            address = associationInfo.deviceMacAddress?.toString(),
        )

    /** Friendly name for UI only. */
    fun priorityDisplayName(): String? = getPriorityDevice()?.name

    private fun savePriority(info: AssociationInfo) {
        val address = info.deviceMacAddress?.toString()
        val name = resolveFriendlyName(info)
        preferences.pairedAssociationId = info.id
        preferences.pairedDeviceAddress = address
        preferences.pairedDeviceName = name
        Logger.i("Priority device saved: $name association=${info.id}")
    }

    private fun AssociationInfo.toAssociatedDevice(isPriority: Boolean): AssociatedDevice =
        AssociatedDevice(
            associationId = id,
            name = resolveFriendlyName(this),
            address = deviceMacAddress?.toString(),
            isPriority = isPriority,
        )

    /**
     * CDM can expose only a MAC address in its picker/AssociationInfo. Resolve the user-visible
     * Bluetooth alias/name directly from Android whenever possible, then fall back to CDM/MAC.
     */
    private fun resolveFriendlyName(info: AssociationInfo): String {
        val address = info.deviceMacAddress?.toString()
        resolveBluetoothName(address)?.let { return it }

        val cdmName = info.displayName?.toString()?.trim()
        if (!cdmName.isNullOrBlank() && !looksLikeMac(cdmName)) return cdmName

        return address ?: cdmName ?: "Dispositivo Bluetooth sconosciuto"
    }

    @Suppress("MissingPermission")
    private fun resolveBluetoothName(address: String?): String? {
        if (address.isNullOrBlank()) return null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) return null

        val adapter = bluetoothAdapter ?: return null
        return try {
            val device = adapter.bondedDevices.firstOrNull {
                it.address.equals(address, ignoreCase = true)
            } ?: adapter.getRemoteDevice(address)

            bluetoothDeviceFriendlyName(device)
        } catch (e: Exception) {
            Logger.d("Could not resolve Bluetooth name for address: ${e.javaClass.simpleName}")
            null
        }
    }

    @Suppress("MissingPermission")
    private fun bluetoothDeviceFriendlyName(device: BluetoothDevice): String? {
        val alias = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try { device.alias } catch (_: Exception) { null }
        } else null
        val name = try { device.name } catch (_: Exception) { null }
        return alias?.takeIf { it.isNotBlank() && !looksLikeMac(it) }
            ?: name?.takeIf { it.isNotBlank() && !looksLikeMac(it) }
    }

    private fun looksLikeMac(value: String): Boolean =
        Regex("(?i)^[0-9A-F]{2}(:[0-9A-F]{2}){5}$").matches(value.trim())
}
