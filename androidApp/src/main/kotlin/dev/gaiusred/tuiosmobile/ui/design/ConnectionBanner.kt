package dev.gaiusred.tuiosmobile.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.gaiusred.tuiosmobile.connection.ConnectionState
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.time.Instant

fun displayTime(time: Instant): String = DateTimeFormatter.ofPattern("MMM d, h:mm a")
    .withZone(ZoneId.systemDefault()).format(java.time.Instant.ofEpochSecond(time.epochSeconds, time.nanosecondsOfSecond.toLong()))

@Composable
fun ConnectionBanner(hostName: String?, offline: Boolean, lastSyncedAt: Instant?,
    problem: Boolean = false, connection: ConnectionState = ConnectionState.Disconnected) {
    val status = when {
        connection == ConnectionState.Connecting -> "Connecting"
        connection == ConnectionState.HostKeyReviewRequired -> "Host key needs review in Settings"
        connection == ConnectionState.RepairRequired || connection == ConnectionState.AuthenticationFailed -> "Reconnect in Settings"
        problem -> "Updates paused"
        offline -> "Offline"
        else -> "Connected"
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(6.dp).background(if (offline || problem) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.primary, CircleShape))
        Column {
            Text(listOfNotNull(hostName, status).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (offline || problem) Text(lastSyncedAt?.let { "Last synced ${displayTime(it)}" } ?: "Last sync time unavailable",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
