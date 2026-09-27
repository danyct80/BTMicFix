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
import com.btmicfix.util.Logger
import com.btmicfix.util.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Background zero-touch routing for the single priority companion device. */
class BTCompanionService : CompanionDeviceService() {

    private lateinit var audioRoutingManager: AudioRoutingManager
    private lateinit var companionManager: DeviceCompanionManager
    private lateinit var preferences: Preferences
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var routingJob: Job? = null
    private var disappearanceJob: Job? = null

    private var activeAssociationId: Int? = null
    private var activeAddress: String? = null
    private var activeRoutingName: String? = null

    override fun onCreate() {
        super.onCreate()
        audioRoutingManager = AudioRoutingManager(applicationContext)
        companionManager = DeviceCompanionManager(applicationContext)
        preferences = Preferences(applicationContext)
        companionManager.reconcilePriority()
    }

    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        super.onDeviceAppeared(associationInfo)
        companionManager.reconcilePriority()
        if (!companionManager.isPriorityAssociation(associationInfo)) {
            Logger.i("Ignoring non-priority association ${associationInfo.id}")
            return
        }
        if (!preferences.autoRouteEnabled) {
            Logger.i("Priority device appeared, but automatic routing is disabled")
            return
        }

        disappearanceJob?.cancel()
        disappearanceJob = null
        val priority = companionManager.getPriorityDevice() ?: return
        activeAssociationId = associationInfo.id
        activeAddress = priority.address
        activeRoutingName = priority.routingName

        startForegroundWithNotification("Connessione…")
        audioRoutingManager.startMonitoring()
        routingJob?.cancel()
        routingJob = serviceScope.launch {
            var lastFailure = "dispositivo non ancora disponibile"
            val availabilityDeadline = android.os.SystemClock.elapsedRealtime() + 30_000L
            var attempt = 0
            while (android.os.SystemClock.elapsedRealtime() < availabilityDeadline) {
                attempt++
                val result = audioRoutingManager.routeToPreferredBluetoothAndWait(
                    activeAddress,
                    activeRoutingName,
                    timeoutMs = 30_000L,
                )
                if (result is AudioRoutingManager.RoutingState.Active) {
                    updateNotification("Microfono instradato su ${result.deviceName}")
                    Logger.i("Background routing active at attempt $attempt")
                    return@launch
                }
                if (result is AudioRoutingManager.RoutingState.Failed) {
                    lastFailure = result.reason
                    // Retry only while Android has not exposed the BT communication endpoint.
                    // A real routing timeout/rejection is definitive for this appearance event.
                    if (!result.reason.contains("non connesso", ignoreCase = true)) break
                }
                delay(500)
            }
            updateNotification("Instradamento non riuscito — apri BTMicFix")
            Logger.w("Background routing failed: $lastFailure")
        }
    }

    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        super.onDeviceDisappeared(associationInfo)
        val wasActive = activeAssociationId == associationInfo.id
        if (!wasActive && !companionManager.isPriorityAssociation(associationInfo)) {
            return
        }

        disappearanceJob?.cancel()
        disappearanceJob = serviceScope.launch {
            // Companion presence can flap briefly while Bluetooth profiles/SCO are changing.
            // Give Android a short grace window and verify the REAL audio state before treating
            // this as a physical disappearance. Never clear a still-valid route from this callback.
            delay(2_000L)
            val checkAddress = if (wasActive) activeAddress else associationInfo.deviceMacAddress?.toString()
            val checkName = if (wasActive) activeRoutingName else companionManager.getPriorityDevice()?.routingName
            val stillPresent = audioRoutingManager.isPreferredBluetoothAvailable(checkAddress, checkName) ||
                audioRoutingManager.isPreferredCommunicationDeviceActive(checkAddress, checkName)
            if (stillPresent) {
                Logger.i("Ignoring transient companion disappearance for association ${associationInfo.id}")
                return@launch
            }

            routingJob?.cancel()
            routingJob = null
            audioRoutingManager.stopMonitoring()
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
        serviceScope.cancel()
        audioRoutingManager.stopMonitoring()
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
