package com.btmicfix.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.btmicfix.audio.AudioRoutingManager
import com.btmicfix.ui.theme.StatusActive
import com.btmicfix.ui.theme.StatusFailed
import com.btmicfix.ui.theme.StatusRouting
import com.btmicfix.ui.theme.SurfaceCard

@Composable
fun VoiceExclusionProbeCard(
    candidates: List<AudioRoutingManager.BluetoothAudioDevice>,
    selectedDeviceId: Int?,
    selectedAddress: String?,
    shizukuReady: Boolean,
    running: Boolean,
    secondsRemaining: Int,
    report: String?,
    onSelect: (Int) -> Unit,
    onRun: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Block, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    "Test inverso: escludi il navigatore dalla voce",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "Non forza l'interfono. Per 20 secondi prova invece a disabilitare SOLO il dispositivo scelto dalle strategie VOICE_COMMUNICATION/ASSISTANT. Media e Android Auto non vengono toccati. Alla fine la policy viene ripristinata automaticamente.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))
            if (candidates.isEmpty()) {
                Text(
                    "Nessun dispositivo Bluetooth alternativo disponibile da escludere.",
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusFailed,
                )
            } else {
                candidates.forEach { device ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !running) { onSelect(device.deviceInfo.id) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selectedDeviceId == device.deviceInfo.id,
                            onClick = if (running) null else ({ onSelect(device.deviceInfo.id) }),
                        )
                        Spacer(Modifier.width(6.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(device.name)
                            Text(
                                device.typeLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            val selected = candidates.firstOrNull { it.deviceInfo.id == selectedDeviceId }
            if (selected != null) {
                Text(
                    if (selectedAddress.isNullOrBlank()) {
                        "Indirizzo stabile non disponibile: il test viene bloccato per evitare di disabilitare genericamente tutti i dispositivi dello stesso tipo."
                    } else {
                        "Target: ${selected.name} • indirizzo …${selectedAddress.takeLast(5)}"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selectedAddress.isNullOrBlank()) StatusFailed else StatusActive,
                )
            }

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onRun,
                enabled = !running && selected != null && !selectedAddress.isNullOrBlank() && shizukuReady,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Science, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (running) "TEST IN CORSO" else "AVVIA TEST ESCLUSIONE 20s")
            }

            if (!shizukuReady) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Shizuku deve essere READY: il test usa una API AudioPolicy privilegiata e temporanea.",
                    style = MaterialTheme.typography.labelSmall,
                    color = StatusFailed,
                )
            }

            if (running) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                Text(
                    "Finestra attiva: $secondsRemaining s — premi ORA il tasto vocale dell'interfono e parla a Gemini.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = StatusRouting,
                    fontWeight = FontWeight.Bold,
                )
            }

            report?.let {
                Spacer(Modifier.height(12.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                ) {
                    Text(
                        it,
                        modifier = Modifier.padding(10.dp),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}
