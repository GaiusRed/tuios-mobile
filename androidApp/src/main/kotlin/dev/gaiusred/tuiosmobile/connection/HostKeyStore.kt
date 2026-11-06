package dev.gaiusred.tuiosmobile.connection

import com.jcraft.jsch.*
import java.security.MessageDigest

/** Read-only per-session pin; JSch cannot add or replace trust. */
internal class HostKeyStore(endpoint: HostEndpoint, private val pinned: PublicHostKey) : HostKeyRepository {
    @Volatile var mismatchObserved = false
        private set
    private val address = if (endpoint.port == 22) endpoint.host else "[${endpoint.host}]:${endpoint.port}"
    override fun check(host: String, key: ByteArray): Int = when {
        host != address -> HostKeyRepository.NOT_INCLUDED
        MessageDigest.isEqual(pinned.blob, key) -> HostKeyRepository.OK
        else -> HostKeyRepository.CHANGED.also { mismatchObserved = true }
    }
    override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit
    override fun remove(host: String?, type: String?) = Unit
    override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
    override fun getKnownHostsRepositoryID() = "pairing-pin"
    override fun getHostKey(): Array<HostKey> = arrayOf(HostKey(address, pinned.blob))
    override fun getHostKey(host: String?, type: String?): Array<HostKey> =
        if ((host == null || host == address) && (type == null || type == pinned.type)) getHostKey() else emptyArray()
}
