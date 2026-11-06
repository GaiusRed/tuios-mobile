package dev.gaiusred.tuiosmobile.connection

import dev.gaiusred.tuiosmobile.identity.HostId
import dev.gaiusred.tuiosmobile.protocol.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ConnectionLifecycleTest {
    private val host = HostProfile(HostId("fabricated"), "test", "person", "phone", listOf(HostEndpoint("100.100.1.1", 22)), "ssh-ed25519 AAAA", "test-key")

    @Test fun backgroundClosesAllChannels() = runTest {
        val links = mutableListOf<ScriptedLink>()
        val connection = ForegroundHostConnection(ProxyTransport { ScriptedLink().also { links += it } }, backgroundScope)
        connection.connect(host)
        val first = connection.openChannel(ChannelKind.HUMAN)
        val second = connection.openChannel(ChannelKind.SUBSCRIPTION)
        connection.setForeground(false)
        assertFalse(first.isOpen.value)
        assertFalse(second.isOpen.value)
        assertEquals(ConnectionState.Disconnected, connection.state.value)
        assertFailsWith<IllegalStateException> { connection.connect(host) }
    }

    @Test fun reconnectAcquiresFreshPresence() = runTest {
        var generation = 0
        val connection = ForegroundHostConnection(ProxyTransport { ScriptedLink(++generation) }, backgroundScope)
        connection.connect(host)
        val first = connection.acquirePresence("session", backgroundScope)
        assertEquals("nonce-1", first.nonce)
        connection.disconnect()
        assertFailsWith<IllegalStateException> { first.nonce }
        connection.connect(host)
        val second = connection.acquirePresence("session", backgroundScope)
        assertEquals("nonce-2", second.nonce)
        second.close()
        connection.disconnect()
    }

    @Test fun backgroundCancelsConnectionStillOpening() = runTest {
        val began = CompletableDeferred<Unit>()
        var released = false
        val connection = ForegroundHostConnection(ProxyTransport {
            began.complete(Unit)
            try { awaitCancellation() } finally { released = true }
        }, backgroundScope)
        val connect = launch { connection.connect(host) }
        began.await()
        connection.setForeground(false)
        connect.join()
        assertTrue(released)
        assertEquals(ConnectionState.Disconnected, connection.state.value)
    }

    @Test fun linkLossClearsConnectedStateAndInvalidatesPresence() = runTest {
        val link = ScriptedLink(1)
        val connection = ForegroundHostConnection(ProxyTransport { link }, backgroundScope)
        connection.connect(host)
        val presence = connection.acquirePresence("session", backgroundScope)
        link.close()
        runCurrent()
        assertEquals(ConnectionState.Disconnected, connection.state.value)
        assertFailsWith<IllegalStateException> { presence.nonce }
    }

    @Test fun overlappingReplacementCannotOverwriteNewerLiveLink() = runTest {
        lateinit var connection: ForegroundHostConnection
        lateinit var newest: Job
        val newestHost = host.copy(machine = "newest")
        val olderHost = host.copy(machine = "older")
        val newestLink = ScriptedLink()
        val olderLink = ScriptedLink()
        val initial = ScriptedLink(onClose = {
            newest = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler), start = CoroutineStart.UNDISPATCHED) {
                connection.connect(newestHost)
            }
        })
        connection = ForegroundHostConnection(ProxyTransport { profile ->
            when (profile.machine) { "newest" -> newestLink; "older" -> olderLink; else -> initial }
        }, backgroundScope)
        connection.connect(host)
        val older = launch { connection.connect(olderHost) }
        runCurrent()
        try {
            assertEquals(newestHost, (connection.state.value as ConnectionState.Connected).host)
            assertFalse(initial.isOpen.value)
        } finally { connection.setForeground(false) }
        older.join(); newest.join()
        assertFalse(newestLink.isOpen.value)
    }

    @Test fun overlappingPendingReplacementAndBackgroundReleaseEveryAttempt() = runTest {
        lateinit var connection: ForegroundHostConnection
        lateinit var newest: Job
        val released = mutableSetOf<String>()
        val initial = ScriptedLink(onClose = {
            newest = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler), start = CoroutineStart.UNDISPATCHED) {
                connection.connect(host.copy(machine = "newest"))
            }
        })
        connection = ForegroundHostConnection(ProxyTransport { profile ->
            if (profile.machine == "test") initial else {
                try { awaitCancellation() } finally { released += profile.machine }
            }
        }, backgroundScope)
        connection.connect(host)
        val older = launch { connection.connect(host.copy(machine = "older")) }
        runCurrent()
        connection.setForeground(false)
        runCurrent()
        assertTrue(newest.isCompleted, "Newest pending connection survived backgrounding")
        assertTrue(older.isCompleted, "Superseded pending connection survived backgrounding")
        assertTrue("newest" in released)
        assertEquals(ConnectionState.Disconnected, connection.state.value)
    }

    private class ScriptedLink(private val generation: Int = 0, private val onClose: () -> Unit = {}) : ProxyLink {
        override val isOpen = kotlinx.coroutines.flow.MutableStateFlow(true)
        private val channels = mutableListOf<ProxyChannel>()
        override suspend fun open(human: Boolean): ProxyChannel = object : ProxyChannel {
            private val open = kotlinx.coroutines.flow.MutableStateFlow(true)
            override val isOpen = open
            private val replies = Channel<ByteArray>(1)
            override suspend fun send(bytes: ByteArray) {
                assertTrue(human)
                val id = kotlinx.serialization.json.Json.parseToJsonElement(bytes.decodeToString()).let {
                    (it as kotlinx.serialization.json.JsonObject)["id"].toString()
                }
                replies.send("{\"id\":$id,\"result\":{\"type\":\"presence\",\"human_nonce\":\"nonce-$generation\",\"client_id\":\"phone\",\"session\":\"session\"}}\n".encodeToByteArray())
            }
            override suspend fun receive(): ByteArray? = replies.receiveCatching().getOrNull()
            override fun close() { open.value = false; replies.close() }
        }.also { channels += it }
        override fun close() {
            if (!isOpen.value) return
            isOpen.value = false; channels.forEach { it.close() }; onClose()
        }
    }
}
