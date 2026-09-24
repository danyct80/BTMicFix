package com.btmicfix.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.btmicfix.audio.AudioRoutingManager
import com.btmicfix.audio.AudioRoutingManager.MicTestVerdict
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
    onDetailsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val routingState by audioRoutingManager.routingState.collectAsState()
    val availableDevices by audioRoutingManager.availableDevices.collectAsState()
    val shizukuStatus by shizukuManager.status.collectAsState()
    val serviceState by shizukuManager.serviceState.collectAsState()
    val lastForceResult by shizukuManager.lastForceResult.collectAsState()
    val lastMicTestResult by audioRoutingManager.lastMicTestResult.collectAsState()

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

            AndroidAutoToolsCard(
                audioRoutingManager = audioRoutingManager,
                shizukuManager = shizukuManager,
                shizukuStatus = shizukuStatus,
                serviceState = serviceState,
                lastForceResult = lastForceResult,
                lastMicTestResult = lastMicTestResult,
                onDetailsClick = onDetailsClick,
            )

            HowItWorksCard()
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/**
 * Compact Android Auto tools card.
 * Raw diagnostics are deliberately hidden from the home screen and moved to DetailsScreen.
 */
@Composable
private fun AndroidAutoToolsCard(
    audioRoutingManager: AudioRoutingManager,
    shizukuManager: ShizukuManager,
    shizukuStatus: ShizukuStatus,
    serviceState: UserServiceState,
    lastForceResult: String?,
    lastMicTestResult: AudioRoutingManager.MicTestResult?,
    onDetailsClick: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var lockJob by remember { mutableStateOf<Job?>(null) }
    var lockActive by remember { mutableStateOf(false) }
    var micTestRunning by remember { mutableStateOf(false) }

    val ready = shizukuStatus == ShizukuStatus.READY && serviceState == UserServiceState.READY

    fun launchMicTest() {
        if (micTestRunning) return
        micTestRunning = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    audioRoutingManager.reassertCurrentRouting()
                    if (ready) shizukuManager.forceBluetoothSco()
                    audioRoutingManager.testBluetoothMicrophone()
                }
            } finally {
                micTestRunning = false
            }
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) launchMicTest()
    }

    DisposableEffect(Unit) {
        onDispose { lockJob?.cancel() }
    }

    val forceSummary = when {
        lastForceResult.isNullOrBlank() -> null
        lastForceResult.contains("RESULT=FAILED", ignoreCase = true) ||
            lastForceResult.contains("ERRORE", ignoreCase = true) -> "Forzatura Shizuku non riuscita"
        lastForceResult.contains("AudioSystem.setForceUse(0,3) -> 0") &&
            lastForceResult.contains("AudioSystem.setForceUse(2,3) -> 0") -> "Policy SCO COMMUNICATION + RECORD forzate"
        else -> "Forzatura Shizuku eseguita: controlla Dettagli"
    }

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
                audioRoutingManager.currentCommunicationDeviceLabel(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Button(
                enabled = ready,
                onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            audioRoutingManager.reassertCurrentRouting()
                            shizukuManager.forceBluetoothSco()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Purple40),
            ) {
                Text("Forza Cardo ora")
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
                                repeat(60) {
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
                Text(if (lockActive) "Ferma lock" else "Lock routing per 30 secondi")
            }

            FilledTonalButton(
                enabled = !micTestRunning,
                onClick = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        launchMicTest()
                    } else {
                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (micTestRunning) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Parla nel Cardo…")
                } else {
                    Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Test microfono Cardo (6 s)")
                }
            }

            forceSummary?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (it.contains("non riuscita")) StatusFailed else StatusActive,
                )
            }

            lastMicTestResult?.let { result ->
                Text(
                    result.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = when (result.verdict) {
                        MicTestVerdict.PASS -> StatusActive
                        MicTestVerdict.NO_AUDIO -> StatusRouting
                        MicTestVerdict.WRONG_DEVICE,
                        MicTestVerdict.ERROR,
                        MicTestVerdict.PERMISSION_REQUIRED,
                        -> StatusFailed
                    },
                )
            }

            OutlinedButton(
                onClick = onDetailsClick,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.BugReport, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Dettagli tecnici")
            }

            if (!ready) {
                Text(
                    "Per le forzature avanzate Shizuku deve essere pronto e autorizzato.",
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
                "Il test microfono apre un ingresso VOICE_COMMUNICATION e verifica quale microfono Android usa davvero.",
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
