package dev.gaiusred.tuiosmobile.mail

import dev.gaiusred.tuiosmobile.protocol.MutationOutcome
import dev.gaiusred.tuiosmobile.storage.*
import dev.gaiusred.tuiosmobile.sync.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MailConnectionGenerationTest {
    @Test fun delayedBootDiscoveryAfterReconnectBlocksMailUntilCurrentListingIsVerified() = runTest {
        val peer = ScriptedDaemon()
        val store = TestHistoryStore()
        val connection = TestHostConnection(peer)
        val selected = MutableStateFlow<dev.gaiusred.tuiosmobile.identity.HostId?>(peer.hostId)
        val sync = ForegroundSync(connection, store, backgroundScope, selected) { receiptTime }
        val mail = MailRepository(connection, store, sync, selected, backgroundScope) { receiptTime }
        sync.setForeground(true); runCurrent()
        val old = MailTarget(peer.hostId, "session-stable", "boot-A")
        connection.disconnect(); runCurrent()
        val readsBeforeReconnect = peer.requests.count { it.first == "read-agent-messages" }
        peer.boot = "boot-B"
        val gate = CompletableDeferred<Unit>()
        peer.subscriptionGate = gate
        connection.connect(peer.profile); runCurrent()
        mail.refresh(old)
        val oldSend = mail.send(MailDraft(peer.hostId, old.sessionId, "window-stable", text = "Must stay unsent", daemonBootId = "boot-A"))
        assertEquals(readsBeforeReconnect, peer.requests.count { it.first == "read-agent-messages" })
        assertFalse(peer.requests.any { it.first == "send-agent-message" })
        assertIs<MutationOutcome.Rejected>(oldSend)
        assertTrue(store.observe(HistoryQuery(peer.hostId)).first().records.none {
            it.entry.content is CachedContent.Mail && it.key.daemonBootId == "boot-B" })
        assertTrue(sync.state.value.offline)
        gate.complete(Unit); runCurrent()
        val current = old.copy(daemonBootId = "boot-B")
        assertIs<MutationOutcome.Rejected>(mail.send(MailDraft(peer.hostId, old.sessionId, "window-stable", text = "Old target", daemonBootId = "boot-A")))
        mail.refresh(current)
        assertIs<MutationOutcome.Accepted>(mail.send(MailDraft(peer.hostId, old.sessionId, "window-stable", text = "Verified current target", daemonBootId = "boot-B")))
        val records = store.observe(HistoryQuery(peer.hostId)).first().records.filter { it.entry.content is CachedContent.Mail }
        assertEquals(listOf("boot-A", "boot-B"), records.map { it.key.daemonBootId })
        sync.setForeground(false)
    }
}
