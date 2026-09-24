package com.btmicfix.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.btmicfix.audio.AudioRoutingManager
import com.btmicfix.audio.AudioRoutingManager.RoutingState
import com.btmicfix.shizuku.ShizukuManager
import com.btmicfix.shizuku.ShizukuManager.ShizukuStatus
import com.btmicfix.shizuku.ShizukuManager.UserServiceState
import com.btmicfix.ui.components.DeviceSelector
import com.btmicfix.ui.components.ShizukuStatusCard
import com.btmicfix.ui.components.StatusCard
import com.btmicfix.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    audioRoutingManager: AudioRoutingManager,
    shizukuManager: ShizukuManager,
    onSetupClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val routingState by audioRoutingManager.routingState.collectAsState()
    val availableDevices by audioRoutingManager.availableDevices.collectAsState()
    val shizukuStatus by shizukuManager.status.collectAsState()
    val serviceState by shizukuManager.serviceState.collectAsState()
    val lastForceResult by shizukuManager.lastForceResult.collectAsState()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("BTMicFix", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SurfaceDark,
                    titleContentColor = Purple80,
                ),
                actions = {
                    IconButton(onClick = onSetupClick) {
                        Icon(Icons.Default.Settings, contentDescription = "Configurazione", tint = Purple80)
                    }
                },
            )
        },
        containerColor = SurfaceDark,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            StatusCard(routingState = routingState)

            RoutingControlButton(
                routingState = routingState,
                onEnableRouting = { audioRoutingManager.routeToFirstAvailableBluetooth() },
                onDisableRouting = {
                    audioRoutingManager.clearRouting()
                    shizukuManager.clearForcedBluetoothSco()
                },
                onRetry = { audioRoutingManager.routeToFirstAvailableBluetooth() },
            )

            DeviceSelector(
                devices = availableDevices,
                onDeviceSelected = { audioRoutingManager.routeToBluetooth(it.deviceInfo) },
            )

            ShizukuStatusCard(shizukuManager = shizukuManager)

            AndroidAutoForceCard(
                audioRoutingManager = audioRoutingManager,
                shizukuManager = shizukuManager,
                shizukuStatus = shizukuStatus,
                serviceState = serviceState,
                lastForceResult = lastForceResult,
            )

            HowItWorksCard()
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AndroidAutoForceCard(
    audioRoutingManager: AudioRoutingManager,
    shizukuManager: ShizukuManager,
    shizukuStatus: ShizukuStatus,
    serviceState: UserServiceState,
    lastForceResult: String?,
) {
    val scope = rememberCoroutineScope()
    var lockJob by remember { mutableStateOf<Job?>(null) }
    var lockActive by remember { mutableStateOf(false) }
    var localMessage by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        onDispose { lockJob?.cancel() }
    }

    val ready = shizukuStatus == ShizukuStatus.READY && serviceState == UserServiceState.READY

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Android Auto / Shizuku",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "Fallback sperimentale: seleziona il Cardo con l'API Android e forza COMMUNICATION + RECORD su Bluetooth SCO tramite Shizuku.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Button(
                enabled = ready,
                onClick = {
                    scope.launch {
                        localMessage = withContext(Dispatchers.IO) {
                            audioRoutingManager.reassertCurrentRouting()
                            shizukuManager.forceBluetoothSco()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Purple40),
            ) {
                Text("FORZA CARDO ORA (SHIZUKU)")
            }

            OutlinedButton(
                enabled = ready,
                onClick = {
                    if (lockActive) {
                        lockJob?.cancel()
                        lockJob = null
                        lockActive = false
                        scope.launch(Dispatchers.IO) { shizukuManager.clearForcedBluetoothSco() }
                    } else {
                        lockActive = true
                        lockJob = scope.launch(Dispatchers.IO) {
                            try {
                                repeat(60) { // 30 seconds at 500 ms
                                    audioRoutingManager.reassertCurrentRouting()
                                    shizukuManager.forceBluetoothSco()
                                    delay(500)
                                }
                            } finally {
                                withContext(Dispatchers.Main) { lockActive = false }
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (lockActive) "FERMA LOCK" else "LOCK ROUTING PER 30 SECONDI")
            }

            Text(
                "Communication device: ${audioRoutingManager.currentCommunicationDeviceLabel()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val message = localMessage ?: lastForceResult
            if (!message.isNullOrBlank()) {
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (message.contains("FAILED") || message.contains("ERRORE")) StatusFailed else StatusActive,
                )
            }

            if (!ready) {
                Text(
                    "Shizuku deve risultare READY e il servizio privilegiato deve essere connesso.",
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusRouting,
                )
            }
        }
    }
}

@Composable
private fun RoutingControlButton(
    routingState: RoutingState,
    onEnableRouting: () -> Unit,
    onDisableRouting: () -> Unit,
    onRetry: () -> Unit,
) {
    when (routingState) {
        is RoutingState.Idle -> Button(
            onClick = onEnableRouting,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Purple40),
        ) {
            Icon(Icons.Default.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Attiva instradamento", style = MaterialTheme.typography.labelLarge)
        }

        is RoutingState.Routing -> Button(
            onClick = {},
            enabled = false,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Instradamento…")
        }

        is RoutingState.Active -> OutlinedButton(
            onClick = onDisableRouting,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusActive),
        ) {
            Icon(Icons.Default.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Disattiva instradamento", style = MaterialTheme.typography.labelLarge)
        }

        is RoutingState.Failed -> Button(
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = StatusFailed.copy(alpha = 0.8f)),
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Riprova", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun HowItWorksCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Come funziona", style = MaterialTheme.typography.titleMedium)
            val steps = listOf(
                "Il routing standard usa setCommunicationDevice per selezionare il Cardo come dispositivo di comunicazione.",
                "Il fallback Android Auto usa Shizuku per tentare di forzare le policy COMMUNICATION e RECORD su BT SCO.",
                "Il pulsante LOCK ripete entrambe le forzature per 30 secondi, utile se Android Auto sovrascrive il routing quando parte Gemini.",
                "Il risultato Shizuku indica esplicitamente se il ROM ha accettato o rifiutato la forzatura privilegiata.",
            )
            steps.forEachIndexed { index, step ->
                Row {
                    Text("${index + 1}.", color = Purple40, fontWeight = FontWeight.Bold, modifier = Modifier.width(20.dp))
                    Text(step, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
