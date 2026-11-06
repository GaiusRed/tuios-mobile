package dev.gaiusred.tuiosmobile.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

sealed interface DaemonReply {
    data class Success(val result: DaemonResult) : DaemonReply
    data class Failure(val failure: ProtocolFailure) : DaemonReply
    data class Mutation(val outcome: MutationOutcome) : DaemonReply
}

sealed interface DaemonResult {
    val raw: JsonObject
    data class Unknown(val type: String, override val raw: JsonObject) : DaemonResult
    data class Hello(val protocol: Int, val minProtocol: Int, val daemonVersion: String,
        override val raw: JsonObject) : DaemonResult
    data class Verbs(val names: Set<String>, override val raw: JsonObject) : DaemonResult
    data class Sessions(val sessions: List<SessionInfo>, override val raw: JsonObject) : DaemonResult
    data class Agents(val agents: List<AgentRow>, val total: Int, override val raw: JsonObject) : DaemonResult
    data class Transcript(val session: String, val window: String, val harness: String,
        val cursor: String, val reset: Boolean, val more: Boolean, val older: String,
        val entries: List<TranscriptEntry>, val untrusted: Boolean, override val raw: JsonObject) : DaemonResult
    data class Mail(val messages: List<MailMessage>, val total: Int, val evicted: Int,
        override val raw: JsonObject) : DaemonResult
    data class Attention(val items: List<AttentionItem>, val seq: ULong, val bootId: String,
        override val raw: JsonObject) : DaemonResult
    data class Approval(val requestId: String, val kind: String, val session: String, val window: String,
        val summary: String, val tool: String, val target: String, val options: List<String>,
        val alwaysScope: List<String>, val plan: String, val planSha: String, val risks: List<ApprovalRisk>,
        val denyMessage: Boolean, val untrusted: Boolean, override val raw: JsonObject) : DaemonResult
    data class Prompt(val session: String, val window: String, val promptId: String?,
        val found: Boolean, val answerable: Boolean, val actions: List<String>,
        val options: List<PromptOption>, override val raw: JsonObject) : DaemonResult
    data class Presence(val humanNonce: String, val clientId: String, val session: String?,
        override val raw: JsonObject) : DaemonResult
    data class Receipt(val receipt: MutationReceipt, override val raw: JsonObject) : DaemonResult
}

/** Each record retains its original JSON, including unknown fields and nested shapes. */
data class SessionInfo(val id: String, val name: String, val created: Long, val lastActive: Long,
    val windowCount: Int, val attached: Boolean, val width: Int, val height: Int,
    val windows: List<JsonObject>, val raw: JsonObject)

data class AgentRow(val session: String, val windowId: String, val name: String,
    val state: String, val reason: String, val message: String, val source: String,
    val harnessId: String, val foreground: String, val cwd: String, val blockedBy: String,
    val confidence: String, val identity: String, val agentSessionId: String, val group: String,
    val protocol: String, val agentStateAt: Long, val workspace: Int, val unread: Int,
    val seen: Int, val queued: Int, val subagents: Int, val evidenceAgeMs: Long?,
    val completionSeq: ULong, val focused: Boolean, val ready: Boolean, val needsYou: Boolean,
    val finishedUnread: Boolean, val draftHeld: Boolean, val settled: Boolean, val raw: JsonObject)

data class TranscriptEntry(val id: String, val role: String, val kind: String,
    val text: String, val at: Long?, val truncated: Boolean, val raw: JsonObject)

data class MailMessage(val id: ULong, val kind: String, val session: String, val from: String,
    val to: String, val text: String, val threadId: ULong, val replyTo: ULong?,
    val sentAt: Long, val readAt: Long?, val seenAt: Long?, val verifiedHuman: Boolean,
    val claimedHuman: Boolean, val raw: JsonObject, val fromLabel: String? = null,
    val toLabel: String? = null, val replyToMissing: Boolean = false, val subject: String? = null)

data class AttentionItem(val id: String, val kind: String, val session: String,
    val window: String?, val host: String?, val summary: String, val options: List<String>,
    val requestId: String?, val since: Long, val seq: ULong, val risk: List<String>,
    val planSha: String?, val raw: JsonObject)

@Serializable
data class PromptOption(val n: Int, val label: String)

data class ApprovalRisk(val rule: String, val why: String)

data class SubscriptionRequest(val session: String? = null, val afterSeq: ULong? = null,
    val bootId: String? = null, val window: String? = null)

sealed interface DaemonEvent {
    data class Subscribed(val seq: ULong, val bootId: String, val replayed: Int) : DaemonEvent
    data class Record(val type: String, val raw: JsonObject, val seq: ULong? = null,
        val bootId: String? = null, val reason: String? = null, val cursor: String? = null,
        val session: String? = null, val window: String? = null, val time: Long? = null) : DaemonEvent
    data class Failure(val failure: ProtocolFailure) : DaemonEvent
}
