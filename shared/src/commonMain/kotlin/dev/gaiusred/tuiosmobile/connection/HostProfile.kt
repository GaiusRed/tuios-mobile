package dev.gaiusred.tuiosmobile.connection

import dev.gaiusred.tuiosmobile.identity.HostId
import kotlinx.serialization.Serializable

@Serializable
data class HostEndpoint(val host: String, val port: Int) {
    init { require(host.isNotBlank() && host.length <= 253 && port in 1..65535) }
}

/** Full public host key only. The encrypted phone identity is addressed by alias. */
@Serializable
data class HostProfile(val id: HostId, val machine: String, val user: String, val device: String,
    val sshCandidates: List<HostEndpoint>, val hostKey: String, val keyAlias: String)

interface HostProfileStore {
    suspend fun list(): List<HostProfile>
    suspend fun save(profile: HostProfile)
    suspend fun remove(id: HostId)
    suspend fun clear() { list().forEach { remove(it.id) } }
}
