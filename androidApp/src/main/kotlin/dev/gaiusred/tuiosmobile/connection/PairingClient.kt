package dev.gaiusred.tuiosmobile.connection

import dev.gaiusred.tuiosmobile.identity.HostId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*
import java.util.UUID

sealed interface PairingOutcome {
    data object InvalidInvitation : PairingOutcome
    data class Paired(val host: HostProfile) : PairingOutcome
    data class Rejected(val status: Int) : PairingOutcome
    data object VerificationFailed : PairingOutcome
    data class Uncertain(val keyAlias: String) : PairingOutcome
}
sealed interface PairingProgress {
    data object Idle : PairingProgress
    data class AwaitingComparison(val check: String, val phoneFingerprint: String, val hostFingerprint: String) : PairingProgress
}
fun interface PairingHttp { suspend fun post(endpoint: HostEndpoint, body: ByteArray): ByteArray }
class PairingRejected(val status: Int) : Exception("Pairing rejected")
data class PhonePublicKey(val line: String, val fingerprint: String)
interface PhoneKeyStore {
    fun aliases(): Set<String> = emptySet()
    fun create(alias: String): PhonePublicKey
    fun privateKey(alias: String): ByteArray
    fun delete(alias: String)
}
class PairingClient(private val http: PairingHttp, private val keys: PhoneKeyStore) {
    private val mutableProgress = MutableStateFlow<PairingProgress>(PairingProgress.Idle)
    val progress = mutableProgress.asStateFlow()
    private val gate = kotlinx.coroutines.sync.Mutex()

    suspend fun pair(invitation: PairInvitation): PairingOutcome {
        if (!gate.tryLock()) return PairingOutcome.InvalidInvitation
        var parsed: ValidInvitation? = null
        var alias: String? = null
        var posted = false
        try {
            parsed = try { parseInvitation(invitation) } catch (_: Exception) { return PairingOutcome.InvalidInvitation }
            alias = "phone-${UUID.randomUUID()}"
            val phone = keys.create(alias)
            val check = PairingProof.check(parsed.token, phone.line)
            mutableProgress.value = PairingProgress.AwaitingComparison(check, phone.fingerprint, parsed.fingerprint)
            val body = buildJsonObject {
                put("device", invitation.device); put("key", phone.line)
                put("mac", PairingProof.requestMac(parsed.token, invitation.device, phone.line))
            }.toString().toByteArray(Charsets.UTF_8)
            require(body.size <= 8192)
            // A POST can install a key even if its acknowledgment is lost. Never race or replay it.
            posted = true
            val bytes = http.post(parsed.pairing.first(), body)
            require(bytes.size <= 16_384)
            val response = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
            fun field(name: String) = response.getValue(name).jsonPrimitive.content
            if (response["ok"]?.jsonPrimitive?.booleanOrNull != true) return PairingOutcome.Rejected(403).also { keys.delete(alias) }
            val hostKey = field("host_key")
            if (field("device") != invitation.device || field("check") != check ||
                !PairingProof.verifySuccess(parsed.token, field("device"), hostKey, field("mac")) ||
                parseHostKey(hostKey).fingerprint != parsed.fingerprint) {
                keys.delete(alias)
                return PairingOutcome.VerificationFailed
            }
            return PairingOutcome.Paired(HostProfile(HostId(UUID.randomUUID().toString()), parsed.machine,
                parsed.user, invitation.device, parsed.ssh, hostKey, alias))
        } catch (failure: CancellationException) {
            if (!posted) alias?.let(keys::delete)
            throw failure
        } catch (failure: PairingRejected) {
            alias?.let(keys::delete)
            return PairingOutcome.Rejected(failure.status)
        } catch (_: Exception) {
            if (!posted) alias?.let(keys::delete)
            return if (posted && alias != null) PairingOutcome.Uncertain(alias) else PairingOutcome.VerificationFailed
        } finally {
            parsed?.token?.fill(0)
            mutableProgress.value = PairingProgress.Idle
            gate.unlock()
        }
    }
}
