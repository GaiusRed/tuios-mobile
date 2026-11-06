package dev.gaiusred.tuiosmobile.sessions

import dev.gaiusred.tuiosmobile.connection.ConnectionState
import dev.gaiusred.tuiosmobile.identity.HostId
import dev.gaiusred.tuiosmobile.sync.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RetainedConversationTest {
    private class Fixture(private val test: TestScope) {
        val daemon = ScriptedDaemon()
        val store = TestHistoryStore()
        val connection = TestHostConnection(daemon)
        val selected = MutableStateFlow<HostId?>(daemon.hostId)
        val sync = ForegroundSync(connection, store, test.backgroundScope, selected) { receiptTime }
        suspend fun start() { sync.setForeground(true); test.runCurrent() }
        suspend fun offline(): SessionRepository {
            sync.setForeground(false); connection.state.value = ConnectionState.Disconnected
            return ForegroundSync(connection, store, test.backgroundScope, selected) { receiptTime }
        }
    }

    @Test fun resetKeepsBothGenerationsReadableAfterColdOfflineRestart() = runTest {
        val f = Fixture(this); f.start()
        val original = f.sync.retainedConversations(f.daemon.hostId).first().single()
        f.daemon.transcript = { page(listOf(entry("z", "replacement")), "reset-cursor", reset = true) }
        f.daemon.event("transcript", 21u, session = "display-name", window = "window-stable"); runCurrent()
        val offline = f.offline()
        val before = f.daemon.requests.size
        val retained = offline.retainedConversations(f.daemon.hostId).first()
        assertEquals(2, retained.size)
        assertEquals(2, retained.map { it.target }.distinct().size)
        assertEquals(listOf("initial"), offline.retainedConversation(original.target).first().entries.map { it.content.text })
        val replacement = retained.single { it.target != original.target }
        assertEquals(listOf("replacement"), offline.retainedConversation(replacement.target).first().entries.map { it.content.text })
        assertEquals("agent-life-1", original.agentSessionId)
        assertEquals("display-name", original.sessionName)
        assertEquals(before, f.daemon.requests.size, "Reading retained generations must not open protocol streams")
    }

    @Test fun replacedAndRemovedAgentRetainsItsOwnMetadataAndEntriesOffline() = runTest {
        val f = Fixture(this); f.start()
        f.daemon.agents = listOf(agent("agent-life-2", "replacement agent"))
        f.daemon.transcript = { page(listOf(entry("z", "second identity")), "agent-2") }
        f.daemon.event("agent-state", 21u); runCurrent()
        f.daemon.sessions = emptyList(); f.daemon.event("session-closed", 22u); runCurrent()
        assertTrue(f.sync.state.value.sessions.isEmpty())
        val offline = f.offline()
        val retained = offline.retainedConversations(f.daemon.hostId).first()
        assertEquals(setOf("agent-life-1", "agent-life-2"), retained.map { it.agentSessionId }.toSet())
        val original = retained.single { it.agentSessionId == "agent-life-1" }
        assertEquals("agent", original.agentName)
        assertEquals("display-name", original.sessionName)
        assertEquals("unknown-future-harness", original.harnessId)
        assertEquals("session-stable", original.target.key.sessionId)
        assertEquals("window-stable", original.target.key.paneId)
        assertEquals(listOf("initial"), offline.retainedConversation(original.target).first().entries.map { it.content.text })
        val replacement = retained.single { it.agentSessionId == "agent-life-2" }
        assertEquals("replacement agent", replacement.agentName)
        assertEquals(listOf("second identity"), offline.retainedConversation(replacement.target).first().entries.map { it.content.text })
    }

    @Test fun bootReplacementRetainsAddressableOriginalDaemonGenerationOffline() = runTest {
        val f = Fixture(this); f.start()
        f.sync.setForeground(false); runCurrent()
        f.daemon.boot = "boot-B"
        f.daemon.transcript = { page(listOf(entry("z", "new boot")), "boot-B-cursor") }
        f.sync.setForeground(true); runCurrent()
        val offline = f.offline()
        val retained = offline.retainedConversations(f.daemon.hostId).first()
        assertEquals(setOf("boot-A", "boot-B"), retained.map { it.target.key.daemonBootId }.toSet())
        val old = retained.single { it.target.key.daemonBootId == "boot-A" }
        assertEquals(listOf("initial"), offline.retainedConversation(old.target).first().entries.map { it.content.text })
    }
}
