package dev.gaiusred.tuiosmobile.sessions

import dev.gaiusred.tuiosmobile.identity.HostId
import dev.gaiusred.tuiosmobile.connection.ConnectionGeneration
import dev.gaiusred.tuiosmobile.storage.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Instant

interface SessionRepository {
    val state: StateFlow<SessionSnapshot>
    suspend fun refresh()
    fun conversation(target: AgentTarget): Flow<ConversationSnapshot>
    suspend fun loadOlder(target: AgentTarget)
    fun retainedConversations(hostId: HostId): Flow<List<RetainedConversation>>
    fun retainedConversation(target: RetainedConversationTarget): Flow<RetainedConversationSnapshot>
}

data class AgentTarget(val hostId: HostId, val sessionId: String, val windowId: String,
    val daemonBootId: String, val agentSessionId: String)

data class SessionSummary(val id: String, val name: String, val windowCount: Int,
    val attached: Boolean, val agents: List<AgentSummary>)

data class AgentSummary(val target: AgentTarget, val name: String, val state: String,
    val harnessId: String, val needsYou: Boolean, val ready: Boolean)

data class SessionSnapshot(val hostId: HostId? = null, val sessions: List<SessionSummary> = emptyList(),
    val offline: Boolean = true, val problem: SyncProblem? = null, val lastSyncedAt: Instant? = null,
    val verification: SessionVerification? = null, val mailRevision: Long? = null)

/** Runtime proof from boot discovery and successful session/agent listing; never restored from disk. */
data class SessionVerification(val hostId: HostId, val connectionGeneration: ConnectionGeneration,
    val daemonBootId: String, val verbs: Set<String>? = null) {
    fun supports(vararg required: String): Boolean = verbs?.containsAll(required.toList()) ?: true
}

enum class SyncProblem { ListingUnavailable, SubscriptionUnavailable, DiscoveryUnavailable, UnsupportedProtocol,
    SubscriptionUnsupported, ListingUnsupported }

data class ConversationEntry(val id: String, val content: CachedContent.Transcript, val serverAt: Instant? = null)

data class ConversationSnapshot(val target: AgentTarget, val entries: List<ConversationEntry> = emptyList(),
    val availability: TranscriptAvailability = TranscriptAvailability.NotLoaded,
    val offline: Boolean = true, val canLoadOlder: Boolean = false)
