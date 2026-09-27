package com.btmicfix.companion

import android.app.Notification
import android.app.PendingIntent
import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import com.btmicfix.BTMicFixApp
import com.btmicfix.MainActivity
import com.btmicfix.audio.AudioRoutingManager
import com.btmicfix.audio.RoutingPolicy
import com.btmicfix.util.Logger
import com.btmicfix.util.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Keeps the process alive while the priority companion device is present and places ONE
 * auto-routing request when the device appears. It never re-asserts the route after another app
 * (phone, VoIP, assistant) takes temporary audio ownership.
 */
class BTCompanionService : CompanionDeviceService() {

    private lateinit var audioRoutingManager: AudioRoutingManager
    private lateinit var companionManager: DeviceCompanionManager
    private lateinit var preferences: Preferences
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var routingJob: Job? = null
    private var disappearanceJob: Job? = null
    private var stateJob: Job? = null

    private var activeAssociationId: Int? = null
    private var activeAddress: String? = null
    private var activeRoutingName: String? = null

    override fun onCreate() {
        super.onCreate()
        val app = application as BTMicFixApp
        audioRoutingManager = app.audioRoutingManager
        companionManager = app.companionManager
        preferences = app.preferences
        companionManager.reconcilePriority()
    }

    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        super.onDeviceAppeared(associationInfo)
        companionManager.reconcilePriority()
        if (!companionManager.isPriorityAssociation(associationInfo)) {
            Logger.i("Ignoring non-priority association ${associationInfo.id}")
            return
        }

        disappearanceJob?.cancel()
        disappearanceJob = null

        val priority = companionManager.getPriorityDevice() ?: return
        activeAssociationId = associationInfo.id
        activeAddress = priority.address
        activeRoutingName = priority.routingName

        if (!preferences.autoRouteEnabled || audioRoutingManager.isAutoRouteSuppressedUntilDisconnect()) {
            Logger.i("Priority device appeared, but automatic routing is disabled/suppressed")
            return
        }

        startForegroundWithNotification("Preferenza Bluetooth in preparazione…")
        stateJob?.cancel()
        stateJob = serviceScope.launch {
            audioRoutingManager.routingState.collect { state ->
                val text = when (state) {
                    is AudioRoutingManager.RoutingState.Active ->
                        "Preferenza attiva: ${state.deviceName}"
                    is AudioRoutingManager.RoutingState.Yielded ->
                        "Controllo audio ceduto temporaneamente"
                    is AudioRoutingManager.RoutingState.Requested ->
                        "Preferenza Bluetooth registrata"
                    is AudioRoutingManager.RoutingState.Failed ->
                        "Routing non riuscito: ${state.reason}"
                    AudioRoutingManager.RoutingState.Idle ->
                        "Preferenza disattivata"
                }
                updateNotification(text)
            }
        }
        routingJob?.cancel()
        routingJob = serviceScope.launch {
            // Retry only while the Bluetooth communication endpoint has not appeared yet.
            // Once Android accepts our request (ACTIVE or YIELDED), stop. No route-hold loop.
            val deadline = android.os.SystemClock.elapsedRealtime() + 15_000L
            var lastFailure = "dispositivo non ancora disponibile"
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                val result = audioRoutingManager.requestPreferredRoute(
                    address = activeAddress,
                    name = activeRoutingName,
                    displayName = priority.name,
                    trigger = RoutingPolicy.Trigger.AUTO_DEVICE_APPEARED,
                    autoRouteEnabled = true,
                    timeoutMs = 2_500L,
                )

                when (result) {
                    is AudioRoutingManager.RoutingState.Active -> {
                        updateNotification("Preferenza attiva: ${result.deviceName}")
                        return@launch
                    }
                    is AudioRoutingManager.RoutingState.Yielded -> {
                        updateNotification("Preferenza pronta — controllo temporaneamente ceduto")
                        return@launch
                    }
                    is AudioRoutingManager.RoutingState.Requested -> {
                        updateNotification("Preferenza Bluetooth registrata")
                        return@launch
                    }
                    is AudioRoutingManager.RoutingState.Failed -> {
                        lastFailure = result.reason
                        if (!result.reason.contains("non connesso", ignoreCase = true)) break
                    }
                    AudioRoutingManager.RoutingState.Idle -> Unit
                }
                delay(500L)
            }
            Logger.w("Auto-route request not placed: $lastFailure")
            updateNotification("Dispositivo presente — apri BTMicFix per attivare")
        }
    }

    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        super.onDeviceDisappeared(associationInfo)
        val wasActive = activeAssociationId == associationInfo.id
        if (!wasActive && !companionManager.isPriorityAssociation(associationInfo)) return

        disappearanceJob?.cancel()
        disappearanceJob = serviceScope.launch {
            // Companion presence may flap while SCO/profile roles change. Never clear routing here.
            delay(2_000L)
            val checkAddress = if (wasActive) activeAddress else associationInfo.deviceMacAddress?.toString()
            val checkName = if (wasActive) activeRoutingName else companionManager.getPriorityDevice()?.routingName
            val stillPresent = audioRoutingManager.isPreferredBluetoothAvailable(checkAddress, checkName) ||
                audioRoutingManager.isPreferredCommunicationDeviceActive(checkAddress, checkName)
            if (stillPresent) {
                Logger.i("Ignoring transient companion disappearance for association ${associationInfo.id}")
                return@launch
            }

            // Android automatically cancels setCommunicationDevice() when the device disconnects.
            audioRoutingManager.notePreferredDeviceDisconnected(checkAddress)
            routingJob?.cancel()
            routingJob = null
            stateJob?.cancel()
            stateJob = null
            activeAssociationId = null
            activeAddress = null
            activeRoutingName = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        routingJob?.cancel()
        routingJob = null
        disappearanceJob?.cancel()
        disappearanceJob = null
        stateJob?.cancel()
        stateJob = null
        serviceScope.cancel()
        activeAssociationId = null
        activeAddress = null
        activeRoutingName = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun startForegroundWithNotification(statusText: String) {
        val notification = buildNotification(statusText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                BTMicFixApp.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(BTMicFixApp.NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(statusText: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(BTMicFixApp.NOTIFICATION_ID, buildNotification(statusText))
    }

    private fun buildNotification(statusText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, BTMicFixApp.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("BTMicFix")
            .setContentText(statusText)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
