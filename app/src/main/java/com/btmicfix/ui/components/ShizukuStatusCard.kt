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
import com.btmicfix.shizuku.ShizukuManager.ShizukuStatus
import com.btmicfix.shizuku.ShizukuManager.UserServiceState
import com.btmicfix.ui.theme.*

@Composable
fun ShizukuStatusCard(
    shizukuManager: ShizukuManager,
    modifier: Modifier = Modifier,
) {
    val status by shizukuManager.status.collectAsState()
    val serviceState by shizukuManager.serviceState.collectAsState()

    val text = when (status) {
        ShizukuStatus.UNKNOWN -> "Controllo Shizuku…"
        ShizukuStatus.NOT_INSTALLED -> "Shizuku non installato"
        ShizukuStatus.NOT_RUNNING -> "Shizuku non in esecuzione"
        ShizukuStatus.PERMISSION_NEEDED -> "Autorizzazione Shizuku necessaria"
        ShizukuStatus.READY -> when (serviceState) {
            UserServiceState.READY -> "Shizuku pronto — servizio privilegiato connesso"
            UserServiceState.BINDING -> "Shizuku pronto — collegamento servizio privilegiato…"
            UserServiceState.ERROR -> "Shizuku pronto — errore servizio privilegiato"
            UserServiceState.DISCONNECTED -> "Shizuku pronto — servizio privilegiato non connesso"
        }
    }

    val good = status == ShizukuStatus.READY && serviceState == UserServiceState.READY
    val icon = when {
        good -> Icons.Default.CheckCircle
        status == ShizukuStatus.NOT_INSTALLED -> Icons.Default.Close
        status == ShizukuStatus.UNKNOWN -> Icons.Default.Info
        else -> Icons.Default.Warning
    }
    val color = if (good) StatusActive else if (status == ShizukuStatus.UNKNOWN) StatusIdle else StatusRouting

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Shizuku", style = MaterialTheme.typography.titleMedium)
                Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when {
                status == ShizukuStatus.PERMISSION_NEEDED -> TextButton(onClick = { shizukuManager.requestPermission() }) { Text("Concedi") }
                status == ShizukuStatus.READY && serviceState != UserServiceState.READY -> TextButton(onClick = { shizukuManager.ensurePrivilegedServiceBound() }) { Text("Connetti") }
            }
        }
    }
}
