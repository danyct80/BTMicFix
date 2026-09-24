package com.btmicfix.ui.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.btmicfix.audio.AudioRoutingManager
import com.btmicfix.companion.DeviceCompanionManager
import com.btmicfix.shizuku.ShizukuManager
import com.btmicfix.shizuku.ShizukuManager.ShizukuStatus
import com.btmicfix.ui.theme.*
import com.btmicfix.util.Preferences

/**
 * Step-by-step setup wizard that guides the user through:
 * 1. Granting Bluetooth permission
 * 2. (Optional) Setting up Shizuku
 * 3. Pairing earbuds via Companion Device Manager
 * 4. Testing the audio routing
 */
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
    val shizukuStatus by shizukuManager.status.collectAsState()
    val routingState by audioRoutingManager.routingState.collectAsState()

    // Bluetooth permission state
    var bluetoothPermissionGranted by remember {
        mutableStateOf(
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }

    // Notification permission state (Android 13+)
    var notificationPermissionGranted by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            } else true
        )
    }

    // Permission launchers
    val btPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        bluetoothPermissionGranted = granted
    }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationPermissionGranted = granted
    }

    // Dispositivi associati (letti dal sistema: restano corretti anche dopo un riavvio dell'app)
    var associatedNames by remember { mutableStateOf(companionManager.getAssociatedDeviceNames()) }

    // CDM association launcher
    val cdmLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        // Dopo la scelta nella finestra di sistema, aggiorna il nome mostrato
        associatedNames = companionManager.getAssociatedDeviceNames()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text("Configurazione", fontWeight = FontWeight.Bold)
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Indietro",
                            tint = Purple80,
                        )
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
            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Completa questi passaggi per attivare l'instradamento automatico del microfono Bluetooth.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Step 1: Bluetooth Permission
            SetupStepCard(
                stepNumber = 1,
                title = "Autorizzazione Bluetooth",
                description = "Necessaria per rilevare i tuoi auricolari e comunicare con essi.",
                isComplete = bluetoothPermissionGranted,
                actionLabel = if (bluetoothPermissionGranted) null else "Concedi",
                onAction = {
                    btPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                },
            )

            // Step 2: Notification Permission (Android 13+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                SetupStepCard(
                    stepNumber = 2,
                    title = "Autorizzazione notifiche",
                    description = "Serve a mostrare lo stato dell'instradamento quando è attivo in background.",
                    isComplete = notificationPermissionGranted,
                    actionLabel = if (notificationPermissionGranted) null else "Concedi",
                    onAction = {
                        notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    },
                )
            }

            // Step 3: Shizuku (Optional)
            SetupStepCard(
                stepNumber = 3,
                title = "Shizuku (facoltativo)",
                description = "Offre un instradamento alternativo avanzato per i dispositivi problematici. Non necessario per la maggior parte degli utenti.",
                isComplete = shizukuStatus == ShizukuStatus.READY,
                isOptional = true,
                actionLabel = when (shizukuStatus) {
                    ShizukuStatus.NOT_INSTALLED -> "Installa Shizuku"
                    ShizukuStatus.NOT_RUNNING -> "Avvia Shizuku"
                    ShizukuStatus.PERMISSION_NEEDED -> "Concedi autorizzazione"
                    ShizukuStatus.READY -> null
                    ShizukuStatus.UNKNOWN -> "Controlla"
                },
                onAction = {
                    when (shizukuStatus) {
                        ShizukuStatus.NOT_INSTALLED -> {
                            // Open Play Store or GitHub for Shizuku
                            val intent = Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api")
                            )
                            context.startActivity(intent)
                        }
                        ShizukuStatus.PERMISSION_NEEDED -> {
                            shizukuManager.requestPermission()
                        }
                        else -> {
                            shizukuManager.refreshStatus()
                        }
                    }
                },
            )

            // Step 4: Pair Earbuds via CDM
            SetupStepCard(
                stepNumber = 4,
                title = "Associa auricolari",
                description = if (associatedNames.isNotEmpty()) {
                    "L'instradamento si attiverà automaticamente quando l'auricolare si connette."
                } else {
                    "Associa i tuoi auricolari Bluetooth per l'instradamento automatico in background."
                },
                detail = if (associatedNames.isNotEmpty()) {
                    (if (associatedNames.size == 1) "Associato: " else "Associati: ") +
                        associatedNames.joinToString(", ")
                } else null,
                isComplete = associatedNames.isNotEmpty(),
                actionLabel = if (associatedNames.isNotEmpty()) "Associa un altro dispositivo" else "Associa dispositivo",
                onAction = {
                    companionManager.startAssociation(cdmLauncher) {
                        associatedNames = companionManager.getAssociatedDeviceNames()
                    }
                },
            )

            // Step 5: Test Routing
            SetupStepCard(
                stepNumber = 5,
                title = "Prova instradamento",
                description = when (routingState) {
                    is AudioRoutingManager.RoutingState.Active ->
                        "✓ Instradamento attivo! Il microfono Bluetooth ora dovrebbe funzionare nelle app di IA."
                    is AudioRoutingManager.RoutingState.Failed ->
                        "✗ Instradamento non riuscito. Verifica che gli auricolari siano connessi."
                    else ->
                        "Connetti gli auricolari e tocca Prova per verificare che il microfono venga instradato."
                },
                isComplete = routingState is AudioRoutingManager.RoutingState.Active,
                actionLabel = when (routingState) {
                    is AudioRoutingManager.RoutingState.Active -> "Ferma"
                    is AudioRoutingManager.RoutingState.Routing -> null
                    else -> "Prova"
                },
                onAction = {
                    if (routingState is AudioRoutingManager.RoutingState.Active) {
                        audioRoutingManager.clearRouting()
                    } else {
                        audioRoutingManager.routeToFirstAvailableBluetooth()
                    }
                },
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/**
 * Individual setup step card with completion indicator and action button.
 */
@Composable
private fun SetupStepCard(
    stepNumber: Int,
    title: String,
    description: String,
    isComplete: Boolean,
    isOptional: Boolean = false,
    detail: String? = null,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isComplete) SurfaceCardHigh else SurfaceCard,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            // Step number / completion indicator
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(
                        if (isComplete) StatusActive.copy(alpha = 0.2f)
                        else if (isOptional) StatusIdle.copy(alpha = 0.2f)
                        else Purple40.copy(alpha = 0.2f)
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (isComplete) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Completato",
                        tint = StatusActive,
                        modifier = Modifier.size(18.dp),
                    )
                } else {
                    Text(
                        text = "$stepNumber",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isOptional) StatusIdle else Purple40,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (isOptional) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = StatusIdle.copy(alpha = 0.2f),
                        ) {
                            Text(
                                text = "FACOLTATIVO",
                                style = MaterialTheme.typography.labelSmall,
                                color = StatusIdle,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (detail != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = StatusActive,
                        fontWeight = FontWeight.Bold,
                    )
                }

                if (actionLabel != null) {
                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = onAction,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Purple40,
                        ),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = actionLabel,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        }
    }
}
