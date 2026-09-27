package com.btmicfix.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.btmicfix.audio.AudioRoutingManager
import com.btmicfix.companion.DeviceCompanionManager
import com.btmicfix.shizuku.ShizukuManager
import com.btmicfix.ui.components.ShizukuStatusCard
import com.btmicfix.ui.theme.*
import com.btmicfix.util.Preferences
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    audioRoutingManager: AudioRoutingManager,
    shizukuManager: ShizukuManager,
    companionManager: DeviceCompanionManager,
    preferences: Preferences,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val routingState by audioRoutingManager.routingState.collectAsState()

    var btGranted by remember {
        mutableStateOf(
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var micGranted by remember {
        mutableStateOf(
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var notificationGranted by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            } else true
        )
    }

    var associatedDevices by remember { mutableStateOf(companionManager.getAssociatedDevices()) }
    var autoRouteEnabled by remember { mutableStateOf(preferences.autoRouteEnabled) }
    fun refreshDevices() { associatedDevices = companionManager.getAssociatedDevices() }

    val btLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        btGranted = it
        if (it) refreshDevices()
    }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        micGranted = it
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationGranted = it
    }
    val cdmLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { refreshDevices() }

    LaunchedEffect(btGranted) {
        if (btGranted) refreshDevices()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Configurazione", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro", tint = Purple80)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SurfaceDark,
                    titleContentColor = Purple80,
                ),
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(modifier = Modifier.height(6.dp))

            PermissionCard("Bluetooth", btGranted) {
                btLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            }
            PermissionCard("Microfono", micGranted) {
                micLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                PermissionCard("Notifiche servizio", notificationGranted) {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
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
                    Text("Dispositivo prioritario", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "BTMicFix instrada e diagnostica soltanto il dispositivo scelto qui. Le altre associazioni non possono cambiare il routing.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    associatedDevices.forEach { device ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(device.name, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        if (device.isPriority) "PRIORITARIO" else "Associazione ${device.associationId}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (device.isPriority) StatusActive else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (!device.isPriority) {
                                    TextButton(onClick = {
                                        val old = companionManager.getPriorityDevice()
                                        if (companionManager.makeExclusivePriority(device.associationId)) {
                                            old?.let {
                                                audioRoutingManager.clearRoutingIfPreferred(it.address, it.routingName)
                                            }
                                            shizukuManager.clearForcedBluetoothScoIfApplied()
                                            audioRoutingManager.clearMicDiagnostics()
                                            refreshDevices()
                                        }
                                    }) { Text("Usa") }
                                } else {
                                    Icon(Icons.Default.Check, "Prioritario", tint = StatusActive)
                                }
                                IconButton(onClick = {
                                    val target = device
                                    val removed = companionManager.removeAssociation(device.associationId)
                                    if (removed && target.isPriority) {
                                        audioRoutingManager.clearRoutingIfPreferred(target.address, target.routingName)
                                        shizukuManager.clearForcedBluetoothScoIfApplied()
                                        audioRoutingManager.clearMicDiagnostics()
                                    }
                                    refreshDevices()
                                }) {
                                    Icon(Icons.Default.Delete, "Rimuovi")
                                }
                            }
                        }
                    }

                    Button(
                        onClick = {
                            val previous = companionManager.getPriorityDevice()
                            companionManager.startAssociation(cdmLauncher) {
                                val current = companionManager.getPriorityDevice()
                                if (previous != null && previous.associationId != current?.associationId) {
                                    audioRoutingManager.clearRoutingIfPreferred(previous.address, previous.routingName)
                                    shizukuManager.clearForcedBluetoothScoIfApplied()
                                    audioRoutingManager.clearMicDiagnostics()
                                }
                                refreshDevices()
                            }
                        },
                        enabled = btGranted,
                        colors = ButtonDefaults.buttonColors(containerColor = Purple40),
                    ) { Text("Associa dispositivo") }

                    if (associatedDevices.isNotEmpty()) {
                        TextButton(onClick = {
                            val old = companionManager.getPriorityDevice()
                            companionManager.resetAssociationsAndPriority()
                            val remainingPriority = companionManager.getPriorityDevice()
                            if (old != null && remainingPriority?.associationId != old.associationId) {
                                audioRoutingManager.clearRoutingIfPreferred(old.address, old.routingName)
                                shizukuManager.clearForcedBluetoothScoIfApplied()
                                audioRoutingManager.clearMicDiagnostics()
                            }
                            refreshDevices()
                        }) {
                            Icon(Icons.Default.RestartAlt, null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Azzera associazioni BTMicFix")
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Instradamento automatico", modifier = Modifier.weight(1f))
                Switch(
                    checked = autoRouteEnabled,
                    onCheckedChange = {
                        autoRouteEnabled = it
                        preferences.autoRouteEnabled = it
                    },
                )
            }

            val priority = companionManager.getPriorityDevice()
            Button(
                onClick = {
                    scope.launch {
                        audioRoutingManager.routeToPreferredBluetoothAndWait(
                            priority?.address,
                            priority?.routingName,
                        )
                    }
                },
                enabled = priority != null &&
                    routingState !is AudioRoutingManager.RoutingState.Routing,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Purple40),
            ) {
                Text(
                    if (routingState is AudioRoutingManager.RoutingState.Active)
                        "Routing confermato: ${(routingState as AudioRoutingManager.RoutingState.Active).deviceName}"
                    else "Verifica routing"
                )
            }
            Text(
                "Il test sopra verifica solo la route di comunicazione. Il microfono reale viene verificato dalla Diagnostica completa nella schermata principale.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            ShizukuStatusCard(shizukuManager)
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PermissionCard(title: String, granted: Boolean, onRequest: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(
                    if (granted) "Concesso" else "Necessario",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (granted) StatusActive else StatusRouting,
                )
            }
            if (!granted) TextButton(onClick = onRequest) { Text("Concedi") }
        }
    }
}
