package dev.gaiusred.tuiosmobile.ui.mail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.gaiusred.tuiosmobile.mail.MailRecipient
import dev.gaiusred.tuiosmobile.storage.*

@Composable
fun MailComposer(draft: MailDraft, recipients: List<MailRecipient>, offline: Boolean, sending: Boolean,
    onRecipient: (String) -> Unit, onSave: (MailDraft) -> Unit, onSend: (MailDraft) -> Unit, onDiscard: (MailDraft) -> Unit,
    onAdopt: (() -> Unit)? = null) {
    var text by rememberSaveable(draft.hostId.value, draft.sessionId, draft.daemonBootId, draft.recipient, draft.threadId, draft.status) { mutableStateOf(draft.text) }
    var edited by rememberSaveable(draft.hostId.value, draft.sessionId, draft.daemonBootId, draft.recipient, draft.threadId, draft.status) { mutableStateOf(false) }
    LaunchedEffect(draft.text) { if (!edited) text = draft.text }
    var picker by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmAdoption by remember { mutableStateOf(false) }
    val legacy = draft.generation == MailDraftGeneration.Legacy
    val locked = sending || draft.status == MailDraftStatus.Uncertain || draft.status == MailDraftStatus.Sent
    val recipient = recipients.find { it.id == draft.recipient }
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
        Box {
            TextButton(onClick = { picker = true }, enabled = !locked) { Text(recipient?.let { "To: ${it.name}" } ?: "Choose recipient") }
            DropdownMenu(picker, onDismissRequest = { picker = false }) {
                recipients.forEach { agent -> DropdownMenuItem(text = {
                    Column { Text(agent.name); Text(agent.id, style = MaterialTheme.typography.labelSmall) }
                }, onClick = { picker = false; onRecipient(agent.id) }, modifier = Modifier.testTag("mail-recipient-${agent.id}")) }
            }
        }
        when (draft.status) {
            MailDraftStatus.Uncertain -> Text("May have been sent. Check Mail before starting another message.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            MailDraftStatus.Sent -> Text("Sent · Verified human", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            MailDraftStatus.Rejected -> Text("Rejected · Not sent", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
            MailDraftStatus.Draft -> if (legacy) Text("Choose a current recipient to use this saved draft.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else if (offline) Text("Draft saved on this phone. Send when connected.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(text, { value -> edited = true; text = value; onSave(draft.copy(text = value)) },
                Modifier.weight(1f).heightIn(max = 150.dp).testTag("mail-input"), enabled = !locked && !legacy && recipient != null,
                placeholder = { Text("Mail message") }, shape = RoundedCornerShape(22.dp), maxLines = 5)
            TextButton(onClick = { onSend(draft.copy(text = text)) },
                enabled = !locked && !legacy && !offline && recipient != null && text.isNotBlank(),
                modifier = Modifier.testTag("mail-send")) { Text(if (sending) "Sending…" else "Send") }
        }
        if (legacy && onAdopt != null) TextButton(onClick = { confirmAdoption = true },
            enabled = !locked && !offline && recipient != null) { Text("Use in current session") }
        if (draft.status == MailDraftStatus.Uncertain || draft.status == MailDraftStatus.Sent) {
            TextButton(onClick = { if (draft.status == MailDraftStatus.Uncertain) confirmDiscard = true else onDiscard(draft) }) {
                Text(if (draft.status == MailDraftStatus.Uncertain) "Discard saved attempt" else "Start another message")
            }
        }
    }
    if (confirmAdoption) AlertDialog(onDismissRequest = { confirmAdoption = false },
        title = { Text("Use this draft in the current session?") },
        text = { Text("This starts a new Mail message to ${recipient?.name.orEmpty()}. The previous reply link will be cleared.") },
        confirmButton = { TextButton(onClick = { confirmAdoption = false; onAdopt?.invoke() }) { Text("Use draft") } },
        dismissButton = { TextButton(onClick = { confirmAdoption = false }) { Text("Cancel") } })
    if (confirmDiscard) AlertDialog(onDismissRequest = { confirmDiscard = false },
        title = { Text("Discard this saved attempt?") },
        text = { Text("The message may already be on the host. This clears the local attempt and does not undo a send.") },
        confirmButton = { TextButton(onClick = { confirmDiscard = false; onDiscard(draft) }) { Text("Discard") } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep") } })
}
