package com.btmicfix.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.btmicfix.audio.AudioRoutingManager
import com.btmicfix.ui.theme.*

/** Read-only list. Routing is controlled only by the priority device selected in Setup. */
@Composable
fun DeviceSelector(
    devices: List<AudioRoutingManager.BluetoothAudioDevice>,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Dispositivi Bluetooth", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            if (devices.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.LinkOff, null, tint = StatusIdle)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        "Nessun dispositivo di comunicazione Bluetooth disponibile",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                devices.forEach { device ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Headset, null, tint = Purple40)
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(device.name)
                            Text(
                                device.typeLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(Icons.Default.Bluetooth, "Disponibile", tint = Purple40)
                    }
                }
            }
        }
    }
}
