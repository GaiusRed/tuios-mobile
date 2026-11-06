package dev.gaiusred.tuiosmobile.connection

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class DeviceKeyStore(context: Context) : PhoneKeyStore {
    private val directory = File(context.noBackupFilesDir, "phone-keys").apply { mkdirs() }
    private val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @Synchronized override fun aliases(): Set<String> {
        val files = directory.listFiles().orEmpty().mapNotNull { file ->
            val name = file.name.removeSuffix(".bak").removeSuffix(".new")
            name.takeIf { it.endsWith(".bin") }?.removeSuffix(".bin")
                ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,100}")) }
        }
        val orphaned = keystore.aliases().toList().filter { it.startsWith("phone-") && it.matches(Regex("[A-Za-z0-9_-]{1,100}")) }
        return (files + orphaned).toSet()
    }

    @Synchronized override fun create(alias: String): PhonePublicKey {
        val file = file(alias)
        check(!file.exists() && !keystore.containsAlias(alias))
        val pair = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1")); generateKeyPair()
        }
        val public = pair.public as ECPublicKey
        val point = byteArrayOf(4) + coordinate(public.w.affineX) + coordinate(public.w.affineY)
        val blob = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { stream ->
                for (value in listOf("ecdsa-sha2-nistp256".toByteArray(), "nistp256".toByteArray(), point)) {
                    stream.writeInt(value.size); stream.write(value)
                }
            }
        }.toByteArray()
        val line = "ecdsa-sha2-nistp256 ${Base64.getEncoder().encodeToString(blob)}"
        val encoded = pair.private.encoded
        val pem = ("-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, byteArrayOf(10)).encodeToString(encoded) +
            "\n-----END PRIVATE KEY-----\n").toByteArray(Charsets.US_ASCII)
        encoded.fill(0)
        try {
            val aes = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
                generateKey()
            }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, aes)
            cipher.updateAAD(alias.toByteArray(Charsets.UTF_8))
            val encrypted = cipher.doFinal(pem)
            val atomic = AtomicFile(file)
            val output = atomic.startWrite()
            try {
                output.write(byteArrayOf(1)); output.write(cipher.iv); output.write(encrypted)
                atomic.finishWrite(output)
            } catch (failure: Exception) { atomic.failWrite(output); throw failure }
            return PhonePublicKey(line, sshFingerprint(blob))
        } catch (_: Exception) {
            delete(alias)
            throw ConnectionException(ConnectionState.RepairRequired)
        } finally { pem.fill(0) }
    }

    @Synchronized override fun privateKey(alias: String): ByteArray {
        try {
            val file = file(alias)
            require(file.length() in 30..8192)
            val encrypted = AtomicFile(file).readFully()
            require(encrypted[0] == 1.toByte())
            val aes = keystore.getKey(alias, null) as? SecretKey ?: error("No key")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, aes, GCMParameterSpec(128, encrypted, 1, 12))
            cipher.updateAAD(alias.toByteArray(Charsets.UTF_8))
            return cipher.doFinal(encrypted, 13, encrypted.size - 13)
        } catch (_: Exception) { throw ConnectionException(ConnectionState.RepairRequired) }
    }

    @Synchronized override fun delete(alias: String) {
        AtomicFile(file(alias)).delete()
        if (keystore.containsAlias(alias)) keystore.deleteEntry(alias)
    }

    private fun file(alias: String): File {
        require(alias.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        return File(directory, "$alias.bin")
    }
    private fun coordinate(value: BigInteger): ByteArray = value.toByteArray().let {
        require(it.size <= 33)
        it.takeLast(32).toByteArray().let { bytes -> ByteArray(32 - bytes.size) + bytes }
    }
}
