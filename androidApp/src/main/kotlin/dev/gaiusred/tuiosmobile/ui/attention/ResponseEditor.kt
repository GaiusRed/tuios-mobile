package dev.gaiusred.tuiosmobile.ui.attention

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import dev.gaiusred.tuiosmobile.attention.*

@Composable
fun ResponseEditor(item: AttentionItem, sending: Boolean, onRespond: (HumanResponse) -> Unit) {
    val review = item.copy(enabled = false, uncertain = false, answered = false)
    var text by remember(review) { mutableStateOf("") }
    var denial by remember(review) { mutableStateOf("") }
    var acknowledged by remember(review) { mutableStateOf(emptySet<String>()) }
    var confirmation by remember(review) { mutableStateOf<HumanResponse?>(null) }
    var confirmationLabel by remember(review) { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val enabled = item.enabled && !sending && !item.uncertain && !item.answered
    val risksAcknowledged = acknowledged == item.risks.map { it.rule }.toSet()
    item.risks.forEach { risk ->
        Row(Modifier.fillMaxWidth()) {
            Checkbox(risk.rule in acknowledged, { checked -> acknowledged = if (checked) acknowledged + risk.rule else acknowledged - risk.rule },
                enabled = enabled, modifier = Modifier.semantics { contentDescription = "Acknowledge risk ${risk.rule}: ${risk.why}" })
            Text("${risk.rule}${if (risk.why.isBlank()) "" else ": ${risk.why}"}", Modifier.weight(1f).padding(top = 12.dp))
        }
    }
    fun confirm(action: String, value: String? = null, label: String = action) {
        focus.clearFocus()
        confirmationLabel = label
        confirmation = HumanResponse(item, action, value, acknowledged)
    }
    if (item.kind == "ask") {
        item.actions.forEach { answer ->
            OutlinedButton(onClick = { confirm(answer) }, enabled = enabled,
                modifier = Modifier.fillMaxWidth().testTag("response-$answer")) { Text(answer) }
        }
    } else {
        if ("text" in item.actions && item.promptId != null) {
            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().testTag("response-input"),
                enabled = enabled, label = { Text("Answer this question") }, minLines = 1, maxLines = 4)
            Button(onClick = { confirm("text", text) }, enabled = enabled && risksAcknowledged && text.isNotBlank(),
                modifier = Modifier.fillMaxWidth().testTag("response-text")) { Text("Review answer") }
        }
        if (item.denyMessage && "deny" in item.actions) OutlinedTextField(denial, { denial = it },
            Modifier.fillMaxWidth(), enabled = enabled, label = { Text("Reason for denying (optional)") }, maxLines = 4)
        item.actions.filter { it != "text" }.forEach { action ->
            if (action == "choose" && item.promptId != null) item.promptOptions.forEach { option ->
                OutlinedButton(onClick = { confirm(action, option.n.toString(), "choose: ${option.label}") },
                    enabled = enabled && risksAcknowledged, modifier = Modifier.fillMaxWidth().testTag("response-choose-${option.n}")) {
                    Text("${option.n}. ${option.label}")
                }
            } else if ((item.promptId == null && action in setOf("once", "always", "deny")) ||
                (item.promptId != null && action in setOf("approve", "approve_always", "deny"))) {
                OutlinedButton(onClick = { confirm(action, denial.takeIf { action == "deny" && item.denyMessage }) },
                    enabled = enabled && (action == "deny" || risksAcknowledged),
                    modifier = Modifier.fillMaxWidth().testTag("response-$action")) { Text(action.replace('_', ' ')) }
            }
        }
    }
    confirmation?.let { response ->
        AlertDialog(onDismissRequest = { confirmation = null }, title = { Text("Confirm $confirmationLabel") },
            text = { Column(Modifier.heightIn(max = 350.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${item.agentName} · ${item.sessionName}\n${item.target.hostId.value} · ${item.target.windowId}")
                Text(item.summary)
                response.value?.takeIf { it.isNotBlank() }?.let { Text(it) }
                if (response.action in setOf("always", "approve_always") && item.alwaysScope.isNotEmpty())
                    Text(item.alwaysScope.joinToString("\n"))
                if (response.riskAck.isNotEmpty()) Text("Risks acknowledged: ${response.riskAck.joinToString()}")
            } },
            confirmButton = { TextButton(onClick = { confirmation = null; onRespond(response) },
                enabled = enabled, modifier = Modifier.testTag("response-confirm")) { Text("Send $confirmationLabel") } },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text("Cancel") } })
    }
}
