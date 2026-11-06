package dev.gaiusred.tuiosmobile.sync

import dev.gaiusred.tuiosmobile.identity.HostId
import dev.gaiusred.tuiosmobile.sessions.AgentTarget
import dev.gaiusred.tuiosmobile.storage.*
import dev.gaiusred.tuiosmobile.mail.*
import dev.gaiusred.tuiosmobile.connection.ConnectionState
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ForegroundSyncTest {
    private class Fixture(val test: TestScope, val store: TestHistoryStore = TestHistoryStore()) {
        val daemon = ScriptedDaemon()
        val connection = TestHostConnection(daemon)
        val selected = MutableStateFlow<HostId?>(daemon.hostId)
        val sync = ForegroundSync(connection, store, test.backgroundScope, selected) { receiptTime }
        val mail by lazy { MailRepository(connection, store, sync, selected, test.backgroundScope) { receiptTime } }
        suspend fun start() { sync.setForeground(true); test.runCurrent() }
        fun target() = sync.state.value.sessions.first().agents.first().target
        suspend fun texts(target: AgentTarget = target()) = sync.conversation(target).first().entries.map { it.content.text }
    }

    @Test fun incomingMailAndGapRefreshWithoutChangingMailboxIdentity() = runTest {
        val f = Fixture(this); f.start()
        val target = f.target().let { MailTarget(it.hostId, it.sessionId, it.daemonBootId) }
        f.mail.refresh(target); runCurrent()
        f.daemon.mailText = "arrived while thread stayed open"
        f.daemon.mailId = 2
        f.daemon.event("agent-message", 21u); runCurrent()
        assertEquals("arrived while thread stayed open", f.mail.observe(target).first().messages.last().content.text)
        f.daemon.mailText = "recovered gap"
        f.daemon.mailId = 3
        f.daemon.event("gap", 50u); runCurrent()
        assertEquals("recovered gap", f.mail.observe(target).first().messages.last().content.text)
        assertEquals(listOf("1", "2", "3"), f.mail.observe(target).first().messages.map { it.id })
        assertFalse(f.daemon.requests.any { it.first == "send-agent-message" })
        f.sync.setForeground(false)
    }

    @Test fun cachedOfflineMailboxRefreshesWhenSameBootBecomesReady() = runTest {
        val f = Fixture(this); f.start()
        val target = f.target().let { MailTarget(it.hostId, it.sessionId, it.daemonBootId) }
        f.mail.refresh(target); f.sync.setForeground(false); runCurrent()
        f.daemon.mailText = "same target after reconnect"
        f.connection.state.value = ConnectionState.Connected(f.daemon.profile)
        f.sync.setForeground(true); runCurrent()
        assertEquals("same target after reconnect", f.mail.observe(target).first().messages.single().content.text)
        f.sync.setForeground(false)
    }

    @Test fun incompatibleProtocolStopsBeforeSubscriptionOrFeatureRequests() = runTest {
        val f = Fixture(this); f.daemon.protocol = 2; f.daemon.minProtocol = 2; f.start()
        assertEquals(listOf("hello"), f.daemon.requests.map { it.first })
        assertTrue(f.sync.state.value.offline)
        assertEquals("UnsupportedProtocol", f.sync.state.value.problem.toString())
        f.sync.setForeground(false)
    }

    @Test fun transcriptReadsReleasePresenceForInteractiveFeaturesAcrossManySessions() = runTest {
        val f = Fixture(this)
        f.daemon.sessions = (1..20).map { session("session-$it", "name-$it") }
        f.daemon.agents = (1..20).map { JsonObject(agent() + ("session" to JsonPrimitive("name-$it"))) }
        f.start()
        assertEquals(20, f.daemon.requests.count { it.first == "agent-transcript" })
        assertTrue(f.daemon.channels.count { !it.closed } <= 2, "Only listing and subscription streams may remain idle")
        f.sync.setForeground(false)
    }

    @Test fun missingSubscriptionReportsUnsupportedWithoutRetryOrInventedBootProof() = runTest {
        val f = Fixture(this); f.daemon.verbs -= "subscribe"; f.start()
        advanceTimeBy(30_000); runCurrent()
        assertEquals(listOf("hello", "list-verbs"), f.daemon.requests.map { it.first })
        assertNull(f.sync.state.value.verification)
        assertEquals("SubscriptionUnsupported", f.sync.state.value.problem.toString())
        f.sync.setForeground(false)
    }

    @Test fun advertisedButRejectedSubscriptionStopsWithoutEndlessRetryOrBootProof() = runTest {
        val f = Fixture(this); f.daemon.rejectSubscription = true; f.start()
        advanceTimeBy(30_000); runCurrent()
        assertEquals(1, f.daemon.requests.count { it.first == "subscribe" })
        assertEquals("SubscriptionUnsupported", f.sync.state.value.problem.toString())
        assertNull(f.sync.state.value.verification)
        f.sync.setForeground(false)
    }

    @Test fun missingTranscriptKeepsVerifiedMailAndDiscoveryRunsForEveryConnection() = runTest {
        val f = Fixture(this); f.daemon.verbs -= "agent-transcript"; f.start()
        assertFalse(f.sync.state.value.offline)
        assertEquals(TranscriptAvailability.Unavailable, f.sync.conversation(f.target()).first().availability)
        assertFalse(f.daemon.requests.any { it.first == "agent-transcript" })
        val target = f.target().let { MailTarget(it.hostId, it.sessionId, it.daemonBootId) }
        f.mail.refresh(target)
        assertEquals(1, f.mail.observe(target).first().messages.size)
        f.connection.state.value = ConnectionState.Connected(f.daemon.profile); runCurrent()
        assertEquals(2, f.daemon.requests.count { it.first == "hello" })
        f.sync.setForeground(false)
    }

    @Test fun missingMailSendLeavesPassiveReadsAvailableAndCannotWrite() = runTest {
        val f = Fixture(this); f.daemon.verbs -= "send-agent-message"; f.start()
        val target = f.target().let { MailTarget(it.hostId, it.sessionId, it.daemonBootId) }
        f.mail.refresh(target)
        assertEquals(1, f.mail.observe(target).first().messages.size)
        assertFalse(f.mail.observe(target).first().canSend)
        assertIs<dev.gaiusred.tuiosmobile.protocol.MutationOutcome.Rejected>(f.mail.send(
            MailDraft(target.hostId, target.sessionId, "window-stable", text = "must stay local", daemonBootId = target.daemonBootId)))
        assertFalse(f.daemon.requests.any { it.first == "send-agent-message" })
        f.sync.setForeground(false)
    }

    @Test fun discoveryInvalidatesCapabilitiesAndVerificationOnReconnect() = runTest {
        val f = Fixture(this); f.start()
        f.daemon.minProtocol = 2; f.daemon.protocol = 2
        f.connection.state.value = ConnectionState.Connected(f.daemon.profile); runCurrent()
        assertTrue(f.sync.state.value.offline)
        assertNull(f.sync.state.value.verification)
        assertEquals("UnsupportedProtocol", f.sync.state.value.problem.toString())
        assertEquals(2, f.daemon.requests.count { it.first == "hello" })
        assertEquals(1, f.daemon.requests.count { it.first == "list-verbs" })
        assertEquals(1, f.sync.state.value.sessions.size) // Cache remains readable, but cannot authorize operations.
        f.sync.setForeground(false)
    }

    @Test fun wireSelectorsFollowSessionRenameWhileCacheIdentityRemainsStable() = runTest {
        val f = Fixture(this); f.start()
        assertEquals(listOf("initial"), f.texts())
        assertEquals("session-stable", f.target().sessionId)
        f.daemon.sessions = listOf(session("session-stable", "renamed-current"))
        f.daemon.agents = listOf(JsonObject(agent() + ("session" to JsonPrimitive("renamed-current"))))
        f.daemon.transcript = { page(listOf(entry("new", "after rename")), "forward-2") }
        f.daemon.event("window-retitled", 21u); runCurrent()
        assertEquals(listOf("initial", "after rename"), f.texts())
        assertEquals("session-stable", f.target().sessionId)
        for (verb in listOf("list-agents", "attach-presence", "agent-transcript")) {
            assertEquals("renamed-current", f.daemon.requests.last { it.first == verb }.second["session"]?.jsonPrimitive?.content)
        }
        f.sync.setForeground(false)
    }

    @Test fun listingSubscriptionRaceReconciles() = runTest {
        val f = Fixture(this)
        f.daemon.onListAgents = {
            f.daemon.onListAgents = null
            f.daemon.sessions = f.daemon.sessions + session("second-stable", "second-display")
            f.daemon.event("session-created", 21u)
        }
        f.start()
        assertEquals(listOf("session-stable", "second-stable"), f.sync.state.value.sessions.map { it.id })
        assertEquals("session-stable", f.target().sessionId)
        f.sync.setForeground(false)
    }

    @Test fun eventGapRefreshesState() = runTest {
        val f = Fixture(this); f.start()
        f.daemon.agents = listOf(agent(name = "after-gap"))
        f.daemon.transcript = { page(listOf(entry("a", "reconciled")), "forward-2") }
        f.daemon.event("gap"); runCurrent()
        assertEquals("after-gap", f.sync.state.value.sessions.single().agents.single().name)
        assertEquals(listOf("initial", "reconciled"), f.texts())
        f.sync.setForeground(false)
    }

    @Test fun newBootDoesNotReplayOldIdentity() = runTest {
        val f = Fixture(this); f.start(); val oldTarget = f.target()
        f.sync.setForeground(false); runCurrent()
        f.daemon.boot = "boot-B"
        f.daemon.transcript = { page(listOf(entry("z", "new daemon")), "new-forward") }
        f.sync.setForeground(true); runCurrent()
        assertEquals("boot-B", f.target().daemonBootId)
        assertNotEquals(oldTarget, f.target())
        assertEquals(listOf("new daemon"), f.texts())
        assertTrue(f.store.observe(HistoryQuery(f.daemon.hostId)).first().records.any { it.key.daemonBootId == "boot-A" })
        f.sync.setForeground(false)
    }

    @Test fun transcriptResetReplacesCurrentBranch() = runTest {
        val f = Fixture(this); f.start(); val target = f.target()
        f.daemon.transcript = { page(listOf(entry("z", "replacement")), "opaque-reset", reset = true) }
        f.daemon.event("transcript", 21u, session = "display-name", window = "window-stable"); runCurrent()
        assertEquals(listOf("replacement"), f.texts(target))
        assertEquals(2, f.store.writes.map { it.key.transcriptId }.distinct().size)
        f.sync.setForeground(false)
        val offline = ForegroundSync(f.connection, f.store, backgroundScope, f.selected) { receiptTime }
        runCurrent()
        assertEquals(listOf("replacement"), offline.conversation(target).first().entries.map { it.content.text })
    }

    @Test fun loadOlderPreservesOrder() = runTest {
        val f = Fixture(this); f.start()
        f.daemon.transcript = { params ->
            if ("before" in params) page(listOf(entry("x", "oldest"), entry("z", "initial")), "wrong-forward", "older-2")
            else {
                assertEquals("forward-1", params["after"]?.jsonPrimitive?.content)
                page(listOf(entry("a", "newest")), "forward-2")
            }
        }
        f.sync.loadOlder(f.target()); runCurrent()
        assertEquals(listOf("oldest", "initial"), f.texts())
        f.daemon.event("transcript", 21u, session = "display-name", window = "window-stable"); runCurrent()
        assertEquals(listOf("oldest", "initial", "newest"), f.texts())
        f.sync.setForeground(false)
    }

    @Test fun switchHostIgnoresLateOldEvents() = runTest {
        val f = Fixture(this); f.start()
        val old = f.daemon
        old.event("session-closed", 21u)
        val next = ScriptedDaemon(HostId("host-B"))
        next.sessions = listOf(session("B-session", "B-name"))
        next.transcript = { page(listOf(entry("b", "host B")), "B-cursor") }
        f.connection.daemon = next
        f.selected.value = next.hostId
        f.connection.state.value = dev.gaiusred.tuiosmobile.connection.ConnectionState.Connected(next.profile)
        runCurrent()
        assertEquals(next.hostId, f.sync.state.value.hostId)
        assertEquals(listOf("B-session"), f.sync.state.value.sessions.map { it.id })
        assertTrue(old.channels.all { it.closed })
        assertEquals(1, f.store.writes.count { it.key.hostId == old.hostId })
        f.sync.setForeground(false); runCurrent()
        assertTrue(next.channels.all { it.closed })
    }

    @Test fun agentRestartAndSessionRemovalRefreshTargets() = runTest {
        val f = Fixture(this); f.start(); val target = f.target()
        f.daemon.agents = listOf(agent(identity = "agent-life-2"))
        f.daemon.transcript = { params ->
            assertFalse("after" in params)
            page(listOf(entry("z", "new agent")), "cursor-agent2")
        }
        f.daemon.event("agent-state", 21u); runCurrent()
        assertNotEquals(target, f.target()); assertEquals(listOf("new agent"), f.texts())
        f.daemon.sessions = emptyList(); f.daemon.event("session-closed", 22u); runCurrent()
        assertTrue(f.sync.state.value.sessions.isEmpty())
        f.sync.setForeground(false)
    }

    @Test fun replayBelowAcknowledgmentIsAppliedWithoutDuplicates() = runTest {
        val f = Fixture(this); f.start()
        f.daemon.event("agent-state", 7u); runCurrent()
        f.sync.setForeground(false); runCurrent(); f.sync.setForeground(true); runCurrent()
        val params = f.daemon.requests.last { it.first == "subscribe" }.second
        assertEquals("7", params["after_seq"]?.jsonPrimitive?.content)
        assertEquals(listOf("initial"), f.texts())
        f.sync.setForeground(false)
    }

    @Test fun drainsIncrementalPagesUsingReturnedOpaqueCursor() = runTest {
        val f = Fixture(this)
        f.daemon.transcript = { params ->
            when (params["after"]?.jsonPrimitive?.content) {
                null -> page(listOf(entry("z", "one")), "opaque/one", more = true)
                "opaque/one" -> page(listOf(entry("a", "two")), "opaque/two")
                else -> error("Unexpected forward cursor")
            }
        }
        f.start(); assertEquals(listOf("one", "two"), f.texts()); f.sync.setForeground(false)
    }

    @Test fun unsupportedResumeFallsBackToFreshSubscriptionAndReconciles() = runTest {
        val f = Fixture(this); f.start()
        f.daemon.event("agent-state", 7u); runCurrent()
        f.sync.setForeground(false); runCurrent()
        f.daemon.rejectResume = true
        f.daemon.transcript = { page(listOf(entry("new", "recovered")), "forward-2") }
        f.sync.setForeground(true); runCurrent(); advanceTimeBy(1_000); runCurrent()
        assertEquals(listOf("initial", "recovered"), f.texts())
        assertFalse("after_seq" in f.daemon.requests.last { it.first == "subscribe" }.second)
        f.sync.setForeground(false)
    }

    @Test fun nonProgressingForwardPageFailsWithoutDiscardingStoredCursor() = runTest {
        val f = Fixture(this); f.start()
        var attempts = 0
        f.daemon.transcript = { page(emptyList(), "", more = ++attempts < 3) }
        f.daemon.event("transcript", 21u, session = "display-name", window = "window-stable"); runCurrent()
        assertEquals(TranscriptAvailability.Failed, f.sync.conversation(f.target()).first().availability)
        assertEquals("forward-1", f.store.checkpoints[f.daemon.hostId]?.agents?.single()?.transcript?.forward)
        f.sync.setForeground(false)
    }

    @Test fun backgroundClosesPresenceAndSubscriptionsWithoutRetrying() = runTest {
        val f = Fixture(this); f.start()
        f.sync.setForeground(false); runCurrent()
        val count = f.daemon.requests.size
        advanceTimeBy(10_000); runCurrent()
        assertEquals(count, f.daemon.requests.size)
        assertTrue(f.daemon.channels.all { it.closed })
    }
}
