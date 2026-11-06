package dev.gaiusred.tuiosmobile.connection

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.nio.ByteBuffer

class FederationMuxTest {
    @Test fun writesHumanOpenAndReassemblesSplitDataWithoutLosingPreambleRemainder() = runBlocking {
        val input = PipedInputStream(65536)
        val peer = PipedOutputStream(input)
        val output = ByteArrayOutputStream()
        peer.write("banner\nTUIOS-LINK 1\n".toByteArray())
        val mux = FederationMux(input, output, { input.close(); peer.close() }, this)
        mux.start()
        val channel = mux.open(true)
        val sent = output.toByteArray()
        assertEquals(1, sent[0].toInt())
        assertEquals(1, ByteBuffer.wrap(sent, 1, 4).int)
        assertEquals("{\"human\":true}", String(sent, 9, sent.size - 9))
        peer.write(frame(2, 1, byteArrayOf(0xe2.toByte())))
        peer.write(frame(2, 1, byteArrayOf(0x82.toByte(), 0xac.toByte(), 10)))
        assertArrayEquals(byteArrayOf(0xe2.toByte()), channel.receive())
        assertArrayEquals(byteArrayOf(0x82.toByte(), 0xac.toByte(), 10), channel.receive())
        mux.close()
        assertFalse(channel.isOpen.value)
    }

    @Test fun oversizeHeaderTerminatesLinkBeforePayloadAllocation() = runBlocking {
        val input = PipedInputStream(65536)
        val peer = PipedOutputStream(input)
        peer.write("TUIOS-LINK 1\n".toByteArray())
        val mux = FederationMux(input, ByteArrayOutputStream(), { input.close(); peer.close() }, this)
        mux.start()
        val channel = mux.open(false)
        peer.write(ByteBuffer.allocate(9).put(2).putInt(1).putInt(1_048_577).array())
        assertNull(withTimeout(2000) { channel.receive() })
        assertFalse(channel.isOpen.value)
    }

    private fun frame(type: Int, stream: Int, payload: ByteArray) =
        ByteBuffer.allocate(9 + payload.size).put(type.toByte()).putInt(stream).putInt(payload.size).put(payload).array()

    @Test fun dataBeforeRemoteCloseIsDeliveredBeforeEof() = runBlocking {
        val input = PipedInputStream(65536)
        val peer = PipedOutputStream(input)
        peer.write("TUIOS-LINK 1\n".toByteArray())
        val mux = FederationMux(input, ByteArrayOutputStream(), { input.close(); peer.close() }, this)
        mux.start()
        val channel = mux.open(false)
        peer.write(frame(2, 1, "complete\n".toByteArray()) + frame(3, 1, ByteArray(0)))
        withTimeout(2000) { while (channel.isOpen.value) delay(1) }
        assertArrayEquals("complete\n".toByteArray(), channel.receive())
        assertNull(channel.receive())
        mux.close()
    }

    @Test fun cancellingBlockedSendClosesOwnerAndReleasesWriter() = runBlocking {
        val input = PipedInputStream(65536)
        val peer = PipedOutputStream(input)
        peer.write("TUIOS-LINK 1\n".toByteArray())
        val reached = java.util.concurrent.CountDownLatch(1)
        val released = java.util.concurrent.CountDownLatch(1)
        val block = java.util.concurrent.atomic.AtomicBoolean(false)
        val output = object : OutputStream() {
            override fun write(value: Int) {
                if (block.get()) { reached.countDown(); released.await(); throw IOException("Closed") }
            }
        }
        val mux = FederationMux(input, output, { input.close(); peer.close(); released.countDown() }, this)
        mux.start()
        val channel = mux.open(false)
        block.set(true)
        val sending = launch { channel.send("fabricated".toByteArray()) }
        withContext(Dispatchers.IO) { assertTrue(reached.await(3, java.util.concurrent.TimeUnit.SECONDS)) }
        sending.cancelAndJoin()
        assertFalse(channel.isOpen.value)
        assertFalse(mux.isOpen.value)
        assertEquals(0, released.count)
    }
}
