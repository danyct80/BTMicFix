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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Owns CDM associations and the single explicit priority device. */
class DeviceCompanionManager(private val context: Context) {

    data class AssociatedDevice(
        val associationId: Int,
        /** UI label. May be a shortened fallback when Android exposes no friendly name. */
        val name: String,
        /** Real friendly name when available; safe as a secondary routing hint. */
        val routingName: String?,
        val address: String?,
        val isPriority: Boolean,
    )

    private val companionDeviceManager: CompanionDeviceManager? =
        context.getSystemService<CompanionDeviceManager>()

    private val bluetoothAdapter: BluetoothAdapter? =
        context.getSystemService<BluetoothManager>()?.adapter

    private val preferences = Preferences(context)

    private val _priorityDevice = MutableStateFlow<AssociatedDevice?>(null)
    val priorityDevice: StateFlow<AssociatedDevice?> = _priorityDevice.asStateFlow()

    fun isAvailable(): Boolean = companionDeviceManager != null

    fun startAssociation(
        launcher: ActivityResultLauncher<IntentSenderRequest>,
        onAssociated: () -> Unit = {},
    ) {
        val cdm = companionDeviceManager ?: run {
            Logger.e("CompanionDeviceManager unavailable")
            return
        }

        val request = AssociationRequest.Builder()
            .addDeviceFilter(BluetoothDeviceFilter.Builder().build())
            .setSingleDevice(true)
            .build()

        val callback = object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(intentSender: IntentSender) {
                launcher.launch(IntentSenderRequest.Builder(intentSender).build())
            }

            @Deprecated("Legacy callback")
            override fun onDeviceFound(intentSender: IntentSender) {
                launcher.launch(IntentSenderRequest.Builder(intentSender).build())
            }

            override fun onAssociationCreated(associationInfo: AssociationInfo) {
                Logger.i("CDM association created: ${associationInfo.id}")
                makePriority(associationInfo)
                onAssociated()
            }

            override fun onFailure(error: CharSequence?) {
                Logger.e("CDM association failed: $error")
            }
        }

