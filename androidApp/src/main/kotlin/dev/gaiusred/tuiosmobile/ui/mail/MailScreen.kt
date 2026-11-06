package dev.gaiusred.tuiosmobile.ui.mail

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.gaiusred.tuiosmobile.connection.HostProfile
import dev.gaiusred.tuiosmobile.mail.*
import dev.gaiusred.tuiosmobile.storage.*
import dev.gaiusred.tuiosmobile.ui.design.*
import dev.gaiusred.tuiosmobile.ui.sessions.IdentityMark

@Composable
fun MailScreen(model: MailViewModel, hosts: List<HostProfile>, initialAgent: List<String> = emptyList(),
    onInitialAgentConsumed: () -> Unit = {}, cachedHostIds: List<dev.gaiusred.tuiosmobile.identity.HostId> = emptyList()) {
    val boxes by model.mailboxes.collectAsState()
    val savedDrafts by model.savedDrafts.collectAsState()
    var route by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var localRoute by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(hosts) { model.setHosts(hosts) }
    LaunchedEffect(cachedHostIds) { model.setCachedHosts(cachedHostIds) }
    LaunchedEffect(initialAgent) { if (initialAgent.size == 4) {
        localRoute = emptyList()
        route = initialAgent.take(3) + listOf("", initialAgent[3])
        onInitialAgentConsumed()
    } }
    BackHandler(route.isNotEmpty() || localRoute.isNotEmpty()) { route = emptyList(); localRoute = emptyList() }
    val localTarget = localRoute.toLocalDraftTarget()
    if (localTarget != null) {
        val draft by remember(model, localTarget) { model.savedDraft(localTarget) }.collectAsState(null)
        SavedDraftScreen(draft, hosts.find { it.id == localTarget.hostId }?.machine ?: "Saved host",
            onBack = { localRoute = emptyList() })
        return
    }
    val selected = boxes.find { route.size in 5..6 && it.snapshot.target.let { target ->
        route[0] == target.hostId.value && route[1] == target.sessionId && route[2] == target.daemonBootId } }
    if (selected != null) {
        val legacyMode = route.getOrNull(5) == "legacy"
        val legacy = if (legacyMode) selected.snapshot.legacyDrafts.find {
            it.recipient == route[4] && it.threadId == route[3].ifBlank { null }
        } else null
        MailThreadScreen(model, selected, route[3].ifBlank { null }, route[4].ifBlank { null }, onBack = { route = emptyList() },
            legacyMode = legacyMode, legacyDraft = legacy, onAdopted = { recipient -> route = selected.snapshot.target.route(null, recipient) })
        return
    }
    LazyColumn(Modifier.fillMaxSize().testTag("mail-list"), contentPadding = PaddingValues(bottom = 18.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 14.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Mail", Modifier.weight(1f), style = MaterialTheme.typography.headlineLarge)
                TextButton(onClick = model::refresh, enabled = boxes.any { !it.snapshot.offline && !it.snapshot.readOnly }) { Text("Refresh") }
            }
            Text("Your exchanges with agents", Modifier.padding(horizontal = 23.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (boxes.isEmpty() && savedDrafts.isEmpty()) item {
            Text("Mail from your sessions will appear here. Pair a host in Settings to get started.", Modifier.padding(24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (savedDrafts.isNotEmpty()) {
            item { Text("Saved drafts · Read only", Modifier.padding(horizontal = 23.dp, vertical = 12.dp), style = MaterialTheme.typography.titleMedium) }
            items(savedDrafts, key = { "local-${it.draft.localTarget()}" }) { row ->
                MailListRow(row.draft.recipient, "${savedDraftLabel(row.draft)}: ${row.draft.text}", null, false) {
                    localRoute = row.draft.localTarget().toLocalRoute()
                }
            }
        }
        boxes.forEach { box ->
            val snapshot = box.snapshot
            item(key = "header-${snapshot.target}") {
                Row(Modifier.fillMaxWidth().padding(start = 23.dp, end = 12.dp, top = 17.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(snapshot.sessionName, style = MaterialTheme.typography.titleMedium)
                        Text("${box.hostName} · ${if (snapshot.readOnly) "Saved · Read only" else if (snapshot.offline) "Offline" else "Connected"}",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (!snapshot.readOnly) TextButton(onClick = { route = snapshot.target.route(null, null) },
                        modifier = Modifier.testTag("mail-new-${snapshot.target.sessionId}-${snapshot.target.daemonBootId}")) { Text("New message") }
                }
                if (snapshot.historyGap) Text("Some older Mail is no longer available on the host.", Modifier.padding(horizontal = 23.dp, vertical = 9.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                if (snapshot.problem != null) Text(if (snapshot.problem == MailProblem.Unsupported)
                    "This daemon does not support reading Mail." else "Mail is unavailable. Check the connection and Mail permission.",
                    Modifier.padding(horizontal = 23.dp, vertical = 9.dp), color = MaterialTheme.colorScheme.error)
            }
            val threads = snapshot.messages.groupBy { it.content.threadId }.entries.toList().asReversed()
            items(threads, key = { "${snapshot.target}-thread-${it.key}" }) { thread ->
                val last = thread.value.last()
                val people = thread.value.flatMap { listOf(it.content.from to it.content.fromLabel, it.content.to to it.content.toLabel) }
                    .filter { it.first != "human" }.distinctBy { it.first }.joinToString(", ") { it.second.ifBlank { it.first } }
                MailListRow(people.ifBlank { "Mail exchange" }, last.content.subject?.takeIf { it.isNotBlank() }
                    ?.let { "$it: ${last.content.text}" } ?: last.content.text,
                    last.sentAt?.let(::displayTime), thread.value.any { !it.content.localRead && it.content.from != "human" },
                    Modifier.testTag("mail-thread-${thread.key}")) { route = snapshot.target.route(thread.key, null) }
            }
            items(snapshot.drafts.filter { !snapshot.readOnly && it.text.isNotBlank() }, key = { "${snapshot.target}-draft-${it.recipient}-${it.threadId}" }) { draft ->
                val label = when (draft.status) {
                    MailDraftStatus.Draft -> "Draft"
                    MailDraftStatus.Uncertain -> "Possibly sent"
                    MailDraftStatus.Rejected -> "Rejected"
                    MailDraftStatus.Sent -> "Sent"
                }
                MailListRow(snapshot.recipients.find { it.id == draft.recipient }?.name ?: draft.recipient,
                    "$label: ${draft.text}", null, false) { route = snapshot.target.route(draft.threadId, draft.recipient) }
            }
            items(snapshot.legacyDrafts.filter { !snapshot.readOnly && it.text.isNotBlank() }, key = { "${snapshot.target}-legacy-${it.recipient}-${it.threadId}" }) { draft ->
                MailListRow("Saved draft", "Draft to review: ${draft.text}", null, false) {
                    route = snapshot.target.route(draft.threadId, draft.recipient) + "legacy"
                }
            }
            if (threads.isEmpty() && snapshot.drafts.none { it.text.isNotBlank() } && snapshot.legacyDrafts.none { it.text.isNotBlank() }) item {
                Text("No downloaded Mail yet", Modifier.padding(horizontal = 24.dp, vertical = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun MailTarget.route(thread: String?, recipient: String?) = listOf(hostId.value, sessionId, daemonBootId, thread.orEmpty(), recipient.orEmpty())

@Composable
private fun MailListRow(title: String, preview: String, time: String?, unread: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 18.dp, end = 23.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(if (unread) "●" else "", Modifier.width(16.dp).then(if (unread) Modifier.semantics { contentDescription = "Unread on this phone" } else Modifier),
            color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
        IdentityMark(title)
        Column(Modifier.weight(1f).padding(start = 13.dp)) {
            Row {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                time?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Text(preview, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium)
        }
    }
    HorizontalDivider(Modifier.padding(start = 98.dp, end = 22.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}
