package dev.gaiusred.tuiosmobile.ui.mail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.gaiusred.tuiosmobile.identity.HostId
import dev.gaiusred.tuiosmobile.mail.MailDraftTarget
import dev.gaiusred.tuiosmobile.storage.*

@Composable
internal fun SavedDraftScreen(draft: MailDraft?, hostName: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TextButton(onClick = onBack, modifier = Modifier.testTag("mail-back")) { Text("‹ Mail") }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Saved draft", style = MaterialTheme.typography.headlineMedium)
            Text("$hostName · Read only", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (draft == null) Text("This saved draft is no longer available.") else {
                Text("Session: ${draft.sessionId}", style = MaterialTheme.typography.bodySmall)
                Text(if (draft.generation == MailDraftGeneration.Legacy) "Legacy draft · Generation not recorded"
                    else "Saved generation · ${draft.daemonBootId}", style = MaterialTheme.typography.bodySmall)
                SavedDraftContent(draft)
            }
        }
    }
}

@Composable
internal fun SavedDraftContent(draft: MailDraft) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("${savedDraftLabel(draft)} · To: ${draft.recipient}", style = MaterialTheme.typography.titleMedium)
        when (draft.status) {
            MailDraftStatus.Uncertain -> Text("May have been sent. Check the computer before starting another message.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            MailDraftStatus.Sent -> Text("Saved send attempt. This view cannot send again.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> Unit
        }
        SelectionContainer { Text(draft.text, Modifier.testTag("saved-draft-body"), style = MaterialTheme.typography.bodyLarge) }
    }
}

internal fun savedDraftLabel(draft: MailDraft): String = when (draft.status) {
    MailDraftStatus.Uncertain -> "Possibly sent"
    MailDraftStatus.Sent -> "Sent"
    MailDraftStatus.Rejected -> "Rejected"
    MailDraftStatus.Draft -> if (draft.generation == MailDraftGeneration.Legacy) "Draft to review" else "Draft"
}

/** Saved activity state contains identity only; Legacy never becomes an invented daemon boot. */
internal fun MailDraftTarget.toLocalRoute(): List<String> = listOf(hostId.value, sessionId,
    if (generation == MailDraftGeneration.Legacy) "legacy" else "boot", (generation as? MailDraftGeneration.Boot)?.id.orEmpty(),
    recipient, if (threadId == null) "none" else "thread", threadId.orEmpty())

internal fun List<String>.toLocalDraftTarget(): MailDraftTarget? {
    if (size != 7 || this[0].isBlank() || this[1].isBlank()) return null
    val generation = when (this[2]) {
        "legacy" -> if (this[3].isEmpty()) MailDraftGeneration.Legacy else return null
        "boot" -> MailDraftGeneration.Boot(this[3])
        else -> return null
    }
    val thread = when (this[5]) { "none" -> null; "thread" -> this[6]; else -> return null }
    return MailDraftTarget(HostId(this[0]), this[1], generation, this[4], thread)
}
