package dev.gaiusred.tuiosmobile.connection

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.gaiusred.tuiosmobile.identity.HostId
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.io.*
import java.util.UUID

class SshCancellationTest {
    @Test fun cancellingJschOpenClosesActualSocketOnApi26() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val keys = DeviceKeyStore(context)
        val alias = "test-${UUID.randomUUID()}"
        val public = keys.create(alias)
        try {
            ServerSocket(0).use { peer ->
                val reached = CompletableDeferred<Unit>()
                val ended = async(Dispatchers.IO) {
                    peer.accept().use { socket ->
                        socket.soTimeout = 5000
                        val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                        assertTrue(input.readLine().startsWith("SSH-2.0-"))
                        reached.complete(Unit)
                        assertEquals(-1, input.read())
                    }
                }
                val factory = LoopbackFactory(peer.localPort)
                val transport = SshProxyTransport(keys, this) { factory }
                val profile = HostProfile(HostId("fabricated"), "fabricated", "person", "phone",
                    listOf(HostEndpoint("100.100.1.1", 22)), public.line, alias)
                val connect = launch { transport.connect(profile).close() }
                withTimeout(5000) { reached.await() }
                connect.cancelAndJoin()
                withTimeout(5000) { ended.await() }
                assertTrue(factory.closed)
            }
        } finally { keys.delete(alias) }
    }

    @Test fun federationFramingAndClosureOnApi26() = runBlocking {
        val input = PipedInputStream(65536)
        val peer = PipedOutputStream(input)
        val output = ByteArrayOutputStream()
        peer.write("banner\nTUIOS-LINK 1\n".toByteArray())
        val mux = FederationMux(input, output, { input.close(); peer.close() }, this)
        mux.start()
        val channel = mux.open(true)
        val frame = output.toByteArray()
        assertEquals(1, frame[0].toInt())
        assertEquals("{\"human\":true}", String(frame, 9, frame.size - 9))
        val bytes = "fabricated\n".toByteArray()
        peer.write(java.nio.ByteBuffer.allocate(9 + bytes.size).put(2).putInt(1).putInt(bytes.size).put(bytes).array())
        assertArrayEquals(bytes, withTimeout(5000) { channel.receive() })
        mux.close()
        assertFalse(channel.isOpen.value)
        assertNull(channel.receive())
    }

    private class LoopbackFactory(private val port: Int) : CancellableSocketFactory {
        @Volatile var closed = false
        @Volatile private var socket: Socket? = null
        override fun createSocket(host: String, port: Int): Socket = Socket("127.0.0.1", this.port).also { socket = it }
        override fun getInputStream(socket: Socket): InputStream = socket.getInputStream()
        override fun getOutputStream(socket: Socket): OutputStream = socket.getOutputStream()
        override fun close() { closed = true; runCatching { socket?.close() } }
    }
}
