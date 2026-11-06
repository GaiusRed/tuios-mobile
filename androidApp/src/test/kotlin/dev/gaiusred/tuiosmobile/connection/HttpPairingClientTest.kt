package dev.gaiusred.tuiosmobile.connection

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger

class HttpPairingClientTest {
    @Test fun fixedLengthPostDoesNotFollowRedirectOrRetry() = runBlocking {
        ServerSocket(0).use { peer ->
            val accepted = async(Dispatchers.IO) {
                peer.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                    assertEquals("POST /v1/pair HTTP/1.1", reader.readLine())
                    val headers = mutableListOf<String>()
                    while (true) { val line = reader.readLine(); if (line.isEmpty()) break; headers += line }
                    assertTrue(headers.contains("Content-Length: 2"))
                    assertFalse(headers.any { it.startsWith("Transfer-Encoding:", true) })
                    assertEquals('{'.code, reader.read()); assertEquals('}'.code, reader.read())
                    socket.getOutputStream().write("HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:1/unsafe\r\nContent-Length: 0\r\n\r\n".toByteArray())
                }
            }
            val factory = LoopbackFactory(peer.localPort)
            val client = HttpPairingClient { factory }
            try { client.post(HostEndpoint("100.100.1.1", 1234), "{}".toByteArray()); fail("Redirect followed") }
            catch (failure: PairingRejected) { assertEquals(302, failure.status) }
            accepted.await()
            assertEquals(1, factory.connections.get())
        }
    }

    @Test fun chunkedSuccessIsBoundedAndDecoded() = runBlocking {
        ServerSocket(0).use { peer ->
            val accepted = async(Dispatchers.IO) {
                peer.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                    while (reader.readLine().isNotEmpty()) { }
                    repeat(2) { reader.read() }
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2\r\n{}\r\n0\r\n\r\n".toByteArray())
                }
            }
            val result = HttpPairingClient { LoopbackFactory(peer.localPort) }
                .post(HostEndpoint("100.100.1.1", 1234), "{}".toByteArray())
            assertEquals("{}", String(result))
            accepted.await()
        }
    }

    @Test fun cancellationClosesBlockedReadSocket() = runBlocking {
        ServerSocket(0).use { peer ->
            val reached = CompletableDeferred<Unit>()
            val ended = async(Dispatchers.IO) {
                peer.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                    while (reader.readLine().isNotEmpty()) { }
                    repeat(2) { reader.read() }
                    reached.complete(Unit)
                    assertEquals(-1, reader.read())
                }
            }
            val request = launch { HttpPairingClient { LoopbackFactory(peer.localPort) }
                .post(HostEndpoint("100.100.1.1", 1234), "{}".toByteArray()) }
            withTimeout(3000) { reached.await() }
            request.cancelAndJoin()
            withTimeout(3000) { ended.await() }
        }
    }

    @Test fun adapterDeadlineAfterPostReturnsUncertainWithRetainedAliasAndNoReplay() = runBlocking {
        ServerSocket(0).use { peer ->
            val posts = AtomicInteger()
            val ended = async(Dispatchers.IO) { readPostUntilClosed(peer, posts) }
            val keys = PairingKeys()
            val factory = LoopbackFactory(peer.localPort)
            val client = PairingClient(HttpPairingClient(1000) { factory }, keys)
            val outcome = client.pair(validInvitation())
            assertEquals(PairingOutcome.Uncertain(keys.alias!!), outcome)
            assertFalse(keys.deleted)
            withTimeout(3000) { ended.await() }
            assertEquals(1, posts.get())
            assertEquals(1, factory.connections.get())
        }
    }

    @Test fun externalCancellationAfterPostPropagatesAndRetainsKey() = runBlocking {
        ServerSocket(0).use { peer ->
            val posts = AtomicInteger()
            val began = CompletableDeferred<Unit>()
            val ended = async(Dispatchers.IO) { readPostUntilClosed(peer, posts, began) }
            val keys = PairingKeys()
            val factory = LoopbackFactory(peer.localPort)
            val request = async { PairingClient(HttpPairingClient { factory }, keys).pair(validInvitation()) }
            withTimeout(3000) { began.await() }
            request.cancel(CancellationException("Caller cancelled"))
            try { request.await(); fail("Caller cancellation became an outcome") }
            catch (_: CancellationException) { }
            withTimeout(3000) { ended.await() }
            assertFalse(keys.deleted)
            assertNotNull(keys.alias)
            assertEquals(1, posts.get())
            assertEquals(1, factory.connections.get())
        }
    }

    private fun validInvitation() = PairInvitation(
        "tuios://pair?v=1&m=fabricated&u=person&s=100.100.1.1:22&p=100.100.1.1:1234&fp=SHA256:" +
            java.util.Base64.getEncoder().withoutPadding().encodeToString(ByteArray(32)) + "&t=AAECAwQFBgcICQoLDA0ODw", "phone")

    private fun readPostUntilClosed(peer: ServerSocket, posts: AtomicInteger, began: CompletableDeferred<Unit>? = null) {
        peer.accept().use { socket ->
            socket.soTimeout = 5000
            val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
            assertEquals("POST /v1/pair HTTP/1.1", reader.readLine())
            var length = 0
            while (true) {
                val line = reader.readLine()
                if (line.isEmpty()) break
                if (line.startsWith("Content-Length:")) length = line.substringAfter(':').trim().toInt()
            }
            repeat(length) { assertTrue(reader.read() >= 0) }
            posts.incrementAndGet()
            began?.complete(Unit)
            assertEquals(-1, reader.read())
        }
    }

    private class PairingKeys : PhoneKeyStore {
        var alias: String? = null
        var deleted = false
        override fun create(alias: String): PhonePublicKey {
            this.alias = alias
            return PhonePublicKey("test-key", "SHA256:fabricated")
        }
        override fun privateKey(alias: String): ByteArray = error("No SSH during pairing")
        override fun delete(alias: String) { deleted = true }
    }

    private class LoopbackFactory(private val port: Int) : CancellableSocketFactory {
        val connections = AtomicInteger()
        @Volatile private var socket: Socket? = null
        override fun createSocket(host: String, port: Int): Socket = Socket("127.0.0.1", this.port).also { socket = it; connections.incrementAndGet() }
        override fun getInputStream(socket: Socket): InputStream = socket.getInputStream()
        override fun getOutputStream(socket: Socket): OutputStream = socket.getOutputStream()
        override fun close() { socket?.close() }
    }
}
