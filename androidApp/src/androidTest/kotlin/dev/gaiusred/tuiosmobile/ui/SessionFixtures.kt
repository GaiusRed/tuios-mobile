package dev.gaiusred.tuiosmobile.ui

import dev.gaiusred.tuiosmobile.connection.*
import dev.gaiusred.tuiosmobile.identity.*
import dev.gaiusred.tuiosmobile.sessions.*
import dev.gaiusred.tuiosmobile.storage.*
import kotlinx.coroutines.flow.*
import kotlin.time.Instant

/** Synthetic display data only. No production seeding or transport. */
internal class SessionFixtures(private val unsupported: Boolean = false) : SessionRepository {
    val host = HostProfile(HostId("fixture-host"), "Studio Mac", "fixture", "Phone", emptyList(), "fixture-only", "fixture")
    val target = AgentTarget(host.id, "studio", "pane", "boot", "agent")
    val saved = RetainedConversation(RetainedConversationTarget(RecordKey(host.id, "studio", "old-pane", "old-boot", "run-1")),
        "Mobile client", "Review", "earlier-agent", "codex", Instant.parse("2026-11-05T07:40:00Z"))
    private val time = Instant.parse("2026-11-05T08:24:00Z")
    override val state = MutableStateFlow(SessionSnapshot(host.id, listOf(
        SessionSummary("studio", "Mobile client", 2, true, listOf(
            AgentSummary(target, "Build", "Working", "codex", false, true),
            AgentSummary(target.copy(windowId = "review", agentSessionId = "review"), "Review", "Waiting for you", "claude", true, true))),
        SessionSummary("notes", "Field notes", 1, false, listOf(
            AgentSummary(target.copy(sessionId = "notes", windowId = "notes", agentSessionId = "notes"), "Research", "Ready", "codex", false, true))),
    ), offline = true, lastSyncedAt = time))
    val entries = listOf(
        ConversationEntry("one", CachedContent.Transcript("user", "text", "Can you check the session list on the smaller screen?"), time),
        ConversationEntry("two", CachedContent.Transcript("assistant", "text", "The session list is ready. I kept saved conversations available when your host is offline."), time),
        ConversationEntry("three", CachedContent.Transcript("assistant", "tool_call", "Checked layout and navigation.", tool = "Layout review", status = "Complete"), time),
        ConversationEntry("four", CachedContent.Transcript("assistant", "future_kind", "A new kind of entry can still be read here."), time),
        ConversationEntry("five", CachedContent.Transcript("assistant", "plan", "Next steps", plan = "Check light and dark appearance", todos = listOf(CachedTodo("Review saved conversations", "done"))), time),
    )
    var olderRequests = 0
    override suspend fun refresh() = Unit
    override fun conversation(target: AgentTarget) = flowOf(ConversationSnapshot(target,
        if (unsupported) emptyList() else entries,
        if (unsupported) TranscriptAvailability.Unavailable else TranscriptAvailability.Available, true, true))
    override suspend fun loadOlder(target: AgentTarget) { olderRequests++ }
    override fun retainedConversations(hostId: HostId) = flowOf(if (hostId == host.id) listOf(saved) else emptyList())
    override fun retainedConversation(target: RetainedConversationTarget) = flowOf(RetainedConversationSnapshot(target,
        saved.takeIf { it.target == target }, if (target == saved.target) listOf(
            ConversationEntry("earlier", CachedContent.Transcript("assistant", "text", "Earlier saved message from this run."), time)
        ) else emptyList()))
}