        cdm.associate(request, context.mainExecutor, callback)
    }

    fun getAssociations(): List<AssociationInfo> {
        val cdm = companionDeviceManager ?: return emptyList()
        return try {
            cdm.myAssociations
        } catch (e: Exception) {
            Logger.e("Error reading CDM associations", e)
            emptyList()
        }
    }

    /**
     * Validate persisted identity against CDM. No association is auto-selected just because
     * it is the only one left.
     */
    fun reconcilePriority(): AssociatedDevice? {
        if (!preferences.hasPreferredDevice()) {
            _priorityDevice.value = null
            return null
        }

        val selected = getAssociations().firstOrNull { info ->
            preferences.isPreferredAssociation(
                associationId = info.id,
                address = info.deviceMacAddress?.toString(),
            )
        }

        if (selected == null) {
            Logger.w("Stored priority is stale; clearing it")
            preferences.clearPairedDevice()
            _priorityDevice.value = null
            return null
        }

        persistIdentity(selected)
        return selected.toAssociatedDevice(isPriority = true).also {
            _priorityDevice.value = it
        }
    }

    fun getPriorityDevice(): AssociatedDevice? = reconcilePriority()

    fun getAssociatedDevices(): List<AssociatedDevice> {
        val priorityId = reconcilePriority()?.associationId
        return getAssociations().map { info ->
            info.toAssociatedDevice(isPriority = info.id == priorityId)
        }
    }

    fun getAssociatedDeviceNames(): List<String> = getAssociatedDevices().map { it.name }

    fun makeExclusivePriority(associationId: Int): Boolean {
        val selected = getAssociations().firstOrNull { it.id == associationId } ?: return false
        return makePriority(selected)
    }

    private fun makePriority(selected: AssociationInfo): Boolean {
        val previousAddress = preferences.pairedDeviceAddress
        val selectedAddress = selected.deviceMacAddress?.toString()

        if (!previousAddress.isNullOrBlank() &&
            !selectedAddress.isNullOrBlank() &&
            !previousAddress.equals(selectedAddress, ignoreCase = true)
        ) {
            stopObservingPresence(previousAddress)
        }

        persistIdentity(selected)
        _priorityDevice.value = selected.toAssociatedDevice(isPriority = true)
        startObservingPresence(selected)
        Logger.i("Priority association set to ${selected.id}")
        return true
    }

    /** Local state changes only after CDM confirms disassociation. */
    fun removeAssociation(associationId: Int): Boolean {
        val cdm = companionDeviceManager ?: return false
        val target = getAssociations().firstOrNull { it.id == associationId }
        val wasPriority = target != null && preferences.isPreferredAssociation(
            associationId = target.id,
            address = target.deviceMacAddress?.toString(),
        )

        return try {
            cdm.disassociate(associationId)
            if (wasPriority && target != null) {
                target.deviceMacAddress?.toString()?.let(::stopObservingPresence)
                preferences.clearPairedDevice()
                _priorityDevice.value = null
            }
            Logger.i("Removed CDM association $associationId")
            true
        } catch (e: Exception) {
            Logger.e("Failed to remove CDM association $associationId", e)
            false
        }
    }

    /** Removes BTMicFix CDM associations only; phone Bluetooth pairings remain untouched. */
    fun resetAssociationsAndPriority(): Boolean {
        val cdm = companionDeviceManager ?: return false
        var allRemoved = true
        getAssociations().forEach { info ->
            try {
                cdm.disassociate(info.id)
                info.deviceMacAddress?.toString()?.let(::stopObservingPresence)
            } catch (e: Exception) {
                allRemoved = false
                Logger.w("Could not remove association ${info.id}: ${e.javaClass.simpleName}")
            }
        }

        // Reconcile against what CDM actually contains after the operation. This prevents a
        // failed disassociate() from leaving a still-existing priority association orphaned.
        reconcilePriority()
        if (getAssociations().isEmpty()) {
            preferences.clearPairedDevice()
            _priorityDevice.value = null
        }
        return allRemoved
    }

    fun resumeObservingPriorityAssociation() {
        val priority = reconcilePriority() ?: return
        getAssociations().firstOrNull { it.id == priority.associationId }
            ?.let(::startObservingPresence)
    }

    // Backward-compatible name used by older call sites.
    fun resumeObservingAllAssociations() = resumeObservingPriorityAssociation()

    fun isPriorityAssociation(info: AssociationInfo): Boolean =
        preferences.isPreferredAssociation(
            associationId = info.id,
            address = info.deviceMacAddress?.toString(),
        )

    fun startObservingPresence(info: AssociationInfo) {
        val cdm = companionDeviceManager ?: return
        val address = info.deviceMacAddress?.toString() ?: return
        try {
            cdm.startObservingDevicePresence(address)
        } catch (e: Exception) {
            Logger.d("Presence observation not started: ${e.javaClass.simpleName}")
        }
    }

    private fun stopObservingPresence(address: String) {
        val cdm = companionDeviceManager ?: return
        try {
            cdm.stopObservingDevicePresence(address)
        } catch (e: Exception) {
            Logger.d("Presence observation was not active: ${e.javaClass.simpleName}")
        }
    }

    private fun persistIdentity(info: AssociationInfo) {
        val address = info.deviceMacAddress?.toString()
        val resolvedName = resolveFriendlyNameOrNull(info)
        val oldAssociationId = preferences.pairedAssociationId
        val oldAddress = preferences.pairedDeviceAddress
        val sameIdentity = oldAssociationId == info.id || (
            !oldAddress.isNullOrBlank() &&
                !address.isNullOrBlank() &&
                oldAddress.equals(address, ignoreCase = true)
            )

        preferences.pairedAssociationId = info.id
        preferences.pairedDeviceAddress = address
        preferences.pairedDeviceName = when {
            !resolvedName.isNullOrBlank() && !looksLikeMac(resolvedName) -> resolvedName
            sameIdentity -> preferences.pairedDeviceName?.takeIf { !looksLikeMac(it) }
            else -> null
        }
    }

    private fun AssociationInfo.toAssociatedDevice(isPriority: Boolean): AssociatedDevice {
        val address = deviceMacAddress?.toString()
        val liveName = resolveFriendlyNameOrNull(this)
        val cachedName = if (preferences.isPreferredAssociation(id, address)) {
            preferences.pairedDeviceName?.takeIf { !it.isNullOrBlank() && !looksLikeMac(it) }
        } else null
        val routingName = liveName ?: cachedName
        return AssociatedDevice(
            associationId = id,
            name = routingName ?: fallbackDisplayName(address),
            routingName = routingName,
            address = address,
            isPriority = isPriority,
        )
    }

    private fun resolveFriendlyNameOrNull(info: AssociationInfo): String? {
        val address = info.deviceMacAddress?.toString()
        resolveBluetoothName(address)?.let { return it }
        val cdmName = info.displayName?.toString()?.trim()
        return cdmName?.takeIf { it.isNotBlank() && !looksLikeMac(it) }
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
            bluetoothFriendlyName(device)
        } catch (e: Exception) {
            Logger.d("Could not resolve Bluetooth name: ${e.javaClass.simpleName}")
            null
        }
    }

    @Suppress("MissingPermission")
    private fun bluetoothFriendlyName(device: BluetoothDevice): String? {
        val alias = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try { device.alias } catch (_: Exception) { null }
        } else null
        val name = try { device.name } catch (_: Exception) { null }
        return alias?.trim()?.takeIf { it.isNotBlank() && !looksLikeMac(it) }
            ?: name?.trim()?.takeIf { it.isNotBlank() && !looksLikeMac(it) }
    }

    private fun fallbackDisplayName(address: String?): String =
        address?.takeLast(5)?.let { "Dispositivo Bluetooth …$it" }
            ?: "Dispositivo Bluetooth"

    private fun looksLikeMac(value: String): Boolean =
        Regex("(?i)^[0-9A-F]{2}(:[0-9A-F]{2}){5}$").matches(value.trim())
}
