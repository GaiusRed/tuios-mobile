package dev.gaiusred.tuiosmobile.connection

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.*
import java.util.concurrent.atomic.AtomicInteger

/** Bounded tuios federation framing. One reader; serialized complete-frame writes. */
internal class FederationMux(input: InputStream, output: OutputStream, private val closeTransport: () -> Unit,
    private val scope: CoroutineScope) : ProxyLink {
    private val input = DataInputStream(input)
    private val output = DataOutputStream(output)
    private val writeGate = Mutex()
    private val gate = Any()
    private val streams = mutableMapOf<Int, Stream>()
    private var nextId = 1
    private var closed = false
    private val open = MutableStateFlow(true)
    override val isOpen = open.asStateFlow()
    private var reader: Job? = null
    private val queuedBytes = AtomicInteger()

    suspend fun start() {
        boundedIo(10_000, ::close) {
            var scanned = 0
            val line = ByteArrayOutputStream()
            while (true) {
                val byte = input.read()
                if (byte < 0 || ++scanned > 8192) throw IOException("Proxy preamble unavailable")
                if (byte == 10) {
                    if (line.toString("US-ASCII") == "TUIOS-LINK 1") break
                    line.reset()
                } else line.write(byte)
            }
        }
        reader = scope.launch(Dispatchers.IO) {
            try { while (isActive) readFrame() } catch (_: Exception) { /* Content-free failure. */ }
            finally { close() }
        }
    }

    override suspend fun open(human: Boolean): ProxyChannel {
        val stream = synchronized(gate) {
            check(!closed && streams.size < 16 && nextId > 0 && nextId <= Int.MAX_VALUE - 2)
            Stream(nextId).also { streams[nextId] = it; nextId += 2 }
        }
        try {
            writeFrame(1, stream.id, if (human) "{\"human\":true}".toByteArray() else ByteArray(0))
            return stream
        } catch (failure: Exception) { close(); throw failure }
    }

    private fun readFrame() {
        val type = input.readUnsignedByte()
        val id = input.readInt()
        val length = input.readInt()
        require(type in 1..4 && id != 0 && length in 0..1_048_576)
        require(type != 3 || length == 0)
        val payload = ByteArray(length)
        input.readFully(payload)
        when (type) {
            1 -> throw IOException("Unrequested peer stream")
            2 -> {
                val stream = synchronized(gate) { streams[id] } ?: return
                if (!stream.enqueue(payload)) throw IOException("Slow stream consumer")
            }
            3 -> synchronized(gate) { streams.remove(id) }?.finish(keepPending = true)
            4 -> throw IOException("Unexpected bulk credit")
        }
    }

    private suspend fun writeFrame(type: Int, id: Int, payload: ByteArray) {
        require(payload.size <= 1_048_576)
        try {
            withTimeout(10_000) {
                writeGate.withLock {
                    boundedIo(10_000, ::close) {
                        synchronized(gate) { check(!closed) }
                        output.writeByte(type); output.writeInt(id); output.writeInt(payload.size)
                        output.write(payload); output.flush()
                    }
                }
            }
        } catch (failure: Exception) { close(); throw failure }
    }

    override fun close() {
        val active = synchronized(gate) {
            if (closed) return
            closed = true
            open.value = false
            streams.values.toList().also { streams.clear() }
        }
        active.forEach { it.finish() }
        closeTransport()
        reader?.cancel()
    }

    private inner class Stream(val id: Int) : ProxyChannel {
        private val open = MutableStateFlow(true)
        override val isOpen = open.asStateFlow()
        private val chunks = Channel<ByteArray>(8)
        fun enqueue(bytes: ByteArray): Boolean {
            if (queuedBytes.addAndGet(bytes.size) > 4 * 1_048_576) { queuedBytes.addAndGet(-bytes.size); return false }
            if (chunks.trySend(bytes).isSuccess) return true
            queuedBytes.addAndGet(-bytes.size)
            return false
        }
        override suspend fun send(bytes: ByteArray) {
            check(open.value)
            writeFrame(2, id, bytes)
        }
        override suspend fun receive(): ByteArray? = try {
            chunks.receiveCatching().getOrNull()?.also { queuedBytes.addAndGet(-it.size) }
        } catch (failure: CancellationException) { close(); throw failure }
        fun finish(keepPending: Boolean = false) {
            open.value = false
            chunks.close()
            if (keepPending) return
            while (true) {
                val bytes = chunks.tryReceive().getOrNull() ?: break
                queuedBytes.addAndGet(-bytes.size)
                bytes.fill(0)
            }
        }
        override fun close() {
            val wasOpen = open.value
            synchronized(gate) { streams.remove(id) }
            finish()
            // A stuck CLOSE also closes the owner through the write deadline.
            if (wasOpen) scope.launch { try { writeFrame(3, id, ByteArray(0)) } catch (_: Exception) { this@FederationMux.close() } }
        }
    }
}
