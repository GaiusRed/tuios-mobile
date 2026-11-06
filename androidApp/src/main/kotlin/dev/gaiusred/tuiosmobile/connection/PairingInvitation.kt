package dev.gaiusred.tuiosmobile.connection

import java.net.URI
import java.net.URLDecoder
import java.util.Base64

class PairInvitation(val uri: String, val device: String) {
    override fun toString() = "PairInvitation(redacted)"
}

internal class ValidInvitation(val machine: String, val user: String,
    val ssh: List<HostEndpoint>, val pairing: List<HostEndpoint>, val fingerprint: String, val token: ByteArray)

internal fun parseInvitation(input: PairInvitation): ValidInvitation {
    require(input.uri.length in 1..8192)
    require(input.device.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")))
    require(input.uri.none { it.isISOControl() })
    val uri = URI(input.uri)
    require(uri.scheme == "tuios" && uri.rawAuthority == "pair" && uri.rawPath.isNullOrEmpty() && uri.rawFragment == null)
    val entries = requireNotNull(uri.rawQuery).split('&').map { part ->
        val split = part.split('=', limit = 2)
        require(split.size == 2)
        fun decode(value: String): String = URLDecoder.decode(value.replace("+", "%2B"), "UTF-8").also {
            require(it.none { c -> c.isISOControl() || c == '\uFFFD' })
        }
        decode(split[0]) to decode(split[1])
    }
    require(entries.map { it.first }.distinct().size == entries.size)
    val values = entries.toMap()
    require(values.keys == setOf("v", "m", "u", "s", "p", "fp", "t") && values["v"] == "1")
    fun text(name: String) = values.getValue(name).also { require(it.isNotBlank() && it.length <= 253) }
    val fingerprint = text("fp")
    require(fingerprint.startsWith("SHA256:"))
    require(decodeBase64(fingerprint.removePrefix("SHA256:"), url = false).size == 32)
    val token = text("t")
    require(token.matches(Regex("[A-Za-z0-9_-]{22}")))
    val decodedToken = decodeBase64(token, url = true)
    require(decodedToken.size == 16 && Base64.getUrlEncoder().withoutPadding().encodeToString(decodedToken) == token)
    return ValidInvitation(text("m"), text("u"), parseCandidates(text("s")), parseCandidates(text("p")), fingerprint, decodedToken)
}

private fun parseCandidates(value: String): List<HostEndpoint> {
    val candidates = value.split(',')
    require(candidates.size in 1..16)
    return candidates.map { candidate ->
        val uri = URI("ssh://$candidate")
        require(uri.userInfo == null && uri.path.isNullOrEmpty() && uri.query == null && uri.fragment == null)
        val host = requireNotNull(uri.host).removeSurrounding("[", "]")
        require(host.none { it.isWhitespace() } && !host.contains('%'))
        HostEndpoint(host, uri.port)
    }
}

internal fun decodeBase64(value: String, url: Boolean = false): ByteArray =
    (if (url) Base64.getUrlDecoder() else Base64.getDecoder()).decode(value)
