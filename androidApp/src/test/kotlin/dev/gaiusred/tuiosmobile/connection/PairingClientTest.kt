package dev.gaiusred.tuiosmobile.connection

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PairingClientTest {
    @Test fun malformedInvitationNeverConnects() = runTest {
        val client = PairingClient(PairingHttp { _, _ -> error("Malformed invitation reached network") }, MemoryKeys())
        for (input in listOf("https://pair", "tuios://pair?v=1", "tuios://pair?bad=%ZZ", "x".repeat(8193))) {
            assertTrue(client.pair(PairInvitation(input, "phone")) is PairingOutcome.InvalidInvitation)
        }
    }

    @Test fun checkAndMacMatchIndependentVectors() {
        val token = ByteArray(16) { it.toByte() }
        assertEquals("565911", PairingProof.check(token, "test-key"))
        assertEquals("NMqqxtivJ6tXfRsa7UoYtBbOPt_k15n8sOp5fQg4e8o", PairingProof.requestMac(token, "phone", "test-key"))
    }

    private class MemoryKeys : PhoneKeyStore {
        override fun create(alias: String): PhonePublicKey = error("Invalid invitation must not generate keys")
        override fun privateKey(alias: String): ByteArray = error("No key")
        override fun delete(alias: String) = Unit
    }
}
