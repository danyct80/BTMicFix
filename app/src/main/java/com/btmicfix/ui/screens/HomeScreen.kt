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
import com.btmicfix.audio.AudioRoutingManager.MicTestPhase
import com.btmicfix.audio.AudioRoutingManager.MicTestSource
import com.btmicfix.audio.AudioRoutingManager.MicTestVerdict
import com.btmicfix.audio.AudioRoutingManager.RoutingState
import com.btmicfix.companion.DeviceCompanionManager
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
    companionManager: DeviceCompanionManager,
    onSetupClick: () -> Unit,
    onDetailsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val routingState by audioRoutingManager.routingState.collectAsState()
    val availableDevices by audioRoutingManager.availableDevices.collectAsState()
    val shizukuStatus by shizukuManager.status.collectAsState()
    val serviceState by shizukuManager.serviceState.collectAsState()
    val micTestResults by audioRoutingManager.micTestResults.collectAsState()
    val micLiveLevel by audioRoutingManager.micLiveLevel.collectAsState()
    val priorityDevice = remember(availableDevices) { companionManager.getPriorityDevice() }
    val preferredAddress = priorityDevice?.address
    val preferredName = priorityDevice?.name
    val preferredAvailable = audioRoutingManager.isPreferredBluetoothAvailable(preferredAddress, preferredName)

    LaunchedEffect(preferredAddress, preferredName) {
        audioRoutingManager.clearMicDiagnostics()
        shizukuManager.clearLastForceResult()
    }

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
                onEnableRouting = {
                    audioRoutingManager.routeToPreferredBluetooth(
                        preferredAddress,
                        preferredName,
                    )
                },
                onDisableRouting = {
                    audioRoutingManager.clearRouting()
                    shizukuManager.clearForcedBluetoothSco()
                },
                onRetry = {
                    audioRoutingManager.routeToPreferredBluetooth(
                        preferredAddress,
                        preferredName,
                    )
                },
                canEnable = preferredAvailable,
                targetName = preferredName,
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
                micTestResults = micTestResults,
                micLiveLevel = micLiveLevel,
                preferredAddress = preferredAddress,
                preferredName = preferredName,
                preferredAvailable = preferredAvailable,
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
    micTestResults: Map<MicTestSource, AudioRoutingManager.MicTestResult>,
    micLiveLevel: AudioRoutingManager.MicLiveLevel?,
    preferredAddress: String?,
    preferredName: String?,
    preferredAvailable: Boolean,
    onDetailsClick: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var lockJob by remember { mutableStateOf<Job?>(null) }
    var lockActive by remember { mutableStateOf(false) }
    var runningMicTest by remember { mutableStateOf<MicTestSource?>(null) }
    var pendingMicTest by remember { mutableStateOf<MicTestSource?>(null) }
    var forceUiMessage by remember(preferredAddress, preferredName) { mutableStateOf<String?>(null) }

    val ready = shizukuStatus == ShizukuStatus.READY && serviceState == UserServiceState.READY
    val targetDeviceName = preferredName?.takeIf { it.isNotBlank() } ?: "dispositivo prioritario"

    fun launchMicTest(source: MicTestSource) {
        if (runningMicTest != null) return
        runningMicTest = source
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    // Diagnostic tests are deliberately passive in 0.5.1: pressing TEST
                    // must not change the global SCO route or re-negotiate HFP.
                    audioRoutingManager.testBluetoothMicrophone(
                        source = source,
                        preferredAddress = preferredAddress,
                        preferredName = preferredName,
                    )
                }
            } finally {
                runningMicTest = null
            }
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val source = pendingMicTest
        pendingMicTest = null
        if (granted && source != null) launchMicTest(source)
    }

    fun requestMicTest(source: MicTestSource) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            launchMicTest(source)
        } else {
            pendingMicTest = source
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    DisposableEffect(Unit) {
        onDispose { lockJob?.cancel() }
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
                enabled = ready && preferredAvailable,
                onClick = {
                    forceUiMessage = null
                    scope.launch {
                        val message = withContext(Dispatchers.IO) {
                            if (audioRoutingManager.reassertPreferredRouting(preferredAddress, preferredName)) {
                                val raw = shizukuManager.forceBluetoothSco()
                                when {
                                    raw.contains("RESULT=FAILED", ignoreCase = true) ||
                                        raw.contains("ERRORE", ignoreCase = true) -> "Forzatura Shizuku non riuscita"
                                    raw.contains("AudioSystem.setForceUse(0,3) -> 0") &&
                                        raw.contains("AudioSystem.setForceUse(2,3) -> 0") -> "Policy SCO COMMUNICATION + RECORD forzate"
                                    else -> "Forzatura Shizuku eseguita: controlla Dettagli"
                                }
                            } else {
                                "$targetDeviceName non connesso: nessuna forzatura eseguita"
                            }
                        }
                        forceUiMessage = message
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Purple40),
            ) {
                Text(
                    if (preferredAvailable) "Forza $targetDeviceName ora"
                    else if (preferredName == null) "Seleziona un dispositivo prioritario"
                    else "$targetDeviceName non connesso"
                )
            }

            OutlinedButton(
                enabled = ready && preferredAvailable,
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
                                    if (audioRoutingManager.reassertPreferredRouting(preferredAddress, preferredName)) {
                                        shizukuManager.forceBluetoothSco()
                                    }
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

            Text(
                "Diagnostica sorgente microfono",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            MicSourceTestRow(
                source = MicTestSource.VOICE_COMMUNICATION,
                targetDeviceName = targetDeviceName,
                result = micTestResults[MicTestSource.VOICE_COMMUNICATION],
                liveLevel = micLiveLevel?.takeIf { it.source == MicTestSource.VOICE_COMMUNICATION },
                running = runningMicTest == MicTestSource.VOICE_COMMUNICATION,
                enabled = runningMicTest == null && preferredAvailable,
                onTest = { requestMicTest(MicTestSource.VOICE_COMMUNICATION) },
            )

            MicSourceTestRow(
                source = MicTestSource.VOICE_RECOGNITION,
                targetDeviceName = targetDeviceName,
                result = micTestResults[MicTestSource.VOICE_RECOGNITION],
                liveLevel = micLiveLevel?.takeIf { it.source == MicTestSource.VOICE_RECOGNITION },
                running = runningMicTest == MicTestSource.VOICE_RECOGNITION,
                enabled = runningMicTest == null && preferredAvailable,
                onTest = { requestMicTest(MicTestSource.VOICE_RECOGNITION) },
            )

            MicSourceTestRow(
                source = MicTestSource.MIC,
                targetDeviceName = targetDeviceName,
                result = micTestResults[MicTestSource.MIC],
                liveLevel = micLiveLevel?.takeIf { it.source == MicTestSource.MIC },
                running = runningMicTest == MicTestSource.MIC,
                enabled = runningMicTest == null && preferredAvailable,
                onTest = { requestMicTest(MicTestSource.MIC) },
            )

            forceUiMessage?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (it.contains("non riuscita") || it.contains("non connesso")) StatusFailed else StatusActive,
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
            } else if (!preferredAvailable) {
                Text(
                    if (preferredName == null) "Seleziona prima un dispositivo prioritario in Configurazione."
                    else "$targetDeviceName non e attualmente disponibile come dispositivo Bluetooth di comunicazione.",
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusRouting,
                )
            }
        }
    }
}

