package com.btmicfix

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.btmicfix.audio.AudioRoutingManager
import com.btmicfix.audio.RoutingPolicy
import com.btmicfix.companion.DeviceCompanionManager
import com.btmicfix.shizuku.ShizukuManager
import com.btmicfix.ui.screens.DetailsScreen
import com.btmicfix.ui.screens.HomeScreen
import com.btmicfix.ui.screens.SetupScreen
import com.btmicfix.ui.theme.BTMicFixTheme
import com.btmicfix.util.Preferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var audioRoutingManager: AudioRoutingManager
    private lateinit var shizukuManager: ShizukuManager
    private lateinit var companionManager: DeviceCompanionManager
    private lateinit var preferences: Preferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as BTMicFixApp
        audioRoutingManager = app.audioRoutingManager
        companionManager = app.companionManager
        preferences = app.preferences
        shizukuManager = ShizukuManager()

        companionManager.reconcilePriority()
        shizukuManager.initialize()
        companionManager.resumeObservingPriorityAssociation()

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

        // One-shot auto request only. Never react to later audio-mode/device changes here.
        val priority = companionManager.getPriorityDevice() ?: return
        if (!preferences.autoRouteEnabled || audioRoutingManager.isAutoRouteSuppressedUntilDisconnect()) return
        lifecycleScope.launch(Dispatchers.IO) {
            audioRoutingManager.requestPreferredRoute(
                address = priority.address,
                name = priority.routingName,
                displayName = priority.name,
                trigger = RoutingPolicy.Trigger.APP_RESUME,
                autoRouteEnabled = true,
            )
        }
    }

    override fun onDestroy() {
        shizukuManager.cleanup()
        super.onDestroy()
    }

    private enum class Screen { Home, Setup, Details }
}
