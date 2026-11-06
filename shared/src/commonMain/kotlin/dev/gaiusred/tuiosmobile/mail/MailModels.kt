package dev.gaiusred.tuiosmobile.mail

import dev.gaiusred.tuiosmobile.identity.HostId
import dev.gaiusred.tuiosmobile.storage.*
import kotlin.time.Instant

data class MailTarget(val hostId: HostId, val sessionId: String, val daemonBootId: String)
/** Local identity only. Legacy is explicit and grants no daemon/session authority. */
data class MailDraftTarget(val hostId: HostId, val sessionId: String, val generation: MailDraftGeneration,
    val recipient: String, val threadId: String?)
fun MailDraft.localTarget() = MailDraftTarget(hostId, sessionId, generation, recipient, threadId)
data class MailItem(val id: String, val content: CachedContent.Mail, val sentAt: Instant?,
    val receivedAt: Instant, val missingParent: Boolean = false)
data class MailRecipient(val id: String, val name: String)
data class MailMailbox(val target: MailTarget, val sessionName: String)
data class MailSnapshot(val target: MailTarget, val sessionName: String = "Saved session",
    val messages: List<MailItem> = emptyList(), val drafts: List<MailDraft> = emptyList(),
    val recipients: List<MailRecipient> = emptyList(), val offline: Boolean = true,
    val readOnly: Boolean = true, val historyGap: Boolean = false,
    val problem: MailProblem? = null, val legacyDrafts: List<MailDraft> = emptyList(), val canSend: Boolean = true)
enum class MailProblem { Unavailable, PermissionDenied, TargetChanged, Unsupported }
