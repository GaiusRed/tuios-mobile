package dev.gaiusred.tuiosmobile.ui

import dev.gaiusred.tuiosmobile.identity.*
import dev.gaiusred.tuiosmobile.sessions.*
import dev.gaiusred.tuiosmobile.ui.conversation.ConversationSource

/** Only stable identity and display labels enter saved instance state; no wire cursors or proofs. */
internal fun ConversationSource.toRoute(): List<String> = when (this) {
    is ConversationSource.Live -> listOf("live", target.hostId.value, target.sessionId, target.windowId,
        target.daemonBootId, target.agentSessionId, agentName, sessionName, hostName)
    is ConversationSource.Saved -> listOf("saved", target.key.hostId.value, target.key.sessionId,
        requireNotNull(target.key.paneId), target.key.daemonBootId, requireNotNull(target.key.transcriptId),
        agentName, sessionName, hostName, agentSessionId)
}

internal fun List<String>.toConversationSource(): ConversationSource? {
    if (isEmpty()) return null
    return when (first()) {
        "live" -> if (size == 9) ConversationSource.Live(AgentTarget(HostId(get(1)), get(2), get(3), get(4), get(5)),
            get(6), get(7), get(8)) else null
        "saved" -> if (size == 10) ConversationSource.Saved(
            RetainedConversationTarget(RecordKey(HostId(get(1)), get(2), get(3), get(4), get(5))), get(6), get(7), get(8), get(9)) else null
        else -> null
    }
}
