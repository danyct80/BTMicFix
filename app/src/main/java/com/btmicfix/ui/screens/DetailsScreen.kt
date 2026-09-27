package com.btmicfix.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.btmicfix.audio.AudioRoutingManager
import com.btmicfix.shizuku.ShizukuManager
import com.btmicfix.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailsScreen(
    audioRoutingManager: AudioRoutingManager,
    shizukuManager: ShizukuManager,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val routingState by audioRoutingManager.routingState.collectAsState()
    val devices by audioRoutingManager.availableDevices.collectAsState()
    val results by audioRoutingManager.allMicTestResults.collectAsState()
    val shizukuResult by shizukuManager.lastForceResult.collectAsState()
    val exclusionResult by shizukuManager.lastExclusionResult.collectAsState()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Dettagli tecnici") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark),
            )
        },
        containerColor = SurfaceDark,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CodeCard("Routing state", routingState.toString())
            CodeCard("BTMicFix request outstanding", audioRoutingManager.isRouteRequestOutstanding().toString())
            CodeCard("Audio mode osservato", audioRoutingManager.currentAudioModeLabel())
            CodeCard("Communication device", audioRoutingManager.currentCommunicationDeviceLabel())
            CodeCard(
                "Communication devices disponibili",
                if (devices.isEmpty()) "Nessuno" else devices.joinToString("\n") { "${it.name} — ${it.typeLabel}" },
            )

            AudioRoutingManager.MicTestSource.entries.forEach { source ->
                AudioRoutingManager.MicRouteMode.entries.forEach { mode ->
                    val key = AudioRoutingManager.MicTestKey(
                        source,
                        mode,
                        AudioRoutingManager.MicTestScenario.BASELINE,
                    )
                    CodeCard(
                        "${source.label} / ${mode.label}",
                        results[key]?.details ?: "Non eseguito in questa sessione",
                    )
                }
            }

            val forcedKey = AudioRoutingManager.MicTestKey(
                AudioRoutingManager.MicTestSource.VOICE_RECOGNITION,
                AudioRoutingManager.MicRouteMode.SYSTEM_DEFAULT,
                AudioRoutingManager.MicTestScenario.SHIZUKU_FORCED,
            )
            CodeCard(
                "VOICE_RECOGNITION / SHIZUKU_FORCED",
                results[forcedKey]?.details ?: "Non eseguito in questa sessione",
            )
            CodeCard("Ultimo Shizuku force/clear", shizukuResult ?: "Non eseguito")
            CodeCard("Ultimo test inverso DEVICE_ROLE_DISABLED", exclusionResult ?: "Non eseguito")
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
private fun CodeCard(title: String, text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Purple80)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text,
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
