package com.btmicfix.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.btmicfix.audio.AudioRoutingManager
import com.btmicfix.shizuku.ShizukuManager
import com.btmicfix.ui.theme.StatusActive
import com.btmicfix.ui.theme.SurfaceCard
import com.btmicfix.ui.theme.SurfaceDark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailsScreen(
    audioRoutingManager: AudioRoutingManager,
    shizukuManager: ShizukuManager,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shizukuStatus by shizukuManager.status.collectAsState()
    val serviceState by shizukuManager.serviceState.collectAsState()
    val lastForceResult by shizukuManager.lastForceResult.collectAsState()
    val micTestResults by audioRoutingManager.micTestResults.collectAsState()
    val scope = rememberCoroutineScope()
    var audioDump by remember { mutableStateOf<String?>(null) }
    var dumpLoading by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Dettagli tecnici", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Indietro")
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
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            DetailCodeCard(
                title = "Stato",
                text = buildString {
                    appendLine("Shizuku: $shizukuStatus")
                    appendLine("Servizio privilegiato: $serviceState")
                    appendLine("Communication device: ${audioRoutingManager.currentCommunicationDeviceLabel()}")
                }.trim(),
            )

            DetailCodeCard(
                title = "Ultima forzatura Shizuku",
                text = lastForceResult ?: "Nessuna forzatura eseguita in questa sessione.",
            )

            AudioRoutingManager.MicTestSource.entries.forEach { source ->
                DetailCodeCard(
                    title = "Test ${source.label}",
                    text = micTestResults[source]?.details ?: "Non eseguito in questa sessione.",
                )
            }

            OutlinedButton(
                onClick = {
                    if (!dumpLoading) {
                        dumpLoading = true
                        scope.launch {
                            audioDump = withContext(Dispatchers.IO) {
                                shizukuManager.getAudioDiagnostics() ?: "Nessun dump disponibile"
                            }
                            dumpLoading = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (dumpLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Acquisizione…")
                } else {
                    Text("Acquisisci dump audio completo")
                }
            }

            audioDump?.let {
                DetailCodeCard(title = "Audio dump", text = it)
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun DetailCodeCard(title: String, text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            SelectionContainer {
                Text(
                    text = text,
                    color = StatusActive,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
            }
        }
    }
}
