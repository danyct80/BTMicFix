package com.btmicfix

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.btmicfix.audio.AudioRoutingManager
import com.btmicfix.audio.BluetoothStateReceiver
import com.btmicfix.companion.DeviceCompanionManager
import com.btmicfix.shizuku.ShizukuManager
import com.btmicfix.ui.screens.DetailsScreen
import com.btmicfix.ui.screens.HomeScreen
import com.btmicfix.ui.screens.SetupScreen
import com.btmicfix.ui.theme.BTMicFixTheme
import com.btmicfix.util.Logger
import com.btmicfix.util.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity(), BluetoothStateReceiver.BluetoothConnectionListener {

    private lateinit var audioRoutingManager: AudioRoutingManager
    private lateinit var shizukuManager: ShizukuManager
    private lateinit var companionManager: DeviceCompanionManager
    private lateinit var preferences: Preferences
    private val btReceiver = BluetoothStateReceiver()
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var foregroundRoutingJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        audioRoutingManager = AudioRoutingManager(this)
        shizukuManager = ShizukuManager()
        companionManager = DeviceCompanionManager(this)
        preferences = Preferences(this)

        companionManager.reconcilePriority()
        audioRoutingManager.startMonitoring()
        shizukuManager.initialize()
        companionManager.resumeObservingPriorityAssociation()

        BluetoothStateReceiver.listener = this
        registerReceiver(
            btReceiver,
            BluetoothStateReceiver.getIntentFilter(),
            Context.RECEIVER_EXPORTED,
        )

        setContent {
            BTMicFixTheme {
                var screen by remember {
                    mutableStateOf(if (preferences.setupCompleted) Screen.Home else Screen.Setup)
                }
                when (screen) {
                    Screen.Home -> HomeScreen(
                        audioRoutingManager = audioRoutingManager,
                        shizukuManager = shizukuManager,
                        companionManager = companionManager,
                        onSetupClick = { screen = Screen.Setup },
                        onDetailsClick = { screen = Screen.Details },
                        modifier = Modifier.fillMaxSize(),
                    )
                    Screen.Setup -> SetupScreen(
                        audioRoutingManager = audioRoutingManager,
                        shizukuManager = shizukuManager,
                        companionManager = companionManager,
                        preferences = preferences,
                        onBackClick = {
                            preferences.setupCompleted = true
                            screen = Screen.Home
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    Screen.Details -> DetailsScreen(
                        audioRoutingManager = audioRoutingManager,
                        shizukuManager = shizukuManager,
                        onBackClick = { screen = Screen.Home },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        companionManager.reconcilePriority()
        shizukuManager.refreshStatus()
    }

    override fun onDestroy() {
        BluetoothStateReceiver.listener = null
        try { unregisterReceiver(btReceiver) } catch (_: Exception) {}
        audioRoutingManager.stopMonitoring()
        foregroundRoutingJob?.cancel()
        foregroundRoutingJob = null
        shizukuManager.cleanup()
        activityScope.cancel()
        super.onDestroy()
    }

    override fun onBluetoothDeviceConnected(device: BluetoothDevice) {
        if (!preferences.autoRouteEnabled || !preferences.isPreferredDevice(device.address)) return
        foregroundRoutingJob?.cancel()
        foregroundRoutingJob = activityScope.launch {
            val priority = companionManager.getPriorityDevice() ?: return@launch
            audioRoutingManager.routeToPreferredBluetoothAndWait(
                priority.address,
                priority.routingName,
            )
        }
    }

    override fun onBluetoothDeviceDisconnected(device: BluetoothDevice) {
        if (!preferences.isPreferredDevice(device.address)) return
        // Cancel a foreground request that may still be waiting for Android to switch.
        // routeToPreferredBluetoothAndWait() performs its own cancellation cleanup.
        foregroundRoutingJob?.cancel()
        foregroundRoutingJob = null
        val priority = companionManager.getPriorityDevice()
        audioRoutingManager.clearRoutingIfPreferred(priority?.address, priority?.routingName)
    }

    private enum class Screen { Home, Setup, Details }
}
