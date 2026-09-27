package com.btmicfix

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.util.Log
import com.btmicfix.audio.AudioRoutingManager
import com.btmicfix.companion.DeviceCompanionManager
import com.btmicfix.util.Preferences

/**
 * Process-wide owner of routing state.
 * Activity and CompanionDeviceService deliberately share the SAME AudioRoutingManager so they
 * cannot issue conflicting requests or keep divergent local state.
 */
class BTMicFixApp : Application() {

    lateinit var audioRoutingManager: AudioRoutingManager
        private set
    lateinit var companionManager: DeviceCompanionManager
        private set
    lateinit var preferences: Preferences
        private set

    companion object {
        const val TAG = "BTMicFix"
        const val NOTIFICATION_CHANNEL_ID = "btmicfix_routing"
        const val NOTIFICATION_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        preferences = Preferences(this)
        companionManager = DeviceCompanionManager(this)
        audioRoutingManager = AudioRoutingManager(this)

        companionManager.reconcilePriority()
        audioRoutingManager.startMonitoring()

        Log.i(TAG, "BTMicFix process coordinator initialized")
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }

        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
