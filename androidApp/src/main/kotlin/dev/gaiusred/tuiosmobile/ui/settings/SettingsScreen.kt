package dev.gaiusred.tuiosmobile.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.gaiusred.tuiosmobile.connection.*
import dev.gaiusred.tuiosmobile.identity.HostId
import dev.gaiusred.tuiosmobile.storage.RetentionPolicy

@Composable fun SettingsScreen(model: SettingsViewModel) {
    val actions = model.actions
    val hosts by actions.hosts.collectAsState()
    val selected by actions.selectedHostId.collectAsState()
    val loadProblem by actions.hostLoadProblem.collectAsState()
    val connection by actions.connection.collectAsState()
    val retention by actions.retention.collectAsState()
    val progress by actions.pairingProgress.collectAsState()
    val pending by actions.pairingUncertain.collectAsState()
    val capabilities by actions.capabilities.collectAsState()
    val cachedHostIds by actions.cachedHostIds.collectAsState()
    val busy by model.busy.collectAsState()
    val result by model.result.collectAsState()
    var pairing by remember { mutableStateOf(false) }
    var repair by remember { mutableStateOf<HostProfile?>(null) }
    var clear by remember { mutableStateOf<HostId?>(null) }
    var remove by remember { mutableStateOf<HostProfile?>(null) }
    var bulk by remember { mutableStateOf<BulkAction?>(null) }
    LaunchedEffect(result, busy) { if (!busy && result == SettingsResult.Paired) { pairing = false; repair = null } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("settings-list")
        .padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineLarge)
        if (loadProblem != null) SettingsGroup("Saved connections unavailable") {
            Text("Your downloaded history is still on this phone. Retry loading saved connections.")
            TextButton(onClick = { model.run { reload(); SettingsResult.Saved } }, enabled = !busy,
                modifier = Modifier.testTag("retry-hosts")) { Text("Retry loading connections") }
            TextButton(onClick = { bulk = BulkAction.ResetConnections }, enabled = !busy,
                modifier = Modifier.testTag("reset-connections")) { Text("Reset saved connections") }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        result?.let { Text(it.message(), style = MaterialTheme.typography.bodyMedium,
            color = if (it in setOf(SettingsResult.Failed, SettingsResult.VerificationFailed, SettingsResult.Rejected))
                MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
        if (progress is PairingProgress.AwaitingComparison && !pairing) PairingComparison(progress as PairingProgress.AwaitingComparison)
        else if (pending) SettingsGroup("Pairing needs review") {
            Text("An earlier attempt may have authorized a key. Check your computer before using a fresh invitation. The local key is kept; this app will not retry the attempt.")
        }
        SettingsGroup("Connections") {
            Text(connectionLabel(connection), color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (hosts.isEmpty()) Text("No paired hosts yet.")
            hosts.forEach { host ->
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(host.id == selected, onClick = { actions.selectHost(host.id) }, enabled = !busy)
                        TextButton(onClick = { actions.selectHost(host.id) }, enabled = !busy,
                            modifier = Modifier.weight(1f).testTag("select-${host.id.value}")) {
                            Text(host.machine, Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    Text("${host.user} · ${host.device}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { repair = host; pairing = true }, enabled = !busy,
                        modifier = Modifier.testTag("repair-${host.id.value}")) { Text("Re-pair this host") }
                    TextButton(onClick = { clear = host.id }, enabled = !busy,
                        modifier = Modifier.testTag("clear-${host.id.value}")) { Text("Clear downloaded history") }
                    TextButton(onClick = { remove = host }, enabled = !busy,
                        modifier = Modifier.testTag("remove-${host.id.value}")) { Text("Remove connection", color = MaterialTheme.colorScheme.error) }
                    HorizontalDivider()
                }
            }
            TextButton(onClick = { repair = null; pairing = true }, enabled = !busy,
                modifier = Modifier.testTag("pair-host")) { Text("Pair a host") }
        }
        SettingsGroup("Capabilities") {
            Text("Use list, mail, and respond permissions on your computer. Pairing does not request terminal input or broader access.")
            TextButton(onClick = { model.run { checkCapabilities() } }, enabled = !busy && connection is ConnectionState.Connected,
                modifier = Modifier.testTag("check-capabilities")) { Text("Check current host support") }
            capabilities?.takeIf { it.hostId == selected && connection is ConnectionState.Connected }?.let { summary ->
                Text("Daemon ${summary.daemonVersion.take(80)}", style = MaterialTheme.typography.titleSmall)
                listOf("Sessions" to setOf("list-sessions", "list-agents"),
                    "Conversations" to setOf("agent-transcript"), "Read Mail" to setOf("read-agent-messages"),
                    "Send Mail" to setOf("send-agent-message"), "Human responses" to setOf("respond", "answer-ask", "reply-approval"))
                    .forEach { (label, verbs) ->
                        val offered = if (label == "Sessions") verbs.all { it in summary.verbs } else verbs.any { it in summary.verbs }
                        Text("$label: ${if (offered) "Offered by daemon" else "Unavailable"}")
                    }
                Text("Support does not grant permission. Your host can still deny an action.", style = MaterialTheme.typography.bodySmall)
            } ?: Text("Connect and check to see the current daemon's support.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SettingsGroup("Keep downloaded history") {
            RetentionPolicy.entries.forEach { policy ->
                Row(Modifier.fillMaxWidth().selectable(policy == retention,
                    onClick = { model.run { changeRetention(policy) } }, enabled = !busy, role = Role.RadioButton)
                    .testTag("retention-${policy.name}").padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(policy == retention, onClick = null, enabled = !busy)
                    Text(when (policy) { RetentionPolicy.SevenDays -> "7 days"; RetentionPolicy.ThirtyDays -> "30 days"; RetentionPolicy.Forever -> "Forever" }, Modifier.padding(start = 12.dp))
                }
            }
            Text("Measured from receipt on this phone. Drafts and saved send/response attempts are not removed by age. Connection settings are kept.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { bulk = BulkAction.ClearHistory }, enabled = !busy,
                modifier = Modifier.testTag("clear-all-history")) { Text("Clear all downloaded history") }
        }
        val orphaned = cachedHostIds.filter { id -> hosts.none { it.id == id } }
        if (orphaned.isNotEmpty()) SettingsGroup("Saved history without a connection") {
            Text("Read this history in Sessions and Mail. It cannot connect or send.", style = MaterialTheme.typography.bodySmall)
            orphaned.forEach { id ->
                TextButton(onClick = { clear = id }, enabled = !busy, modifier = Modifier.testTag("clear-${id.value}")) {
                    Text("Clear saved host ${id.value.take(8)}")
                }
            }
        }
        SettingsGroup("Privacy") {
            Text("History stays on this phone. Automatic cloud backup and device transfer are excluded. Private SSH keys are encrypted using Android Keystore.")
            Text("Clearing local data does not delete content or authorized keys on your computer. Available remote history can be downloaded again.", style = MaterialTheme.typography.bodySmall)
            Text("Deletion removes app records. Secure erasure of device storage is not guaranteed.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { bulk = BulkAction.RemoveLocalData }, enabled = !busy,
                modifier = Modifier.testTag("remove-all-local")) { Text("Remove all local connections and content", color = MaterialTheme.colorScheme.error) }
        }
    }
    clear?.let { id ->
        AlertDialog(onDismissRequest = { clear = null }, title = { Text("Clear downloaded history?") },
            text = { Text("This deletes downloaded messages, read positions, drafts, and saved possibly sent attempts for this host. Check your computer before sending again. The saved connection and credential are kept. Available remote history can be downloaded again.", Modifier.testTag("clear-warning")) },
            confirmButton = { TextButton(onClick = { clear = null; model.run { clearHistory(id) } }, modifier = Modifier.testTag("confirm-clear")) { Text("Clear history") } },
            dismissButton = { TextButton(onClick = { clear = null }) { Text("Cancel") } })
    }
    remove?.let { host -> RemoveConnectionDialog(host, onDismiss = { remove = null }) { credentials, cache ->
        remove = null; model.run { removeHost(host.id, credentials, cache) }
    } }
    bulk?.let { action ->
        AlertDialog(onDismissRequest = { bulk = null }, title = { Text(action.title) },
            text = { Text(action.warning, Modifier.testTag("bulk-warning")) },
            confirmButton = { TextButton(onClick = { bulk = null; model.run {
                when (action) {
                    BulkAction.ClearHistory -> clearAllHistory()
                    BulkAction.ResetConnections -> resetConnections()
                    BulkAction.RemoveLocalData -> removeAllLocalData()
                }
            } }, modifier = Modifier.testTag("confirm-bulk")) { Text(action.confirmation) } },
            dismissButton = { TextButton(onClick = { bulk = null }) { Text("Cancel") } })
    }
    if (pairing) PairHostDialog(model, repair, progress, busy, onDismiss = { pairing = false; repair = null })
}

private enum class BulkAction(val title: String, val confirmation: String, val warning: String) {
    ClearHistory("Clear all downloaded history?", "Clear all history",
        "Delete messages, read positions, drafts and saved possibly sent attempts for every cached host, including removed connections. Saved connections and credentials are kept. Check your computer before sending again. Available remote history can be downloaded again."),
    ResetConnections("Reset saved connections?", "Reset connections",
        "Remove saved connection profiles from this phone, including unreadable settings. Downloaded history and local credentials are kept. This does not revoke any authorized key on your computer."),
    RemoveLocalData("Remove all local connections and content?", "Remove local data",
        "Delete all saved connections, local SSH credentials including detached pairing keys, downloaded history, drafts and saved possibly sent attempts. Retention returns to 30 days. This does not revoke authorized keys or delete content on your computer. Check the computer before pairing or sending again.")
}

@Composable private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable private fun RemoveConnectionDialog(host: HostProfile, onDismiss: () -> Unit, onRemove: (Boolean, Boolean) -> Unit) {
    var credentials by remember { mutableStateOf(true) }
    var cache by remember { mutableStateOf(true) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Remove ${host.machine}?") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Removing this profile does not revoke its authorized key on your computer. Remove that key on the computer separately if access must end.")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(credentials, { credentials = it }, Modifier.testTag("delete-credentials"))
                Text("Delete local credential")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(cache, { cache = it }, Modifier.testTag("delete-cache"))
                Text("Delete downloaded history and drafts")
            }
            Text("Kept history remains readable in Sessions and Mail without a connection. A credential shared with another saved connection is kept.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = { onRemove(credentials, cache) }, modifier = Modifier.testTag("confirm-remove")) { Text("Remove connection") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable private fun PairHostDialog(model: SettingsViewModel, repair: HostProfile?, progress: PairingProgress,
    busy: Boolean, onDismiss: () -> Unit) {
    // Deliberately not saveable: invitations and their tokens must not enter saved activity state.
    var invitation by remember { mutableStateOf("") }
    var device by remember { mutableStateOf("phone") }
    BackHandler { invitation = ""; onDismiss() }
    AlertDialog(onDismissRequest = { invitation = ""; onDismiss() }, title = { Text(if (repair == null) "Pair a host" else "Re-pair ${repair.machine}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("On your computer, start tuios pairing and paste its invitation. Keep Tailscale connected on both devices.")
                if (repair != null) Text("This replaces the saved connection and deletes its old local credential after successful pairing. Its old authorized key remains on your computer until you remove it there.")
                OutlinedTextField(invitation, { invitation = it.take(8192) }, label = { Text("Pairing invitation") }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().testTag("pair-invitation"), visualTransformation = PasswordVisualTransformation(), maxLines = 4)
                OutlinedTextField(device, { device = it.take(64) }, label = { Text("Device name") }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().testTag("pair-device"), singleLine = true)
                if (progress is PairingProgress.AwaitingComparison) PairingComparison(progress)
                else Text("Compare the check and fingerprints shown here with your computer before approving there.", style = MaterialTheme.typography.bodySmall)
                if (busy) Text("Waiting for the computer. Closing this dialog does not retry or cancel a possibly submitted approval.")
            }
        }, confirmButton = { TextButton(enabled = !busy && invitation.isNotBlank() && device.isNotBlank(), onClick = {
            val input = PairInvitation(invitation, device); invitation = ""
            model.pair(input, repair?.id)
        }, modifier = Modifier.testTag("start-pairing")) { Text("Start pairing") } },
        dismissButton = { TextButton(onClick = { invitation = ""; onDismiss() }) { Text(if (busy) "Close" else "Cancel") } })
}

@Composable private fun PairingComparison(progress: PairingProgress.AwaitingComparison) {
    SettingsGroup("Compare on both devices") {
        Text("Check: ${progress.check}", style = MaterialTheme.typography.titleLarge)
        Text("Phone: ${progress.phoneFingerprint}")
        Text("Computer: ${progress.hostFingerprint}")
        Text("Approve manually on your computer only after all values match.")
    }
}

private fun connectionLabel(state: ConnectionState): String = when (state) {
    is ConnectionState.Connected -> "Connected to ${state.host.machine}"
    ConnectionState.Connecting -> "Connecting…"
    ConnectionState.Disconnected -> "Offline"
    ConnectionState.HostKeyReviewRequired -> "Host key changed. Verify your computer, then re-pair deliberately."
    ConnectionState.RepairRequired -> "Local credential unavailable. Re-pair this host."
    ConnectionState.AuthenticationFailed -> "Authentication failed. Check authorization on your computer or re-pair."
    ConnectionState.PermissionDenied -> "Permission denied. Check list, mail, and respond grants on your computer."
    ConnectionState.Failed -> "Connection unavailable. Check Tailscale and your computer."
}

private fun SettingsResult.message(): String = when (this) {
    SettingsResult.Saved -> "Settings updated."
    SettingsResult.Cleared -> "Downloaded history cleared. Available remote history can be downloaded again."
    SettingsResult.Removed -> "Connection removed from this phone."
    SettingsResult.Paired -> "Host paired and selected."
    SettingsResult.InvalidInvitation -> "Invitation is invalid. Use a fresh invitation from your computer."
    SettingsResult.Rejected -> "Pairing was rejected or expired. Check your computer before trying a fresh invitation."
    SettingsResult.VerificationFailed -> "Pairing could not be verified. Check your computer before trying again."
    SettingsResult.Uncertain -> "Pairing may have authorized a key. Check your computer. This attempt will not be retried."
    SettingsResult.Failed -> "The action could not be completed. Your computer's data and authorization are managed there."
}
