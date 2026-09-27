package com.btmicfix.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.btmicfix.shizuku.ShizukuManager
import com.btmicfix.ui.theme.*

@Composable
fun ShizukuStatusCard(
    shizukuManager: ShizukuManager,
    modifier: Modifier = Modifier,
) {
    val status by shizukuManager.status.collectAsState()
    val serviceState by shizukuManager.serviceState.collectAsState()

    val (icon, text, color, action) = when (status) {
        ShizukuManager.ShizukuStatus.UNKNOWN -> Quad(Icons.Default.Info, "Controllo Shizuku…", StatusIdle, null)
        ShizukuManager.ShizukuStatus.NOT_INSTALLED -> Quad(Icons.Default.Close, "Shizuku non installato", StatusIdle, null)
        ShizukuManager.ShizukuStatus.NOT_RUNNING -> Quad(Icons.Default.Warning, "Shizuku non in esecuzione", StatusRouting, null)
        ShizukuManager.ShizukuStatus.PERMISSION_NEEDED -> Quad(Icons.Default.Warning, "Autorizzazione Shizuku necessaria", StatusRouting, "Autorizza")
        ShizukuManager.ShizukuStatus.READY -> when (serviceState) {
            ShizukuManager.UserServiceState.READY -> Quad(Icons.Default.CheckCircle, "Shizuku pronto — snapshot read-only abilitati", StatusActive, null)
            ShizukuManager.UserServiceState.CONNECTING -> Quad(Icons.Default.Info, "Collegamento al servizio read-only…", StatusRouting, null)
            else -> Quad(Icons.Default.Warning, "Shizuku autorizzato, servizio non pronto", StatusRouting, null)
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = color, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Shizuku", style = MaterialTheme.typography.titleMedium)
                Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (action != null) {
                TextButton(onClick = shizukuManager::requestPermission) { Text(action) }
            }
        }
    }
}

private data class Quad(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val text: String,
    val color: androidx.compose.ui.graphics.Color,
    val action: String?,
)
