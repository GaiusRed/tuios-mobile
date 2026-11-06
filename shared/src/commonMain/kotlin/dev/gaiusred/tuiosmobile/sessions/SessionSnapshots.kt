package dev.gaiusred.tuiosmobile.sessions

import dev.gaiusred.tuiosmobile.identity.HostId
import dev.gaiusred.tuiosmobile.identity.RecordKey
import dev.gaiusred.tuiosmobile.storage.*
import kotlin.time.Instant

internal fun HistorySnapshot.sessions(host: HostId?, offline: Boolean, problem: SyncProblem?): SessionSnapshot {
    val current = checkpoint ?: return SessionSnapshot(host, offline = offline, problem = problem)
    return SessionSnapshot(host, current.sessions.map { session ->
        SessionSummary(session.id, session.name, session.windowCount, session.attached,
            current.agents.filter { it.sessionId == session.id }.map { agent ->
                AgentSummary(AgentTarget(current.hostId, session.id, agent.windowId, current.bootId, agent.agentSessionId),
                    agent.name, agent.state, agent.harnessId, agent.needsYou, agent.ready)
            })
    }, offline, problem, Instant.fromEpochSeconds(current.receivedSeconds, current.receivedNanos))
}

internal fun HistorySnapshot.conversation(target: AgentTarget, offline: Boolean): ConversationSnapshot {
    val current = checkpoint?.takeIf { it.hostId == target.hostId && it.bootId == target.daemonBootId }
    val agent = current?.agents?.find { it.sessionId == target.sessionId && it.windowId == target.windowId &&
        it.agentSessionId == target.agentSessionId }
    val transcript = agent?.transcript
    val key = transcript?.let { RecordKey(target.hostId, target.sessionId, target.windowId, target.daemonBootId, it.generation) }
    return ConversationSnapshot(target, records.filter { key != null && it.key == key }.mapNotNull { record ->
        (record.entry.content as? CachedContent.Transcript)?.let { ConversationEntry(record.entry.id, it, record.entry.serverAt) }
    }, agent?.availability ?: TranscriptAvailability.NotLoaded, offline,
        !transcript?.older.isNullOrEmpty())
}
