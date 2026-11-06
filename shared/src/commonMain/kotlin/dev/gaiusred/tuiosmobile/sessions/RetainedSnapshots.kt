package dev.gaiusred.tuiosmobile.sessions

import dev.gaiusred.tuiosmobile.storage.*

/** Generations are addressable by stored identity; the current checkpoint is irrelevant here. */
internal fun HistorySnapshot.retainedConversations(): List<RetainedConversation> {
    val downloaded = records.filter { it.entry.content is CachedContent.Transcript }.groupBy { it.key }
        .mapValues { (_, entries) -> entries.maxOf { it.entry.receivedAt } }
    return records.mapNotNull { record ->
        val identity = record.entry.content as? CachedContent.TranscriptGeneration
        if (identity == null || record.key !in downloaded) null else RetainedConversation(
            RetainedConversationTarget(record.key), identity.sessionName, identity.agentName,
            identity.agentSessionId, identity.harnessId, downloaded[record.key])
    }
}

internal fun HistorySnapshot.retainedConversation(target: RetainedConversationTarget): RetainedConversationSnapshot {
    val summary = retainedConversations().find { it.target == target }
    val entries = if (summary == null) emptyList() else records.filter { it.key == target.key }.mapNotNull { record ->
        (record.entry.content as? CachedContent.Transcript)?.let { ConversationEntry(record.entry.id, it, record.entry.serverAt) }
    }
    return RetainedConversationSnapshot(target, summary, entries)
}
