package dev.gaiusred.tuiosmobile.protocol

/** Only approved operations are expressible; no arbitrary UI-supplied verbs. */
sealed interface DaemonRequest {
    data object ListSessions : DaemonRequest
    data class Hello(val client: String = "tuios-mobile", val version: String = "0.1.0") : DaemonRequest
    data object ListVerbs : DaemonRequest
    data class ListAgents(val session: String? = null) : DaemonRequest
    data class ListAttention(val session: String? = null) : DaemonRequest
    data class PeekPrompt(val session: String, val window: String) : DaemonRequest
    data class GetApproval(val session: String, val requestId: String) : DaemonRequest
    data class AttachPresence(val session: String?) : DaemonRequest
    data class Transcript(val session: String, val window: String, val humanNonce: String,
        val after: String? = null, val before: String? = null, val limit: Int = 200) : DaemonRequest
    data class ReadMail(val session: String, val thread: ULong? = null, val limit: Int = 256) : DaemonRequest
    data class SendMail(val session: String, val to: String, val humanNonce: String,
        val text: String, val replyTo: ULong? = null) : DaemonRequest
    data class Respond(val session: String, val window: String, val action: String,
        val promptId: String, val humanNonce: String, val value: String? = null,
        val riskAck: List<String> = emptyList()) : DaemonRequest
    data class ReplyApproval(val requestId: String, val session: String, val window: String,
        val decision: String, val summary: String, val humanNonce: String,
        val riskAck: List<String> = emptyList(), val planSha: String? = null, val message: String? = null) : DaemonRequest
    data class AnswerAsk(val requestId: String, val answer: String, val question: String,
        val humanNonce: String) : DaemonRequest
    data class DismissAttention(val id: String, val humanNonce: String) : DaemonRequest
}
