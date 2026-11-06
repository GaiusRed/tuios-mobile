package dev.gaiusred.tuiosmobile.sessions

import dev.gaiusred.tuiosmobile.identity.RecordKey
import kotlin.time.Instant

/** Read-only local identity, separate from the active target used for network actions. */
data class RetainedConversationTarget(val key: RecordKey) {
    init { require(key.paneId != null && key.transcriptId != null) }
}

data class RetainedConversation(val target: RetainedConversationTarget, val sessionName: String,
    val agentName: String, val agentSessionId: String, val harnessId: String, val lastDownloadedAt: Instant? = null)

data class RetainedConversationSnapshot(val target: RetainedConversationTarget,
    val conversation: RetainedConversation? = null, val entries: List<ConversationEntry> = emptyList())
