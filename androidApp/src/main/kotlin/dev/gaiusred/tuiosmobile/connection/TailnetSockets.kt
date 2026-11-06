package dev.gaiusred.tuiosmobile.connection

import com.jcraft.jsch.SocketFactory
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

internal fun tailnetAddress(address: InetAddress): Boolean {
    val bytes = address.address
    return when (bytes.size) {
        4 -> bytes[0].toInt() and 255 == 100 && (bytes[1].toInt() and 255) in 64..127
        16 -> bytes.take(6).toByteArray().contentEquals(byteArrayOf(0xfd.toByte(), 0x7a, 0x11, 0x5c, 0xa1.toByte(), 0xe0.toByte()))
        else -> false
    }
}

internal fun resolveTailnet(host: String): InetAddress = InetAddress.getAllByName(host)
    .firstOrNull(::tailnetAddress) ?: throw java.io.IOException("Tailscale address required")

internal interface CancellableSocketFactory : SocketFactory { fun close() }

internal class TailnetSocketFactory : CancellableSocketFactory {
    private val gate = Any()
    private var closed = false
    private var socket: Socket? = null
    override fun createSocket(host: String, port: Int): Socket {
        val address = resolveTailnet(host)
        val created = synchronized(gate) {
            check(!closed)
            Socket().also { socket = it }
        }
        try {
            created.connect(InetSocketAddress(address, port), 10_000)
            created.soTimeout = 15_000
            return created
        } catch (failure: Exception) { close(); throw failure }
    }
    override fun getInputStream(socket: Socket): InputStream = socket.getInputStream()
    override fun getOutputStream(socket: Socket): OutputStream = socket.getOutputStream()
    override fun close() { synchronized(gate) { closed = true; runCatching { socket?.close() }; socket = null } }
}