@Composable
private fun MicSourceTestRow(
    source: MicTestSource,
    targetDeviceName: String,
    result: AudioRoutingManager.MicTestResult?,
    liveLevel: AudioRoutingManager.MicLiveLevel?,
    running: Boolean,
    enabled: Boolean,
    onTest: () -> Unit,
) {
    val resultColor = when (result?.verdict) {
        MicTestVerdict.PASS -> StatusActive
        MicTestVerdict.NO_AUDIO -> StatusRouting
        MicTestVerdict.WRONG_DEVICE,
        MicTestVerdict.TARGET_NOT_CONFIGURED,
        MicTestVerdict.TARGET_NOT_CONNECTED,
        MicTestVerdict.ERROR,
        MicTestVerdict.PERMISSION_REQUIRED,
        -> StatusFailed
        null -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    source.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                if (running && liveLevel != null) {
                    Text(
                        when (liveLevel.phase) {
                            MicTestPhase.CALIBRATING -> "1/2 SILENZIO — calibrazione rumore"
                            MicTestPhase.SPEAKING -> "2/2 PARLA NEL MICROFONO DI $targetDeviceName"
                            MicTestPhase.FINISHED -> "Test completato"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (liveLevel.phase == MicTestPhase.SPEAKING) StatusActive else StatusRouting,
                        fontWeight = FontWeight.SemiBold,
                    )
                } else {
                    Text(
                        result?.summary ?: "Non testato",
                        style = MaterialTheme.typography.bodySmall,
                        color = resultColor,
                    )
                }
            }

            FilledTonalButton(
                enabled = enabled,
                onClick = onTest,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            ) {
                if (running) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("TEST")
                }
            }
        }

        val meterRms = if (running && liveLevel != null) liveLevel.rms else result?.rms
        val meterThreshold = if (running && liveLevel != null) liveLevel.thresholdRms else result?.thresholdRms
        val meterDbfs = if (running && liveLevel != null) liveLevel.dbfs else result?.rmsDbfs
        val thresholdDbfs = if (running && liveLevel != null) liveLevel.thresholdDbfs else result?.thresholdDbfs

        if (meterRms != null && meterThreshold != null && meterThreshold > 0.0 && meterDbfs != null && thresholdDbfs != null) {
            val meterProgress = (meterRms / (meterThreshold * 2.0)).toFloat().coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = meterProgress,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Volume: ${"%.1f".format(meterDbfs)} dBFS  •  soglia min: ${"%.1f".format(thresholdDbfs)} dBFS  •  RMS ${"%.0f".format(meterRms)}/${"%.0f".format(meterThreshold)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (!running && result != null) {
            Text(
                "Ingresso reale: ${result.actualInput}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RoutingControlButton(
    routingState: RoutingState,
    onEnableRouting: () -> Unit,
    onDisableRouting: () -> Unit,
    onRetry: () -> Unit,
    canEnable: Boolean,
    targetName: String?,
) {
    when (routingState) {
        is RoutingState.Idle -> Button(
            onClick = onEnableRouting,
            enabled = canEnable,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Purple40),
        ) {
            Icon(Icons.Default.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                if (canEnable) "Attiva instradamento"
                else if (targetName == null) "Seleziona dispositivo prioritario"
                else "$targetName non connesso",
                style = MaterialTheme.typography.labelLarge,
            )
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
            enabled = canEnable,
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
                "Il routing standard usa setCommunicationDevice per selezionare il dispositivo Bluetooth prioritario come dispositivo di comunicazione.",
                "Il fallback Android Auto usa Shizuku per tentare di forzare le policy COMMUNICATION e RECORD su BT SCO.",
                "Il pulsante LOCK ripete entrambe le forzature per 30 secondi, utile se Android Auto sovrascrive il routing quando parte Gemini.",
                "Ogni test calibra prima il rumore (resta in silenzio), poi misura la voce e mostra volume, soglia minima e ingresso realmente usato.",
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
