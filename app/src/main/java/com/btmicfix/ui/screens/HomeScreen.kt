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
import com.btmicfix.audio.RoutingPolicy
import com.btmicfix.audio.VoiceExclusionPolicy
import com.btmicfix.companion.DeviceCompanionManager
import com.btmicfix.shizuku.ShizukuManager
import com.btmicfix.ui.components.DeviceSelector
import com.btmicfix.ui.components.ShizukuStatusCard
import com.btmicfix.ui.components.StatusCard
import com.btmicfix.ui.components.VoiceExclusionProbeCard
import com.btmicfix.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
    val requestOutstanding = audioRoutingManager.isRouteRequestOutstanding()

    var diagnosticRunning by remember { mutableStateOf(false) }
    var diagnosticStep by remember { mutableStateOf<String?>(null) }
    var diagnosticReport by remember(priorityAddress, priorityRoutingName) { mutableStateOf<String?>(null) }
    var diagnosticOk by remember(priorityAddress, priorityRoutingName) { mutableStateOf<Boolean?>(null) }
    var pendingDiagnostic by remember { mutableStateOf(false) }

    val exclusionCandidates = availableDevices.filterNot { device ->
        audioRoutingManager.matchesPreferredDevice(
            device = device,
            preferredAddress = priorityAddress,
            preferredName = priorityRoutingName,
        )
    }
    var selectedExclusionDeviceId by remember { mutableStateOf<Int?>(null) }
    var exclusionRunning by remember { mutableStateOf(false) }
    var exclusionSecondsRemaining by remember { mutableStateOf(0) }
    var exclusionReport by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(exclusionCandidates.map { it.deviceInfo.id }) {
        val currentStillExists = exclusionCandidates.any { it.deviceInfo.id == selectedExclusionDeviceId }
        if (!currentStillExists) {
            selectedExclusionDeviceId = exclusionCandidates.singleOrNull()?.deviceInfo?.id
        }
    }

    val selectedExclusionDevice = exclusionCandidates.firstOrNull {
        it.deviceInfo.id == selectedExclusionDeviceId
    }
    val selectedExclusionAddress = selectedExclusionDevice?.let {
        audioRoutingManager.resolveStableBluetoothAddress(it)
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
            val requestWasAlreadyOutstanding = audioRoutingManager.isRouteRequestOutstanding()
            val autoSuppressWasActive = audioRoutingManager.isAutoRouteSuppressedUntilDisconnect()
            try {
                lines += "Target: $priorityDisplayName"
                lines += "Policy normale: one-shot, nessun setMode, nessun route-hold"

                if (priority == null) {
                    failures++
                    lines += "Target: FAIL — nessun dispositivo prioritario"
                } else {
                    // Diagnostics must start from a clean Shizuku policy so baseline captures
                    // are not contaminated by a previous privileged force.
                    if (shizukuStatus == ShizukuManager.ShizukuStatus.READY) {
                        val privilegedReady = withContext(Dispatchers.IO) {
                            shizukuManager.awaitServiceReady()
                        }
                        if (!privilegedReady) {
                            failures++
                            lines += "Shizuku pre-clean: FAIL — servizio privilegiato non disponibile"
                            throw IllegalStateException(
                                "Shizuku autorizzato ma servizio privilegiato non pronto"
                            )
                        }
                        val preClear = withContext(Dispatchers.IO) {
                            shizukuManager.clearForcedBluetoothSco()
                        }
                        if (!preClear.contains("RESULT=CLEARED", ignoreCase = true)) {
                            failures++
                            lines += "Shizuku pre-clean: FAIL — baseline non garantita"
                            throw IllegalStateException(
                                "Policy Shizuku non ripristinabile: test audio annullati"
                            )
                        }
                    }

                    diagnosticStep = "1/9 Richiesta one-shot della route"
                    val routed = if (requestWasAlreadyOutstanding) {
                        routingState
                    } else {
                        withContext(Dispatchers.IO) {
                            audioRoutingManager.requestPreferredRoute(
                                address = priorityAddress,
                                name = priorityRoutingName,
                                displayName = priorityDisplayName,
                                trigger = RoutingPolicy.Trigger.USER_ENABLE,
                            )
                        }
                    }

                    // Give SCO/input endpoints time to settle before the first AudioRecord.
                    delay(1_500L)
                    val active = audioRoutingManager.isPreferredCommunicationDeviceActive(
                        priorityAddress,
                        priorityRoutingName,
                    )
                    if (active) {
                        lines += "Routing: PASS — ${audioRoutingManager.currentCommunicationDeviceLabel()}"
                    } else {
                        failures++
                        lines += when (routed) {
                            is RoutingState.Yielded ->
                                "Routing: YIELDED — altra sessione audio ha priorita (${routed.audioMode})"
                            is RoutingState.Failed -> "Routing: FAIL — ${routed.reason}"
                            else -> "Routing: FAIL — route prioritaria non attiva"
                        }
                    }

                    if (active) {
                        val sources = listOf(
                            MicTestSource.VOICE_COMMUNICATION,
                            MicTestSource.VOICE_RECOGNITION,
                            MicTestSource.MIC,
                        )
                        var step = 2
                        for (source in sources) {
                            diagnosticStep = "$step/9 ${source.label} — preparati, poi SILENZIO/PARLA"
                            delay(1_500L)
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

                            diagnosticStep = "$step/9 ${source.label} target esplicito — preparati, poi SILENZIO/PARLA"
                            delay(1_500L)
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

                    diagnosticStep = "8/9 Shizuku force isolato + microfono reale"
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
                            lines += "Shizuku force COMM+RECORD: ${if (forceOk) "PASS" else "FAIL"}"
                            if (!forceOk) failures++

                            if (forceOk) {
                                diagnosticStep = "8/9 Shizuku — preparati, poi SILENZIO/PARLA"
                                delay(1_500L)
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
                            lines += "Shizuku cleanup: ${if (clearOk) "PASS" else "FAIL"}"
                            if (!clearOk) failures++
                        }
                    } else if (!shizukuReady) {
                        lines += "Shizuku: SKIP — servizio non pronto (facoltativo)"
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
                if (!requestWasAlreadyOutstanding) {
                    audioRoutingManager.deactivatePreferredRoute(
                        suppressAutoRouteUntilDisconnect = autoSuppressWasActive,
                    )
                }
                diagnosticRunning = false
                diagnosticStep = null
            }
        }
    }

    fun launchInverseExclusionProbe() {
        val target = selectedExclusionDevice ?: return
        val targetAddress = selectedExclusionAddress ?: return
        if (exclusionRunning || diagnosticRunning) return

        exclusionRunning = true
        exclusionSecondsRemaining = VoiceExclusionPolicy.DEFAULT_WINDOW_MS / 1000
        exclusionReport = null

        scope.launch {
            val routeTimeline = mutableListOf<String>()
            var lastRouteLine: String? = null
            var excludedTargetBecameCurrent = false
            try {
                // The inverse experiment must not be contaminated by BTMicFix's positive route.
                audioRoutingManager.deactivatePreferredRoute(suppressAutoRouteUntilDisconnect = true)
                shizukuManager.clearForcedBluetoothScoIfApplied()

                val serviceReady = withContext(Dispatchers.IO) {
                    shizukuManager.awaitServiceReady()
                }
                if (!serviceReady) {
                    exclusionReport = "RESULT=UNAVAILABLE\nShizuku UserService non disponibile"
                    return@launch
                }

                val capabilities = withContext(Dispatchers.IO) {
                    shizukuManager.inspectVoiceExclusionCapabilities()
                }
                if (!capabilities.contains("RESULT=AVAILABLE", ignoreCase = true)) {
                    exclusionReport = capabilities
                    return@launch
                }

                val probe = async(Dispatchers.IO) {
                    shizukuManager.testVoiceDeviceExclusion(
                        publicType = target.type,
                        address = targetAddress,
                        name = target.name,
                        durationMs = VoiceExclusionPolicy.DEFAULT_WINDOW_MS,
                    )
                }

                // Ignore the first second so the timeline cannot count the pre-exclusion
                // route as a false failure while AudioPolicy is still applying the role.
                delay(1_000L)
                val remainingWindowMs = VoiceExclusionPolicy.DEFAULT_WINDOW_MS - 1_000L
                val deadline = android.os.SystemClock.elapsedRealtime() + remainingWindowMs
                exclusionSecondsRemaining = (remainingWindowMs / 1000L).toInt()
                while (!probe.isCompleted && android.os.SystemClock.elapsedRealtime() < deadline + 2_000L) {
                    val remainingMs = (deadline - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                    exclusionSecondsRemaining = ((remainingMs + 999L) / 1000L).toInt()
                    val targetIsCurrent = audioRoutingManager.isCurrentCommunicationDevice(target)
                    if (targetIsCurrent) excludedTargetBecameCurrent = true
                    val routeLine = "${audioRoutingManager.currentAudioModeLabel()} -> " +
                        audioRoutingManager.currentCommunicationDeviceLabel() +
                        if (targetIsCurrent) " [TARGET_ESCLUSO]" else ""
                    if (routeLine != lastRouteLine) {
                        routeTimeline += routeLine
                        lastRouteLine = routeLine
                    }
                    delay(250L)
                }

                val raw = probe.await()
                val targetSeenAsCurrent = excludedTargetBecameCurrent
                exclusionReport = buildString {
                    appendLine(capabilities)
                    appendLine("--- TEMPORARY EXCLUSION ---")
                    appendLine(raw)
                    appendLine("--- ROUTE TIMELINE ---")
                    if (routeTimeline.isEmpty()) appendLine("Nessun cambio route osservato")
                    else routeTimeline.forEach(::appendLine)
                    appendLine("EXCLUDED_TARGET_SEEN_AS_COMMUNICATION_DEVICE=$targetSeenAsCurrent")
                    appendLine(
                        if (targetSeenAsCurrent) {
                            "INTERPRETAZIONE=Il dispositivo escluso e comparso comunque come communication device: blocco non conclusivo"
                        } else {
                            "INTERPRETAZIONE=Durante la finestra il dispositivo escluso non e diventato communication device"
                        }
                    )
                }.trim()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                exclusionReport = "RESULT=ERROR\n${t.javaClass.simpleName}: ${t.message}"
            } finally {
                exclusionSecondsRemaining = 0
                exclusionRunning = false
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
                title = { Text("BTMicFix 0.8", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SurfaceDark,
                    titleContentColor = Purple80,
                ),
                actions = {
                    IconButton(onClick = onDetailsClick, enabled = !diagnosticRunning && !exclusionRunning) {
                        Icon(Icons.Default.Info, "Dettagli tecnici", tint = Purple80)
                    }
                    IconButton(onClick = onSetupClick, enabled = !diagnosticRunning && !exclusionRunning) {
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

            if (!requestOutstanding) {
                Button(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            audioRoutingManager.requestPreferredRoute(
                                address = priorityAddress,
                                name = priorityRoutingName,
                                displayName = priorityDisplayName,
                                trigger = RoutingPolicy.Trigger.USER_ENABLE,
                            )
                        }
                    },
                    enabled = priorityAvailable && !diagnosticRunning && !exclusionRunning,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Purple40),
                ) {
                    Icon(Icons.Default.PowerSettingsNew, null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        if (priorityAvailable) "Attiva preferenza $priorityDisplayName"
                        else "Dispositivo prioritario non disponibile"
                    )
                }
            } else {
                OutlinedButton(
                    onClick = {
                        audioRoutingManager.deactivatePreferredRoute()
                        shizukuManager.clearForcedBluetoothScoIfApplied()
                    },
                    enabled = !diagnosticRunning && !exclusionRunning,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Disattiva preferenza Bluetooth")
                }
                Text(
                    "La preferenza resta registrata, ma BTMicFix non mantiene forzatamente la route: telefono, VoIP e Gemini possono prenderne temporaneamente il controllo. L'app non esegue alcun re-routing automatico durante queste sessioni.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
                        "Una sola esecuzione: richiesta one-shot, 6 registrazioni PCM reali (3 sorgenti × 2 modalità) e test Shizuku isolato con cleanup finale. La diagnostica non usa route-hold e non imposta AudioManager.mode.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = { requestCompleteDiagnostic() },
                        enabled = priority != null && !diagnosticRunning && !exclusionRunning,
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

            VoiceExclusionProbeCard(
                candidates = exclusionCandidates,
                selectedDeviceId = selectedExclusionDeviceId,
                selectedAddress = selectedExclusionAddress,
                shizukuReady = shizukuStatus == ShizukuManager.ShizukuStatus.READY,
                running = exclusionRunning,
                secondsRemaining = exclusionSecondsRemaining,
                report = exclusionReport,
                onSelect = { selectedExclusionDeviceId = it },
                onRun = { launchInverseExclusionProbe() },
            )

            DeviceSelector(availableDevices)
            ShizukuStatusCard(shizukuManager)
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
