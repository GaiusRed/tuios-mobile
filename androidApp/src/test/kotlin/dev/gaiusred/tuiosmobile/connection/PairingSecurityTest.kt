package dev.gaiusred.tuiosmobile.connection

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class PairingSecurityTest {
    private val token = ByteArray(16) { it.toByte() }
    private val type = "ssh-ed25519".toByteArray()
    private val blob = ByteBuffer.allocate(4 + type.size + 4 + 32).putInt(type.size).put(type).putInt(32).put(ByteArray(32) { 1 }).array()
    private val hostKey = "ssh-ed25519 ${Base64.getEncoder().encodeToString(blob)}"
    private val invitation = "tuios://pair?v=1&m=fabricated&u=invited-user&s=100.100.1.1:22&p=100.100.1.1:1234&fp=${sshFingerprint(blob)}&t=AAECAwQFBgcICQoLDA0ODw"

    @Test fun validApprovalPinsKeyAndIgnoresReturnedCommandAndUser() = runTest {
        val keys = Keys()
        lateinit var client: PairingClient
        client = PairingClient(PairingHttp { _, body ->
            assertTrue(client.progress.value is PairingProgress.AwaitingComparison)
            val request = Json.parseToJsonElement(String(body)).jsonObject
            assertEquals("phone", request["device"]?.jsonPrimitive?.content)
            assertNotNull(request["mac"])
            response()
        }, keys)
        val outcome = client.pair(PairInvitation(invitation, "phone")) as PairingOutcome.Paired
        assertEquals("invited-user", outcome.host.user)
        assertEquals(hostKey, outcome.host.hostKey)
        assertFalse(keys.deleted)
        assertEquals(PairingProgress.Idle, client.progress.value)
    }

    @Test fun forgedMacChangedFingerprintAndWrongCheckAreRejected() = runTest {
        for (field in listOf("mac", "check", "host_key", "device")) {
            val keys = Keys()
            val reply = Json.parseToJsonElement(String(response())).jsonObject.toMutableMap()
            reply[field] = JsonPrimitive("forged")
            val client = PairingClient(PairingHttp { _, _ -> JsonObject(reply).toString().toByteArray() }, keys)
            val result = client.pair(PairInvitation(invitation, "phone"))
            assertTrue("Rejected $field", result is PairingOutcome.VerificationFailed)
            assertTrue(keys.deleted)
        }
    }

    @Test fun lostAcknowledgmentDoesNotReplayOrDeletePossiblyInstalledKey() = runTest {
        var posts = 0
        val keys = Keys()
        val client = PairingClient(PairingHttp { _, _ -> posts++; throw java.io.IOException("fabricated") }, keys)
        assertTrue(client.pair(PairInvitation(invitation, "phone")) is PairingOutcome.Uncertain)
        assertEquals(1, posts)
        assertFalse(keys.deleted)
    }

    @Test fun duplicateSecurityParametersInvalidPercentEncodingAndExpiredHostNeverBecomeTrusted() = runTest {
        for (suffix in listOf("&t=AAECAwQFBgcICQoLDA0ODw", "&v=1", "&m=%ZZ", "#fragment")) {
            val client = PairingClient(PairingHttp { _, _ -> error("Invalid invitation reached network") }, Keys())
            assertTrue(client.pair(PairInvitation(invitation + suffix, "phone")) is PairingOutcome.InvalidInvitation)
        }
        val client = PairingClient(PairingHttp { _, _ -> throw PairingRejected(410) }, Keys())
        assertEquals(PairingOutcome.Rejected(410), client.pair(PairInvitation(invitation, "phone")))
    }

    private fun response(): ByteArray {
        val mac = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(token, "HmacSHA256"))
            Base64.getUrlEncoder().withoutPadding().encodeToString(doFinal("tuios-pair-v1-ok\nphone\n$hostKey".toByteArray()))
        }
        return buildJsonObject {
            put("ok", true); put("device", "phone"); put("user", "untrusted-user"); put("command", "arbitrary untrusted shell text")
            put("host_key", hostKey); put("mac", mac); put("check", "565911")
        }.toString().toByteArray()
    }
    private class Keys : PhoneKeyStore {
        var deleted = false
        override fun create(alias: String) = PhonePublicKey("test-key", "SHA256:phone-test-fingerprint")
        override fun privateKey(alias: String): ByteArray = error("No SSH during pairing")
        override fun delete(alias: String) { deleted = true }
    }
}
