package dev.gaiusred.tuiosmobile.sessions

import dev.gaiusred.tuiosmobile.identity.*
import dev.gaiusred.tuiosmobile.storage.*
import kotlin.test.*
import kotlin.time.Instant

class DisplayTimestampTest {
    @Test fun snapshotsExposeStoredTimesWithoutInventingServerTime() {
        val host = HostId("host")
        val received = Instant.parse("2026-11-05T08:00:00Z")
        val server = Instant.parse("2026-11-05T07:59:00Z")
        val key = RecordKey(host, "session", "pane", "boot", "generation")
        val snapshot = HistorySnapshot(records = listOf(
            HistoryRecord(key, CachedEntry("metadata", CachedContent.TranscriptGeneration("Session", "Agent", "agent", "codex"), received)),
            HistoryRecord(key, CachedEntry("one", CachedContent.Transcript("assistant", "text", "hello"), received, server)),
            HistoryRecord(key, CachedEntry("two", CachedContent.Transcript("user", "text", "hi"), received)),
        ), checkpoint = SyncCheckpoint(host, "boot", agents = listOf(CachedAgent("session", "pane", "Agent", "idle", "codex", "agent", false, true,
            transcript = TranscriptCheckpoint("generation", null, null))), receivedSeconds = received.epochSeconds))
        assertEquals(received, snapshot.sessions(host, true, null).lastSyncedAt)
        val live = snapshot.conversation(AgentTarget(host, "session", "pane", "boot", "agent"), true)
        assertEquals(listOf(server, null), live.entries.map { it.serverAt })
        assertEquals(listOf(server, null), snapshot.retainedConversation(RetainedConversationTarget(key)).entries.map { it.serverAt })
        assertEquals(received, snapshot.retainedConversations().single().lastDownloadedAt)
    }
}
