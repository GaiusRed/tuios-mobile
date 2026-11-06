package dev.gaiusred.tuiosmobile.connection

import com.jcraft.jsch.*
import kotlinx.coroutines.*
import java.io.OutputStream

internal class SshProxyTransport(private val keys: PhoneKeyStore, private val scope: CoroutineScope,
    private val sockets: () -> CancellableSocketFactory = { TailnetSocketFactory() }) : ProxyTransport {
    override suspend fun connect(host: HostProfile): ProxyLink {
        val pinned = try { parseHostKey(host.hostKey) } catch (_: Exception) {
            throw ConnectionException(ConnectionState.HostKeyReviewRequired)
        }
        require(host.sshCandidates.size in 1..16)
        var failure: Exception? = null
        for (endpoint in host.sshCandidates) {
            try { return connectCandidate(host, endpoint, pinned) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (rejected: ConnectionException) {
                if (rejected.reason != ConnectionState.Failed) throw rejected
                failure = rejected
            }
            catch (unavailable: Exception) { failure = unavailable }
        }
        // Never retain library exception messages: they can contain authentication details.
        if (failure != null) throw ConnectionException(ConnectionState.Failed)
        error("No SSH candidates")
    }

    private suspend fun connectCandidate(host: HostProfile, endpoint: HostEndpoint, pinned: PublicHostKey): ProxyLink {
        val jsch = JSch()
        val factory = sockets()
        val repository = HostKeyStore(endpoint, pinned)
        jsch.setHostKeyRepository(repository)
        val session = jsch.getSession(host.user, endpoint.host, endpoint.port)
        var exec: ChannelExec? = null
        fun release() {
            factory.close()
            runCatching { exec?.disconnect() }
            runCatching { session.disconnect() }
            runCatching { jsch.removeAllIdentity() }
        }
        try {
            val link = boundedIo(15_000, ::release, { it.close() }) {
                val private = keys.privateKey(host.keyAlias)
                try { jsch.addIdentity(host.keyAlias, private, null, null) } finally { private.fill(0) }
                session.setSocketFactory(factory)
                session.setConfig("StrictHostKeyChecking", "yes")
                session.setConfig("PreferredAuthentications", "publickey")
                session.setConfig("server_host_key", if (pinned.type == "ssh-rsa") "rsa-sha2-512,rsa-sha2-256" else pinned.type)
                session.setConfig("PubkeyAcceptedAlgorithms", "ecdsa-sha2-nistp256")
                session.setConfig("enable_strict_kex", "yes")
                session.setConfig("ssh-ed25519", "com.jcraft.jsch.bc.SignatureEd25519")
                session.setConfig("xdh", "com.jcraft.jsch.bc.XDH")
                session.timeout = 15_000
                session.setServerAliveInterval(10_000)
                session.setServerAliveCountMax(1)
                session.connect(10_000)
                val channel = (session.openChannel("exec") as ChannelExec).also { exec = it }
                channel.setPty(false)
                channel.setAgentForwarding(false)
                channel.setCommand("tuios stdio-proxy")
                // Discard stderr without retaining it or mixing it with binary stdout.
                channel.setErrStream(object : OutputStream() { override fun write(b: Int) = Unit
                    override fun write(b: ByteArray, off: Int, len: Int) = Unit })
                val input = channel.inputStream
                val output = channel.outputStream
                channel.connect(10_000)
                FederationMux(input, output, ::release, scope)
            }
            link.start()
            return link
        } catch (failure: Exception) {
            release()
            if (failure is CancellationException || failure is ConnectionException) throw failure
            if (repository.mismatchObserved) throw ConnectionException(ConnectionState.HostKeyReviewRequired)
            if (failure is JSchException && failure.message?.startsWith("Auth fail") == true)
                throw ConnectionException(ConnectionState.AuthenticationFailed)
            throw ConnectionException(ConnectionState.Failed)
        }
    }
}
