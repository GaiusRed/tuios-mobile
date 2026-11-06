package dev.gaiusred.tuiosmobile.connection

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object PairingProof {
    private fun hmac(token: ByteArray, text: String): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(token, "HmacSHA256")); doFinal(text.toByteArray(Charsets.UTF_8))
    }
    fun check(token: ByteArray, key: String): String {
        val value = ByteBuffer.wrap(hmac(token, "tuios-pair-v1-check\n$key")).int.toLong() and 0xffffffffL
        return (value % 1_000_000).toString().padStart(6, '0')
    }
    fun requestMac(token: ByteArray, device: String, key: String): String =
        base64Url(hmac(token, "tuios-pair-v1\n$device\n$key"))
    internal fun verifySuccess(token: ByteArray, device: String, key: String, supplied: String): Boolean =
        try { MessageDigest.isEqual(hmac(token, "tuios-pair-v1-ok\n$device\n$key"), decodeBase64(supplied, true)) }
        catch (_: IllegalArgumentException) { false }
}

internal fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
internal fun sshFingerprint(blob: ByteArray): String = "SHA256:" +
    Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(blob))

internal data class PublicHostKey(val type: String, val blob: ByteArray) {
    val fingerprint: String get() = sshFingerprint(blob)
}

internal fun parseHostKey(line: String): PublicHostKey {
    require(line.length in 1..8192 && line.none { it.isISOControl() })
    val parts = line.trim().split(Regex(" +"))
    require(parts.size in 2..3)
    require(parts[0] in setOf("ssh-ed25519", "ecdsa-sha2-nistp256", "ecdsa-sha2-nistp384", "ecdsa-sha2-nistp521", "ssh-rsa"))
    val blob = decodeBase64(parts[1])
    require(blob.size in 16..6144)
    val buffer = ByteBuffer.wrap(blob)
    val size = buffer.int
    require(size in 1..64 && size <= buffer.remaining())
    val type = ByteArray(size).also { buffer.get(it) }.toString(Charsets.US_ASCII)
    require(type == parts[0])
    // JSch parses the remaining algorithm-specific key before authentication.
    com.jcraft.jsch.HostKey("paired", blob)
    return PublicHostKey(type, blob)
}
