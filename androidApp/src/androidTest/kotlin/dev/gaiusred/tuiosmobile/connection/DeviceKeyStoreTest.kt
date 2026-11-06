package dev.gaiusred.tuiosmobile.connection

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class DeviceKeyStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun localCredentialIndexFindsDetachedEncryptedKeysWithoutDecrypting() {
        val alias = "test-${UUID.randomUUID()}"
        val store = DeviceKeyStore(context)
        try {
            store.create(alias)
            assertTrue(store.aliases().contains(alias))
            store.delete(alias)
            assertFalse(store.aliases().contains(alias))
        } finally { store.delete(alias) }
    }

    @Test fun encryptionRoundTripAndDeletionOnApi26() {
        val alias = "test-${UUID.randomUUID()}"
        val store = DeviceKeyStore(context)
        try {
            val public = store.create(alias)
            val private = store.privateKey(alias)
            try {
                val pair = KeyPair.load(JSch(), private, public.line.toByteArray())
                try {
                    assertEquals(KeyPair.ECDSA, pair.keyType)
                    assertEquals(public.fingerprint, sshFingerprint(pair.publicKeyBlob))
                    assertNotNull(pair.getSignature("fabricated challenge".toByteArray()))
                    val encrypted = File(context.noBackupFilesDir, "phone-keys/$alias.bin").readBytes()
                    assertFalse(encrypted.contentEquals(private))
                    assertFalse(String(encrypted, Charsets.ISO_8859_1).contains("PRIVATE KEY"))
                } finally { pair.dispose() }
            } finally { private.fill(0) }
            store.delete(alias)
            try { store.privateKey(alias); fail("Deleted key was readable") } catch (failure: ConnectionException) {
                assertEquals(ConnectionState.RepairRequired, failure.reason)
            }
        } finally { store.delete(alias) }
    }

    @Test fun decryptFailureRequiresRepairWithoutLoggingKey() {
        val alias = "test-${UUID.randomUUID()}"
        val store = DeviceKeyStore(context)
        try {
            store.create(alias)
            val file = File(context.noBackupFilesDir, "phone-keys/$alias.bin")
            val encrypted = file.readBytes()
            encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
            file.writeBytes(encrypted)
            try { store.privateKey(alias); fail("Corrupt key was accepted") } catch (failure: ConnectionException) {
                assertEquals(ConnectionState.RepairRequired, failure.reason)
                assertNull(failure.cause)
                assertEquals("Connection unavailable", failure.message)
            }
        } finally { store.delete(alias) }
    }

    @Test fun api26Ed25519FallbackSignsWithoutChangingGlobalProvider() {
        val provider = java.security.Security.getProvider("BC")
        val signature = com.jcraft.jsch.bc.SignatureEd25519()
        signature.init()
        val seed = ByteArray(32) { (it + 1).toByte() }
        val key = org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(seed, 0)
        signature.setPrvKey(seed)
        signature.update("fabricated challenge".toByteArray())
        val signed = signature.sign()
        val verify = com.jcraft.jsch.bc.SignatureEd25519()
        verify.init(); verify.setPubKey(key.generatePublicKey().encoded)
        verify.update("fabricated challenge".toByteArray())
        val algorithm = "ssh-ed25519".toByteArray()
        val wireSignature = java.nio.ByteBuffer.allocate(8 + algorithm.size + signed.size)
            .putInt(algorithm.size).put(algorithm).putInt(signed.size).put(signed).array()
        assertTrue(verify.verify(wireSignature))
        assertSame(provider, java.security.Security.getProvider("BC"))
        seed.fill(0)
    }

    @Test fun api26Curve25519FallbackAgreesWithoutProviderRegistration() {
        val alice = com.jcraft.jsch.bc.XDH().apply { init("X25519", 32) }
        val bob = com.jcraft.jsch.bc.XDH().apply { init("X25519", 32) }
        assertArrayEquals(alice.getSecret(bob.q), bob.getSecret(alice.q))
    }
}
