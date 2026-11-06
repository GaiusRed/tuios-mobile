package dev.gaiusred.tuiosmobile.sessions

import dev.gaiusred.tuiosmobile.sync.*
import dev.gaiusred.tuiosmobile.storage.*
import dev.gaiusred.tuiosmobile.identity.HostId
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*
import kotlinx.serialization.json.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SessionRepositoryTest {
    @Test fun expandableAndUnknownContentSurvivesOfflineStorage() = runTest {
        val daemon = ScriptedDaemon()
        val rich = Json.parseToJsonElement("""{"id":"detail","role":"tool","kind":"future-tool-kind","text":"safe fallback","tool":"apply_patch","target":"a.kt","status":"ok","tool_id":"call-1","plan":"review this plan","todos":[{"text":"check output","status":"pending"}],"diff":{"file":"a.kt","added":1,"removed":0,"hunks":[{"old_start":1,"new_start":1,"lines":[{"op":"+","text":"new line"}]}]},"human_nonce":"DO-NOT-STORE","unknown_private":{"data":"DO-NOT-STORE"}}""").jsonObject
        daemon.transcript = { page(listOf(rich), "opaque") }
        val store = TestHistoryStore()
        val sync = ForegroundSync(TestHostConnection(daemon), store, backgroundScope,
            MutableStateFlow<HostId?>(daemon.hostId)) { receiptTime }
        sync.setForeground(true); runCurrent()
        val target = sync.state.value.sessions.single().agents.single().target
        sync.setForeground(false)
        val content = sync.conversation(target).first().entries.single().content
        val encoded = Json.encodeToString<CachedContent>(content)
        assertFalse("DO-NOT-STORE" in encoded)
        assertEquals("safe fallback", content.text)
        // Decode the cached display object independently, so the test also covers persistent shape.
        val cached = Json.parseToJsonElement(encoded).jsonObject
        assertEquals("apply_patch", cached["tool"]?.jsonPrimitive?.content)
        assertEquals("review this plan", cached["plan"]?.jsonPrimitive?.content)
        assertEquals("new line", cached["diff"]?.jsonObject?.get("hunks")?.jsonArray?.first()?.jsonObject
            ?.get("lines")?.jsonArray?.first()?.jsonObject?.get("text")?.jsonPrimitive?.content)
        assertEquals("check output", cached["todos"]?.jsonArray?.first()?.jsonObject?.get("text")?.jsonPrimitive?.content)
    }

    @Test fun coldOfflineSnapshotUsesPersistentCurrentPointer() = runTest {
        val daemon = ScriptedDaemon()
        val store = TestHistoryStore()
        val host = daemon.hostId
        val target = AgentTarget(host, "session-stable", "window-stable", "boot-A", "agent-life-1")
        val key = dev.gaiusred.tuiosmobile.identity.RecordKey(host, target.sessionId, target.windowId, "boot-A", "generation-current")
        store.saveCheckpoint(SyncCheckpoint(host, "boot-A", sessions = listOf(CachedSession(target.sessionId, "offline", 1, false)),
            agents = listOf(CachedAgent(target.sessionId, target.windowId, "agent", "working", "future", target.agentSessionId,
                false, false, TranscriptCheckpoint("generation-current", "f", "older"), TranscriptAvailability.Available))))
        store.apply(HistoryBatch(key, listOf(CachedEntry("a", CachedContent.Transcript("assistant", "text", "current"), receiptTime))))
        store.apply(HistoryBatch(key.copy(transcriptId = "old-generation"), listOf(CachedEntry("b", CachedContent.Transcript("assistant", "text", "old page received later"), receiptTime))))
        val connection = TestHostConnection(daemon)
        connection.state.value = dev.gaiusred.tuiosmobile.connection.ConnectionState.Disconnected
        val repo: SessionRepository = ForegroundSync(connection, store, backgroundScope, MutableStateFlow<HostId?>(host)) { receiptTime }
        runCurrent()
        assertEquals("offline", repo.state.value.sessions.single().name)
        val snapshot = repo.conversation(target).first()
        assertTrue(snapshot.offline)
        assertEquals(listOf("current"), snapshot.entries.map { it.content.text })
        assertTrue(daemon.requests.isEmpty())
    }

    @Test fun missingTranscriptIsFeatureLocal() = runTest {
        val daemon = ScriptedDaemon(); daemon.transcriptFailure = "unsupported_harness"
        val repo = ForegroundSync(TestHostConnection(daemon), TestHistoryStore(), backgroundScope,
            MutableStateFlow<HostId?>(daemon.hostId)) { receiptTime }
        repo.setForeground(true); runCurrent()
        val target = repo.state.value.sessions.single().agents.single().target
        assertEquals(TranscriptAvailability.Unavailable, repo.conversation(target).first().availability)
        assertNull(repo.state.value.problem)
        assertEquals("unknown-future-harness", repo.state.value.sessions.single().agents.single().harnessId)
        repo.setForeground(false)
    }
}
