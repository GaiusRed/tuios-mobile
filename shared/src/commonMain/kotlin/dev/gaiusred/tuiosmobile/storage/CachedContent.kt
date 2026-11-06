package dev.gaiusred.tuiosmobile.storage

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

/** Allowlisted display data only. Original daemon JSON and presence nonces have no storage field. */
@Serializable
sealed interface CachedContent {
    /** Immutable identity captured for one transcript generation, stored beside its entries. */
    @Serializable
    @SerialName("transcript_generation")
    data class TranscriptGeneration(val sessionName: String, val agentName: String,
        val agentSessionId: String, val harnessId: String) : CachedContent

    @Serializable
    @SerialName("transcript")
    data class Transcript(val role: String, val kind: String, val text: String,
        val truncated: Boolean = false, val tool: String? = null, val target: String? = null,
        val status: String? = null, val toolId: String? = null, val plan: String? = null,
        val todos: List<CachedTodo> = emptyList(), val diff: CachedDiff? = null) : CachedContent

    @Serializable
    @SerialName("mail")
    data class Mail(val kind: String, val from: String, val to: String, val text: String,
        val threadId: String, val replyTo: String? = null,
        val verifiedHuman: Boolean = false, val claimedHuman: Boolean = false,
        val sessionName: String = "", val fromLabel: String = "", val toLabel: String = "",
        val remoteReadAt: Long? = null, val localRead: Boolean = false,
        val replyToMissing: Boolean = false, val subject: String? = null) : CachedContent

    @Serializable
    @SerialName("mail_gap")
    data class MailGap(val evicted: Int) : CachedContent

    @Serializable
    @SerialName("attention")
    data class Attention(val kind: String, val summary: String, val options: List<String>,
        val requestId: String? = null, val risk: List<String> = emptyList(),
        val planSha: String? = null) : CachedContent

    /** Written before a possible response; contains display data, never human presence proof. */
    @Serializable
    @SerialName("attention_attempt")
    data class AttentionAttempt(val agentSessionId: String, val itemId: String, val kind: String,
        val sessionName: String, val agentName: String, val summary: String, val requestId: String?,
        val promptId: String?, val action: String, val status: String) : CachedContent

    @Serializable
    @SerialName("session")
    data class Session(val name: String, val windowCount: Int, val attached: Boolean) : CachedContent

    @Serializable
    @SerialName("agent")
    data class Agent(val name: String, val state: String, val harnessId: String,
        val agentSessionId: String, val needsYou: Boolean, val ready: Boolean) : CachedContent
}

@Serializable
data class CachedTodo(val text: String, val status: String)

@Serializable
data class CachedDiff(val file: String, val added: Int, val removed: Int, val hunks: List<CachedHunk>,
    val truncated: Boolean = false, val plain: Boolean = false, val wholeReplace: Boolean = false)

@Serializable
data class CachedHunk(val oldStart: Int, val newStart: Int, val lines: List<CachedDiffLine>)

@Serializable
data class CachedDiffLine(val op: String, val text: String)
