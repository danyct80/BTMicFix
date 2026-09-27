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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.btmicfix.audio.AudioRoutingManager
import com.btmicfix.audio.AudioRoutingManager.MicRouteMode
import com.btmicfix.audio.AudioRoutingManager.MicTestPhase
import com.btmicfix.audio.AudioRoutingManager.MicTestScenario
import com.btmicfix.audio.AudioRoutingManager.MicTestSource
import com.btmicfix.audio.AudioRoutingManager.MicTestVerdict
import com.btmicfix.audio.AudioRoutingManager.RoutingState
import com.btmicfix.companion.DeviceCompanionManager
import com.btmicfix.shizuku.ShizukuManager
import com.btmicfix.ui.components.DeviceSelector
import com.btmicfix.ui.components.ShizukuStatusCard
import com.btmicfix.ui.components.StatusCard
import com.btmicfix.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val routingState by audioRoutingManager.routingState.collectAsState()
    val availableDevices by audioRoutingManager.availableDevices.collectAsState()
    val priority by companionManager.priorityDevice.collectAsState()
    val liveLevel by audioRoutingManager.micLiveLevel.collectAsState()
    val shizukuStatus by shizukuManager.status.collectAsState()

    val priorityAddress = priority?.address
    val priorityRoutingName = priority?.routingName
    val priorityDisplayName = priority?.name ?: "dispositivo prioritario"
    val priorityAvailable = audioRoutingManager.isPreferredBluetoothAvailable(
        priorityAddress,
        priorityRoutingName,
    )

    var diagnosticRunning by remember { mutableStateOf(false) }
    var diagnosticStep by remember { mutableStateOf<String?>(null) }
    var diagnosticReport by remember(priorityAddress, priorityRoutingName) { mutableStateOf<String?>(null) }
    var diagnosticOk by remember(priorityAddress, priorityRoutingName) { mutableStateOf<Boolean?>(null) }
    var pendingDiagnostic by remember { mutableStateOf(false) }
    var routeHoldRequested by remember { mutableStateOf(false) }
    var routeHoldJob by remember { mutableStateOf<Job?>(null) }

    fun startRouteHold() {
        if (priority == null) return
        routeHoldRequested = true
        routeHoldJob?.cancel()
        routeHoldJob = scope.launch {
            while (isActive && routeHoldRequested) {
                if (audioRoutingManager.isPreferredBluetoothAvailable(
                        priorityAddress,
                        priorityRoutingName,
                    )
                ) {
                    withContext(Dispatchers.IO) {
                        audioRoutingManager.routeToPreferredBluetoothAndWait(
                            priorityAddress,
                            priorityRoutingName,
                            timeoutMs = 2_500L,
                        )
                    }
                }
                delay(750L)
            }
        }
    }

    fun stopRouteHold(clearRoute: Boolean) {
        routeHoldRequested = false
        routeHoldJob?.cancel()
        routeHoldJob = null
        if (clearRoute) {
            audioRoutingManager.clearRoutingIfPreferred(priorityAddress, priorityRoutingName)
        }
    }

    DisposableEffect(priorityAddress, priorityRoutingName) {
        onDispose {
            routeHoldRequested = false
            routeHoldJob?.cancel()
            routeHoldJob = null
        }
    }

    fun launchCompleteDiagnostic() {
        if (diagnosticRunning) return
        diagnosticRunning = true
        diagnosticStep = "Preparazione…"
        diagnosticReport = null
        diagnosticOk = null
        audioRoutingManager.clearMicDiagnostics()

        scope.launch {
            val lines = mutableListOf<String>()
            var failures = 0
            val holdWasAlreadyRequested = routeHoldRequested
            if (!holdWasAlreadyRequested) startRouteHold()
            try {
                lines += "Target: $priorityDisplayName"

                if (priority == null) {
                    failures++
                    lines += "Target: FAIL — nessun dispositivo prioritario"
                } else {
                    if (shizukuStatus == ShizukuManager.ShizukuStatus.READY) {
                        val privilegedReady = withContext(Dispatchers.IO) {
                            shizukuManager.awaitServiceReady()
                        }
                        if (!privilegedReady) {
                            failures++
                            lines += "Shizuku pre-clean: FAIL — servizio privilegiato non disponibile"
                            throw IllegalStateException(
                                "Shizuku e autorizzato ma il servizio privilegiato non e pronto: baseline non garantita"
                            )
                        }
                        val preClear = withContext(Dispatchers.IO) {
                            shizukuManager.clearForcedBluetoothSco()
                        }
                        if (!preClear.contains("RESULT=CLEARED", ignoreCase = true)) {
                            failures++
                            lines += "Shizuku pre-clean: FAIL — impossibile garantire una baseline pulita"
                            throw IllegalStateException(
                                "Policy Shizuku non ripristinabile: test audio annullati per evitare risultati contaminati"
                            )
                        }
                    }

                    diagnosticStep = "1/9 Attivazione routing reale"
                    val routed = withContext(Dispatchers.IO) {
                        audioRoutingManager.routeToPreferredBluetoothAndWait(
                            priorityAddress,
                            priorityRoutingName,
                        )
                    }
                    val active = audioRoutingManager.isPreferredCommunicationDeviceActive(
                        priorityAddress,
                        priorityRoutingName,
                    )
                    if (routed is RoutingState.Active && active) {
                        lines += "Routing: PASS — ${audioRoutingManager.currentCommunicationDeviceLabel()}"
                    } else {
                        failures++
                        lines += "Routing: FAIL — ${(routed as? RoutingState.Failed)?.reason ?: "route non confermata"}"
                    }

                    if (active) {
                        val sources = listOf(
                            MicTestSource.VOICE_COMMUNICATION,
                            MicTestSource.VOICE_RECOGNITION,
                            MicTestSource.MIC,
                        )
                        var step = 2
                        for (source in sources) {
                            diagnosticStep = "$step/9 ${source.label} — preparati: prima SILENZIO, poi PARLA"
                            delay(1_000L)
                            val natural = withContext(Dispatchers.IO) {
                                audioRoutingManager.testBluetoothMicrophone(
                                    source = source,
                                    preferredAddress = priorityAddress,
                                    preferredName = priorityRoutingName,
                                    targetDisplayName = priorityDisplayName,
                                    routeMode = MicRouteMode.SYSTEM_DEFAULT,
                                )
                            }
                            if (natural.verdict != MicTestVerdict.PASS) failures++
                            lines += "${source.label} / ROUTE_ATTIVA: ${natural.verdict} — ${natural.actualInput} — RMS ${"%.0f".format(natural.rms)}"
                            step++

                            diagnosticStep = "$step/9 ${source.label} target esplicito — preparati: prima SILENZIO, poi PARLA"
                            delay(1_000L)
                            val explicit = withContext(Dispatchers.IO) {
                                audioRoutingManager.testBluetoothMicrophone(
                                    source = source,
                                    preferredAddress = priorityAddress,
                                    preferredName = priorityRoutingName,
                                    targetDisplayName = priorityDisplayName,
                                    routeMode = MicRouteMode.TARGET_PREFERRED,
                                )
                            }
                            if (explicit.verdict != MicTestVerdict.PASS) failures++
                            lines += "${source.label} / TARGET_ESPLICITO: ${explicit.verdict} — ${explicit.actualInput} — RMS ${"%.0f".format(explicit.rms)}"
                            step++
                        }
                    }

                    diagnosticStep = "8/9 Shizuku force + prova microfono reale"
                    val shizukuReady = shizukuStatus == ShizukuManager.ShizukuStatus.READY &&
                        withContext(Dispatchers.IO) { shizukuManager.awaitServiceReady(3_000L) }
                    val targetStillActive = audioRoutingManager.isPreferredCommunicationDeviceActive(
                        priorityAddress,
                        priorityRoutingName,
                    )
                    if (shizukuReady && targetStillActive) {
                        val preClear = withContext(Dispatchers.IO) { shizukuManager.clearForcedBluetoothSco() }
                        val preClearOk = preClear.contains("RESULT=CLEARED", ignoreCase = true)
                        lines += "Shizuku pre-force clean: ${if (preClearOk) "PASS" else "FAIL"}"
                        if (!preClearOk) {
                            failures++
                        } else {
                            val force = withContext(Dispatchers.IO) { shizukuManager.forceBluetoothSco() }
                            val forceOk = force.contains("RESULT=OK", ignoreCase = true)
                            val routeStillActiveAfterForce =
                                audioRoutingManager.isPreferredCommunicationDeviceActive(
                                    priorityAddress,
                                    priorityRoutingName,
                                )
                            lines += "Shizuku force COMM+RECORD: ${if (forceOk) "PASS" else "FAIL"}"
                            lines += "Route target dopo force: ${if (routeStillActiveAfterForce) "PASS" else "FAIL"}"
                            if (!forceOk || !routeStillActiveAfterForce) failures++

                            if (forceOk && routeStillActiveAfterForce) {
                                // Give AudioPolicy a settling window and the user time to prepare.
                                diagnosticStep = "8/9 Shizuku — preparati: prima SILENZIO, poi PARLA"
                                delay(1_000L)
                                val forcedMic = withContext(Dispatchers.IO) {
                                    audioRoutingManager.testBluetoothMicrophone(
                                        source = MicTestSource.VOICE_RECOGNITION,
                                        durationMs = 7_000L,
                                        preferredAddress = priorityAddress,
                                        preferredName = priorityRoutingName,
                                        targetDisplayName = priorityDisplayName,
                                        routeMode = MicRouteMode.SYSTEM_DEFAULT,
                                        scenario = MicTestScenario.SHIZUKU_FORCED,
                                    )
                                }
                                lines += "SHIZUKU / VOICE_RECOGNITION: ${forcedMic.verdict} — ${forcedMic.actualInput} — RMS ${"%.0f".format(forcedMic.rms)}"
                                if (forcedMic.verdict != MicTestVerdict.PASS) failures++
                            }

                            diagnosticStep = "9/9 Ripristino policy Shizuku"
                            val clear = withContext(Dispatchers.IO) { shizukuManager.clearForcedBluetoothSco() }
                            val clearOk = clear.contains("RESULT=CLEARED", ignoreCase = true)
                            delay(150L)
                            val routeStillActiveAfterClear =
                                audioRoutingManager.isPreferredCommunicationDeviceActive(
                                    priorityAddress,
                                    priorityRoutingName,
                                )
                            lines += "Shizuku cleanup: ${if (clearOk) "PASS" else "FAIL"}"
                            lines += "Route target dopo cleanup: ${if (routeStillActiveAfterClear) "PASS" else "FAIL"}"
                            if (!clearOk || !routeStillActiveAfterClear) failures++
                        }
                    } else if (!shizukuReady) {
                        lines += "Shizuku: SKIP — servizio non pronto (funzione facoltativa)"
                    } else {
                        failures++
                        lines += "Shizuku: NON TESTATO — route prioritaria non piu attiva"
                    }
                }

                diagnosticOk = failures == 0
                lines += if (failures == 0) "ESITO COMPLETO: PASS"
                else "ESITO COMPLETO: $failures controllo/i non superato/i"
                diagnosticReport = lines.joinToString("\n")
            } catch (cancelled: CancellationException) {
                // Do not convert lifecycle/navigation cancellation into a fake diagnostic error.
                throw cancelled
            } catch (t: Throwable) {
                diagnosticOk = false
                lines += "ERRORE SUITE: ${t.javaClass.simpleName}: ${t.message}"
                diagnosticReport = lines.joinToString("\n")
            } finally {
                if (shizukuManager.isForcedBluetoothScoApplied()) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        shizukuManager.clearForcedBluetoothScoIfApplied()
                    }
                }
                if (!holdWasAlreadyRequested) {
                    stopRouteHold(clearRoute = false)
                }
                diagnosticRunning = false
                diagnosticStep = null
            }
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val run = pendingDiagnostic
        pendingDiagnostic = false
        if (granted && run) launchCompleteDiagnostic()
    }

    fun requestCompleteDiagnostic() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            launchCompleteDiagnostic()
        } else {
            pendingDiagnostic = true
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
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
                    IconButton(onClick = onDetailsClick, enabled = !diagnosticRunning) {
                        Icon(Icons.Default.Info, "Dettagli tecnici", tint = Purple80)
                    }
                    IconButton(onClick = onSetupClick, enabled = !diagnosticRunning) {
                        Icon(Icons.Default.Settings, "Configurazione", tint = Purple80)
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
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(modifier = Modifier.height(6.dp))
            StatusCard(routingState)

            Button(
                onClick = { startRouteHold() },
                enabled = priorityAvailable && !diagnosticRunning && !routeHoldRequested &&
                    routingState !is RoutingState.Routing,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Purple40),
            ) {
                Icon(Icons.Default.PowerSettingsNew, null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    if (!priorityAvailable) "Dispositivo prioritario non disponibile"
                    else if (routeHoldRequested) "Instradamento mantenuto su $priorityDisplayName"
                    else "Attiva $priorityDisplayName"
                )
            }

            if (routingState is RoutingState.Active || routeHoldRequested) {
                OutlinedButton(
                    onClick = {
                        stopRouteHold(clearRoute = true)
                        shizukuManager.clearForcedBluetoothScoIfApplied()
                    },
                    enabled = !diagnosticRunning,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Disattiva instradamento")
                }
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
                    Text("Diagnostica completa", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Una sola esecuzione: routing reale, 6 registrazioni PCM (3 sorgenti × 2 modalità) + 1 registrazione VOICE_RECOGNITION sotto forzatura Shizuku, con cleanup finale.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = { requestCompleteDiagnostic() },
                        enabled = priority != null && !diagnosticRunning &&
                            routingState !is RoutingState.Routing,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Purple40),
                    ) {
                        Icon(Icons.Default.BugReport, null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (diagnosticRunning) "DIAGNOSTICA IN CORSO" else "ESEGUI DIAGNOSTICA COMPLETA")
                    }

                    if (diagnosticRunning) {
                        Text(
                            diagnosticStep ?: "In corso…",
                            color = StatusRouting,
                            fontWeight = FontWeight.SemiBold,
                        )
                        liveLevel?.let { level ->
                            Text(
                                when (level.phase) {
                                    MicTestPhase.CALIBRATING -> "SILENZIO — calibrazione rumore"
                                    MicTestPhase.SPEAKING -> "PARLA ORA nel microfono di $priorityDisplayName"
                                    MicTestPhase.FINISHED -> "Acquisizione completata"
                                },
                                color = if (level.phase == MicTestPhase.SPEAKING) StatusActive else StatusRouting,
                            )
                            LinearProgressIndicator(
                                progress = (level.rms / maxOf(level.thresholdRms * 2.0, 1.0))
                                    .toFloat().coerceIn(0f, 1f),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                "Livello ${"%.1f".format(level.dbfs)} dBFS • soglia ${"%.1f".format(level.thresholdDbfs)} dBFS",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }

                    diagnosticReport?.let { report ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        ) {
                            Text(
                                report,
                                modifier = Modifier.padding(10.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (diagnosticOk == true) StatusActive else StatusFailed,
                            )
                        }
                    }
                }
            }

            DeviceSelector(availableDevices)
            ShizukuStatusCard(shizukuManager)
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
