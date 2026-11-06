package dev.gaiusred.tuiosmobile.ui.design

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.gaiusred.tuiosmobile.sessions.ConversationEntry

@Composable
fun MessageBubble(entry: ConversationEntry, agentName: String) {
    val content = entry.content
    val outgoing = content.role == "user" || content.role == "human"
    val details = content.kind in setOf("tool_call", "tool_result", "plan", "todos", "diff", "thinking") || content.diff != null
    val known = content.kind in setOf("text", "error", "tool_call", "tool_result", "plan", "todos", "diff", "thinking")
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 7.dp),
        horizontalAlignment = if (outgoing) Alignment.End else Alignment.Start) {
        Text(if (outgoing) "You" else if (content.role == "assistant") agentName else content.role.ifBlank { agentName },
            Modifier.padding(horizontal = 12.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(Modifier.widthIn(max = 520.dp).fillMaxWidth(if (details) 0.94f else 0.88f),
            shape = RoundedCornerShape(topStart = 21.dp, topEnd = 21.dp,
                bottomStart = if (outgoing) 21.dp else 6.dp, bottomEnd = if (outgoing) 6.dp else 21.dp),
            color = if (outgoing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
            contentColor = if (outgoing) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer) {
            Column(Modifier.padding(horizontal = 15.dp, vertical = 11.dp)) {
                if (!known) Text("Other activity", style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(bottom = 5.dp))
                if (details) ExpandableEntry(entry) else Text(content.text.ifBlank { "No display text" },
                    style = MaterialTheme.typography.bodyLarge)
                if (content.truncated) Text("Message shortened by the host", style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 7.dp))
            }
        }
        entry.serverAt?.let { Text(displayTime(it), Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun ExpandableEntry(entry: ConversationEntry) {
    val content = entry.content
    var expanded by rememberSaveable(entry.id) { mutableStateOf(false) }
    val title = listOfNotNull(content.tool ?: when (content.kind) {
        "tool_result" -> "Tool result"
        "tool_call" -> "Tool activity"
        "plan" -> "Plan"
        "todos" -> "Tasks"
        "thinking" -> "Reasoning"
        else -> content.diff?.file?.takeIf { it.isNotBlank() } ?: "Changes"
    }, content.status?.takeIf { it.isNotBlank() }).joinToString(" · ")
    TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp),
        modifier = Modifier.defaultMinSize(minHeight = 48.dp).semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Text(if (expanded) " −" else " +")
    }
    if (expanded) {
        if (content.text.isNotBlank()) Text(content.text, style = MaterialTheme.typography.bodyMedium)
        content.target?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
        content.plan?.let { Text(it, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyMedium) }
        content.todos.forEach { todo -> Text("${todo.status}: ${todo.text}", Modifier.padding(top = 7.dp), style = MaterialTheme.typography.bodyMedium) }
        content.diff?.let { diff ->
            Text("${diff.file}  +${diff.added} −${diff.removed}", Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.titleMedium)
            if (diff.wholeReplace) Text("Whole file replaced", style = MaterialTheme.typography.labelSmall)
            diff.hunks.forEach { hunk ->
                Text("Lines ${hunk.oldStart} → ${hunk.newStart}", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelSmall)
                hunk.lines.forEach { line ->
                    Text("${line.op} ${line.text}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (diff.truncated) Text("Diff shortened by the host", style = MaterialTheme.typography.labelSmall)
        }
    }
}
