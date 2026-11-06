package dev.gaiusred.tuiosmobile.ui.attention

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import dev.gaiusred.tuiosmobile.attention.*
import dev.gaiusred.tuiosmobile.sessions.*

@Composable
fun AttentionPanel(repository: AttentionRepository, target: AgentTarget, snapshot: SessionSnapshot) {
    val scope = rememberCoroutineScope()
    val model = remember(repository, target) { AttentionViewModel(repository, target, scope) }
    val items by model.items.collectAsState()
    val sending by model.sending.collectAsState()
    val problem by model.problem.collectAsState()
    LaunchedEffect(snapshot) { model.refresh() }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (items.isNotEmpty() || problem != null) {
            TextButton(onClick = model::refresh, enabled = !snapshot.offline && !sending,
                modifier = Modifier.padding(horizontal = 16.dp)) { Text("Refresh questions") }
        }
        if (problem != null) Text(problem!!, Modifier.padding(horizontal = 22.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        items.forEach { item -> key(item.id, item.requestId, item.promptId) { AttentionCard(item, sending, model::respond) } }
    }
}

@Composable
fun AttentionCard(item: AttentionItem, sending: Boolean, onRespond: (HumanResponse) -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("attention-${item.id}"),
        shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.07f)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (item.kind == "ask" || item.kind == "question") "Question" else "Approval",
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text("${item.agentName} · ${item.sessionName}", style = MaterialTheme.typography.labelLarge)
            Text("${item.target.hostId.value} · ${item.target.windowId}", style = MaterialTheme.typography.labelSmall)
            Text(item.summary, style = MaterialTheme.typography.bodyLarge)
            if (item.tool.isNotBlank()) Text("Tool: ${item.tool}")
            if (item.resource.isNotBlank()) Text("Target: ${item.resource}")
            // The complete daemon detail remains readable; no ellipsis or locally shortened plan.
            if (item.plan.isNotBlank()) Text(item.plan)
            if (item.alwaysScope.isNotEmpty()) Text("Always allows:\n${item.alwaysScope.joinToString("\n")}")
            when {
                item.uncertain -> Text("The response may have been applied. Refresh to check; it will not be sent again.",
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                item.answered -> Text("Response applied. Refresh to check the current state.")
                !item.enabled -> Text("Offline or no longer current. Reconnect and refresh before answering.")
            }
            ResponseEditor(item, sending, onRespond)
        }
    }
}
