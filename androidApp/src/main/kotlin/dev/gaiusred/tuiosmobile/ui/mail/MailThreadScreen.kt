package dev.gaiusred.tuiosmobile.ui.mail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.gaiusred.tuiosmobile.mail.*
import dev.gaiusred.tuiosmobile.storage.*
import dev.gaiusred.tuiosmobile.ui.design.displayTime
import dev.gaiusred.tuiosmobile.ui.sessions.IdentityMark

@Composable
fun MailThreadScreen(model: MailViewModel, row: MailboxRow, threadId: String?, initialRecipient: String?, onBack: () -> Unit,
    legacyMode: Boolean = false, legacyDraft: MailDraft? = null, onAdopted: (String) -> Unit = {}) {
    val snapshot = row.snapshot
    val messages = if (legacyMode) emptyList() else snapshot.messages.filter { it.content.threadId == threadId }
    val last = messages.lastOrNull()
    val suggestedRecipient = messages.lastOrNull { it.content.from != "human" }?.content?.from
        ?: last?.content?.to?.takeIf { it != "human" }
    var recipientId by rememberSaveable(snapshot.target, threadId, initialRecipient) { mutableStateOf(initialRecipient ?: suggestedRecipient) }
    val sending by model.sending.collectAsState()
    val problem by model.problem.collectAsState()
    LaunchedEffect(snapshot.target, threadId, messages.map { it.id }) {
        model.markRead(snapshot.target, messages.map { it.id }.toSet())
    }
    val savedDraft = snapshot.drafts.find { it.recipient == recipientId && it.threadId == threadId }
    val draft = legacyDraft?.copy(recipient = recipientId.orEmpty()) ?: savedDraft ?: MailDraft(snapshot.target.hostId, snapshot.target.sessionId, recipientId.orEmpty(), threadId,
        text = "", replyTo = last?.id, daemonBootId = snapshot.target.daemonBootId)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, modifier = Modifier.testTag("mail-back")) { Text("‹ Mail") }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(snapshot.sessionName, style = MaterialTheme.typography.titleMedium)
                Text(row.hostName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(72.dp))
        }
        Text(if (snapshot.readOnly) "Saved Mail · Read only" else if (snapshot.offline) "Offline · Saved Mail" else "Connected · Mail",
            Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("mail-messages"), contentPadding = PaddingValues(vertical = 12.dp)) {
            if (snapshot.historyGap) item { Text("Some older Mail is no longer available on the host.", Modifier.padding(18.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (messages.isEmpty() && !snapshot.readOnly) item {
                Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    IdentityMark(snapshot.recipients.find { it.id == recipientId }?.name ?: "Mail")
                    Text("New message", style = MaterialTheme.typography.titleLarge)
                    Text("Choose an agent in this session. Mail stays separate from the agent's prompt.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
            }
            items(messages, key = { it.id }) { item -> MailBubble(item) }
            if (snapshot.readOnly) {
                val retainedDraft = legacyDraft ?: savedDraft
                if (retainedDraft != null) item {
                    Column(Modifier.padding(18.dp)) { SavedDraftContent(retainedDraft) }
                } else if (messages.isEmpty()) item {
                    Text("No downloaded messages in this thread.", Modifier.padding(18.dp))
                }
            }
        }
        if (!snapshot.canSend) Text("This daemon does not support sending Mail.", Modifier.padding(horizontal = 18.dp))
        if (problem != null || snapshot.problem != null) Text(problem ?: if (snapshot.problem == MailProblem.Unsupported)
            "This daemon does not support this Mail operation." else "Mail permission is unavailable for this session.",
            Modifier.padding(horizontal = 18.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall)
        if (legacyMode && legacyDraft == null) Text("This saved draft is no longer available.", Modifier.padding(18.dp))
        else if (!snapshot.readOnly) MailComposer(draft, snapshot.recipients, snapshot.offline || !snapshot.canSend, sending,
            onRecipient = { recipientId = it }, onSave = model::save, onSend = model::send, onDiscard = model::discard,
            onAdopt = legacyDraft?.let { original -> { recipientId?.let { recipient ->
                model.adopt(original, snapshot.target, recipient) { onAdopted(recipient) }
            } } })
    }
}

@Composable
private fun MailBubble(item: MailItem) {
    val mail = item.content
    val outgoing = mail.from == "human"
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 7.dp),
        horizontalAlignment = if (outgoing) Alignment.End else Alignment.Start) {
        Text("${mail.fromLabel.ifBlank { mail.from }} → ${mail.toLabel.ifBlank { mail.to }}",
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(Modifier.widthIn(max = 520.dp).fillMaxWidth(0.88f),
            shape = RoundedCornerShape(topStart = 21.dp, topEnd = 21.dp,
                bottomStart = if (outgoing) 21.dp else 6.dp, bottomEnd = if (outgoing) 6.dp else 21.dp),
            color = if (outgoing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
            contentColor = if (outgoing) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer) {
            Column(Modifier.padding(horizontal = 15.dp, vertical = 11.dp)) {
                if (item.missingParent) Text("Earlier reply is missing", style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(bottom = 6.dp))
                mail.subject?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 6.dp))
                }
                Text(mail.text, style = MaterialTheme.typography.bodyLarge)
            }
        }
        val source = when {
            mail.verifiedHuman -> "Verified human"
            mail.claimedHuman -> "Claimed human · Unverified"
            outgoing -> "Human · Unverified"
            else -> null
        }
        source?.let { Text(it, Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item.sentAt?.let { Text(displayTime(it), Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
