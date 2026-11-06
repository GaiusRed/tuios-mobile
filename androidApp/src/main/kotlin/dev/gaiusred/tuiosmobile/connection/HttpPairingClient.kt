package dev.gaiusred.tuiosmobile.connection

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One socket and one fixed-length POST. No redirects, reconnect, or mutation replay. */
class HttpPairingClient internal constructor(private val timeoutMillis: Long = 70_000,
    private val factories: () -> CancellableSocketFactory) : PairingHttp {
    constructor() : this(factories = { TailnetSocketFactory() })

    override suspend fun post(endpoint: HostEndpoint, body: ByteArray): ByteArray {
        require(body.size in 1..8192)
        val factory = factories()
        val socket = AtomicReference<Socket?>()
        return try {
            boundedIo(timeoutMillis, { factory.close(); runCatching { socket.get()?.close() } }) {
                val connected = factory.createSocket(endpoint.host, endpoint.port)
                socket.set(connected)
                connected.soTimeout = 60_000
                connected.use {
                    val host = if (endpoint.host.contains(':')) "[${endpoint.host}]" else endpoint.host
                    require(host.none { it.isISOControl() })
                    val header = "POST /v1/pair HTTP/1.1\r\nHost: $host:${endpoint.port}\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                    connected.getOutputStream().apply { write(header.toByteArray(Charsets.US_ASCII)); write(body); flush() }
                    readPairingResponse(connected.getInputStream())
                }
            }
        } catch (deadline: TimeoutCancellationException) {
            // After our inner deadline the caller is active; a cancelled caller must still propagate.
            currentCoroutineContext().ensureActive()
            throw java.io.IOException("Pairing transport deadline elapsed")
        } finally { factory.close() }
    }
}

private fun readPairingResponse(input: InputStream): ByteArray {
    var headerBytes = 0
    fun line(): String {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val byte = input.read()
            require(byte >= 0 && ++headerBytes <= 8192)
            if (byte == 10) {
                val raw = bytes.toByteArray()
                require(raw.isNotEmpty() && raw.last() == 13.toByte())
                return String(raw, 0, raw.size - 1, Charsets.US_ASCII)
            }
            require(byte in 32..126 || byte == 13 || byte == 9)
            bytes.write(byte)
        }
    }
    val status = line().split(' ', limit = 3)
    require(status.size >= 2 && status[0] in setOf("HTTP/1.0", "HTTP/1.1"))
    val code = status[1].toInt()
    val headers = mutableMapOf<String, String>()
    while (true) {
        val header = line()
        if (header.isEmpty()) break
        val parts = header.split(':', limit = 2)
        require(parts.size == 2)
        val name = parts[0].lowercase(java.util.Locale.ROOT)
        require(headers.put(name, parts[1].trim()) == null)
    }
    if (code != 200) throw PairingRejected(code)
    val output = ByteArrayOutputStream()
    fun copy(length: Int) {
        require(length in 0..16_384 && output.size() + length <= 16_384)
        val buffer = ByteArray(minOf(length, 2048))
        var remaining = length
        while (remaining > 0) {
            val count = input.read(buffer, 0, minOf(buffer.size, remaining))
            require(count > 0)
            output.write(buffer, 0, count)
            remaining -= count
        }
    }
    val transfer = headers["transfer-encoding"]
    val length = headers["content-length"]
    require(transfer == null || length == null)
    when {
        transfer != null -> {
            require(transfer.equals("chunked", ignoreCase = true))
            while (true) {
                val chunk = line().substringBefore(';').toInt(16)
                if (chunk == 0) { while (line().isNotEmpty()) { }; break }
                copy(chunk)
                require(input.read() == 13 && input.read() == 10)
            }
        }
        length != null -> copy(length.toInt())
        else -> {
            while (true) {
                val byte = input.read()
                if (byte < 0) break
                require(output.size() < 16_384)
                output.write(byte)
            }
        }
    }
    return output.toByteArray()
}
