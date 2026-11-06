package dev.gaiusred.tuiosmobile.ui.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gaiusred.tuiosmobile.connection.*
import dev.gaiusred.tuiosmobile.sessions.*
import dev.gaiusred.tuiosmobile.ui.design.*

@Composable
fun SessionsScreen(viewModel: SessionsViewModel, hosts: List<HostProfile>, connection: ConnectionState,
    onOpenAgent: (AgentSummary, SessionSummary) -> Unit, onOpenSaved: (SavedConversationRow) -> Unit,
    onSettings: () -> Unit, attentionContent: @Composable (AgentTarget) -> Unit = {},
    cachedHostIds: List<dev.gaiusred.tuiosmobile.identity.HostId> = emptyList(), onRetryConnection: () -> Unit = {}) {
    val state by viewModel.state.collectAsState()
    var search by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(hosts) { viewModel.setHosts(hosts) }
    LaunchedEffect(cachedHostIds) { viewModel.setCachedHosts(cachedHostIds) }
    LaunchedEffect(search) { viewModel.search(search) }
    val hostName = hosts.find { it.id == state.snapshot.hostId }?.machine
    LazyColumn(Modifier.fillMaxSize().testTag("sessions-list"), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 14.dp, top = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("Sessions", Modifier.weight(1f).testTag("sessions-title"), style = MaterialTheme.typography.headlineLarge)
                TextButton(onClick = viewModel::refresh, enabled = !state.snapshot.offline) { Text("Refresh") }
            }
            OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp)
                .testTag("session-search"), singleLine = true, placeholder = { Text("Search on this device") },
                leadingIcon = { Text("⌕", fontSize = 26.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                shape = RoundedCornerShape(14.dp), colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant))
            if (state.snapshot.hostId != null && (connection in setOf(ConnectionState.Disconnected, ConnectionState.Failed) ||
                connection is ConnectionState.Connected && state.snapshot.problem != null)) {
                TextButton(onClick = onRetryConnection) { Text("Retry connection") }
            }
            ConnectionBanner(hostName, state.snapshot.offline, state.snapshot.lastSyncedAt,
                state.snapshot.problem != null, connection)
            state.snapshot.problem?.let { problem ->
                Text(when (problem) {
                    SyncProblem.UnsupportedProtocol -> "This daemon's protocol is not supported. Update the daemon or app."
                    SyncProblem.SubscriptionUnsupported -> "This daemon cannot verify live sessions: subscriptions are unsupported. Saved content remains available."
                    SyncProblem.ListingUnsupported -> "This daemon cannot list sessions or agents. Saved content remains available."
                    SyncProblem.DiscoveryUnavailable -> "Checking daemon compatibility failed. Retrying while this app is open."
                    SyncProblem.ListingUnavailable -> "Session listing is unavailable."
                    SyncProblem.SubscriptionUnavailable -> "Live updates are unavailable. Reconnecting while this app is open."
                }, Modifier.padding(horizontal = 22.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (state.sessions.isEmpty() && state.saved.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (search.isNotBlank()) "No matching conversations" else "No conversations yet",
                    style = MaterialTheme.typography.titleMedium)
                Text(when {
                    search.isNotBlank() -> "Try a different name or downloaded message."
                    state.snapshot.hostId == null -> "Pair a host in Settings to see your sessions here."
                    state.snapshot.offline -> "Your downloaded conversations will appear here. Reconnect to check for sessions."
                    else -> "No sessions are available on this host."
                }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.snapshot.hostId == null) TextButton(onClick = onSettings) { Text("Open Settings") }
            }
        }
        state.sessions.forEach { session ->
            item(key = "session-${session.id}") {
                SectionTitle(session.name)
                if (session.agents.isEmpty()) Text("No agents in this session", Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(session.agents, key = { "agent-${it.target.sessionId}-${it.target.windowId}-${it.target.agentSessionId}" }) { agent ->
                val preview = state.previews[agent.target]
                ConversationRow(agent.name, preview?.text?.takeIf { it.isNotBlank() } ?: agent.state,
                    "${agent.harnessId} · ${session.name}", preview?.serverAt?.let(::displayTime), agent.needsYou,
                    Modifier.testTag("agent-${agent.name}"), onClick = { onOpenAgent(agent, session) })
                attentionContent(agent.target)
            }
        }
        if (state.saved.isNotEmpty()) {
            item { SectionTitle("Saved conversations") }
            items(state.saved, key = { it.conversation.target.key.toString() }) { row ->
                val saved = row.conversation
                ConversationRow(saved.agentName, saved.sessionName,
                    "${row.hostName} · Read only", saved.lastDownloadedAt?.let(::displayTime), false,
                    Modifier.testTag("saved-${saved.target.key.transcriptId}"), onClick = { onOpenSaved(row) })
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 19.dp, bottom = 7.dp)
        .semantics { heading() }, style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ConversationRow(title: String, preview: String, context: String, time: String?, unread: Boolean,
    modifier: Modifier, onClick: () -> Unit) {
    Column {
        Row(modifier.fillMaxWidth().clickable(onClickLabel = "Open $title", role = Role.Button, onClick = onClick)
            .padding(start = 12.dp, end = 20.dp, top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(10.dp)) {
                if (unread) Box(Modifier.size(6.dp).background(MaterialTheme.colorScheme.primary, CircleShape)
                    .semantics { contentDescription = "Needs your attention" })
            }
            IdentityMark(title)
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (time != null) Text(time, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(context, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(preview, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text("›", Modifier.padding(start = 9.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 23.sp)
        }
        HorizontalDivider(Modifier.padding(start = 82.dp), color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
    }
}

@Composable
fun IdentityMark(name: String, modifier: Modifier = Modifier, compact: Boolean = false) {
    Box(modifier.size(if (compact) 32.dp else 48.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.13f), CircleShape),
        contentAlignment = Alignment.Center) {
        Text(name.trim().take(1).uppercase(), color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold, fontSize = if (compact) 16.sp else 20.sp)
    }
}
