package com.btmicfix.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.btmicfix.diagnostics.PassiveDiagnosticsManager
import com.btmicfix.diagnostics.PassiveObservationLogic
import com.btmicfix.shizuku.ShizukuManager
import com.btmicfix.ui.components.ShizukuStatusCard
import com.btmicfix.ui.theme.StatusActive
import com.btmicfix.ui.theme.StatusRouting
import com.btmicfix.ui.theme.SurfaceCard
import com.btmicfix.ui.theme.SurfaceDark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    diagnosticsManager: PassiveDiagnosticsManager,
    shizukuManager: ShizukuManager,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val running by diagnosticsManager.running.collectAsState()
    val live by diagnosticsManager.liveState.collectAsState()
    val report by diagnosticsManager.lastReport.collectAsState()
    val shizukuStatus by shizukuManager.status.collectAsState()

    var pendingStart by remember { mutableStateOf(false) }
    val bluetoothPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (pendingStart) {
            pendingStart = false
            // The test is valid even without this permission; it only enriches BT profile labels.
            launchObservation(scope, diagnosticsManager, shizukuManager, shizukuStatus)
        }
    }

    fun startObservation() {
        if (running) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingStart = true
            bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            launchObservation(scope, diagnosticsManager, shizukuManager, shizukuStatus)
        }
    }

    val phase = live?.phase ?: PassiveObservationLogic.Phase.BASELINE
    val cue = if (running) PassiveObservationLogic.cueFor(phase) else "Pronto per un test completamente passivo"
    val remaining = live?.let { PassiveObservationLogic.secondsRemaining(it.elapsedMs) } ?: 20
    val cueColor = when (phase) {
        PassiveObservationLogic.Phase.GEMINI -> StatusActive
        PassiveObservationLogic.Phase.RECOVERY -> StatusRouting
        else -> MaterialTheme.colorScheme.onSurface
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("BTMicFix 0.9 — Passive Observer", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark),
            )
        },
        containerColor = SurfaceDark,
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                shape = RoundedCornerShape(18.dp),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Visibility, null)
                        Spacer(Modifier.width(10.dp))
                        Text("Diagnostica realmente passiva", style = MaterialTheme.typography.titleLarge)
                    }
                    Text(
                        "Questa versione non apre il microfono, non seleziona dispositivi audio, " +
                            "non cambia la modalità audio e non modifica AudioPolicy. Osserva soltanto " +
                            "quello che Android fa quando attivi Gemini da Cardo mentre Android Auto è attivo.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            ShizukuStatusCard(shizukuManager)

            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                shape = RoundedCornerShape(18.dp),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Test unico — 20 secondi", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "0–3 s: non toccare nulla. 3–15 s: premi il tasto vocale del Cardo e parla a Gemini. " +
                            "15–20 s: rilascia tutto e attendi. Non attivare nessun vecchio routing BTMicFix.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = ::startObservation,
                        enabled = !running,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                    ) {
                        Icon(Icons.Default.BugReport, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (running) "OSSERVAZIONE IN CORSO" else "AVVIA OSSERVAZIONE PASSIVA")
                    }
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                shape = RoundedCornerShape(18.dp),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(cue, color = cueColor, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    if (running) Text("Tempo residuo: ${remaining}s")
                    live?.let { state ->
                        HorizontalDivider()
                        Text("Audio mode: ${state.audioMode}")
                        Text("Communication device: ${state.communicationDevice}")
                        Text("Registrazioni attive osservate: ${state.recordings.size}")
                        if (state.recordings.isNotEmpty()) {
                            state.recordings.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                        }
                        Text("Rete: ${state.networkSummary}")
                        Text("Bluetooth: ${state.bluetoothSummary}")
                    }
                }
            }

            report?.let { result ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Esito", style = MaterialTheme.typography.titleLarge)
                        Text(result.summary)
                        OutlinedButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("BTMicFix passive report", result.fullText))
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.ContentCopy, null)
                            Spacer(Modifier.width(8.dp))
                            Text("COPIA REPORT COMPLETO")
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun launchObservation(
    scope: kotlinx.coroutines.CoroutineScope,
    diagnosticsManager: PassiveDiagnosticsManager,
    shizukuManager: ShizukuManager,
    shizukuStatus: ShizukuManager.ShizukuStatus,
) {
    scope.launch {
        val privilegedReady = if (shizukuStatus == ShizukuManager.ShizukuStatus.READY) {
            withContext(Dispatchers.IO) { shizukuManager.awaitServiceReady(4_000L) }
        } else false

        diagnosticsManager.runObservation(
            privilegedSnapshotProvider = if (privilegedReady) {
                { label -> withContext(Dispatchers.IO) { shizukuManager.collectPassiveSnapshot(label) } }
            } else null,
        )
    }
}
