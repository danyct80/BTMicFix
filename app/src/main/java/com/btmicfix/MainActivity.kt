package com.btmicfix

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.btmicfix.shizuku.ShizukuManager
import com.btmicfix.ui.screens.HomeScreen
import com.btmicfix.ui.theme.BTMicFixTheme

class MainActivity : ComponentActivity() {
    private lateinit var shizukuManager: ShizukuManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as BTMicFixApp
        shizukuManager = ShizukuManager().also { it.initialize() }

        setContent {
            BTMicFixTheme {
                HomeScreen(
                    diagnosticsManager = app.passiveDiagnosticsManager,
                    shizukuManager = shizukuManager,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        shizukuManager.refreshStatus()
    }

    override fun onDestroy() {
        shizukuManager.cleanup()
        super.onDestroy()
    }
}
