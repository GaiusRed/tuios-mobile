package dev.gaiusred.tuiosmobile.ui.conversation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.gaiusred.tuiosmobile.sessions.SessionSnapshot
import dev.gaiusred.tuiosmobile.storage.TranscriptAvailability
import dev.gaiusred.tuiosmobile.ui.design.*
import dev.gaiusred.tuiosmobile.ui.sessions.IdentityMark

@Composable
fun ConversationScreen(viewModel: ConversationViewModel, sessionSnapshot: SessionSnapshot,
    onBack: () -> Unit, onMail: () -> Unit, attentionContent: @Composable () -> Unit = {}) {
    val state by viewModel.state.collectAsState()
    val source = viewModel.source
    val saved = source is ConversationSource.Saved
    val ownHostStatus = sessionSnapshot.takeIf { source is ConversationSource.Live && it.hostId == source.target.hostId }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, modifier = Modifier.testTag("conversation-back")) { Text("‹ Sessions") }
            Spacer(Modifier.weight(1f))
            if (!saved) TextButton(onClick = onMail) { Text("Mail") }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("conversation-list"), contentPadding = PaddingValues(bottom = 20.dp)) {
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    IdentityMark(source.agentName, compact = true)
                    Text(source.agentName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                    Text("${source.hostName} · ${source.sessionName}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (source is ConversationSource.Saved) {
                        Text("Saved conversation · Read only", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(state.lastDownloadedAt?.let { "Downloaded ${displayTime(it)}" } ?: "Download time unavailable",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (!saved) ConnectionBanner(null, state.offline || ownHostStatus == null, ownHostStatus?.lastSyncedAt,
                    ownHostStatus?.problem != null)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
            }
            if (!saved) item { attentionContent() }
            if (!saved && state.canLoadOlder) item {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TextButton(onClick = viewModel::loadOlder, enabled = !state.offline && !state.loadingOlder) {
                        Text(if (state.loadingOlder) "Loading older messages…" else "Load older messages")
                    }
                }
            }
            if (state.pagingFailed) item { ConversationNotice("Older messages unavailable", "Try again when your host is connected.", null) }
            if (saved && !state.savedExists) item {
                ConversationNotice("Saved conversation removed", "This conversation is no longer stored on this device.", null)
            } else if (!saved) {
                when (state.availability) {
                    TranscriptAvailability.Unavailable -> item {
                        ConversationNotice("Transcript unavailable", "You can still read and send Mail with this agent.", onMail)
                    }
                    TranscriptAvailability.Failed -> item {
                        ConversationNotice("Updates unavailable", "Downloaded messages are still available. You can continue in Mail.", onMail)
                    }
                    TranscriptAvailability.NotLoaded -> if (state.entries.isEmpty()) item {
                        ConversationNotice("No downloaded messages", if (state.offline) "Reconnect to check this conversation, or open Mail."
                            else "Waiting for this conversation. You can open Mail in the meantime.", onMail)
                    }
                    TranscriptAvailability.Available -> if (state.entries.isEmpty()) item {
                        ConversationNotice("No messages yet", "Messages from this agent will appear here.", null)
                    }
                }
            }
            items(state.entries, key = { it.id }) { MessageBubble(it, source.agentName) }
        }
    }
}

@Composable
private fun ConversationNotice(title: String, body: String, onMail: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (onMail != null) TextButton(onClick = onMail) { Text("Open Mail") }
    }
}
