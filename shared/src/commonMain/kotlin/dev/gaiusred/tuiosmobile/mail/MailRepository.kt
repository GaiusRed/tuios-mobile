package dev.gaiusred.tuiosmobile.mail

import dev.gaiusred.tuiosmobile.connection.*
import dev.gaiusred.tuiosmobile.identity.HostId
import dev.gaiusred.tuiosmobile.identity.RecordKey
import dev.gaiusred.tuiosmobile.protocol.*
import dev.gaiusred.tuiosmobile.sessions.SessionRepository
import dev.gaiusred.tuiosmobile.storage.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Instant

class MailRepository(private val connection: HostConnection, private val history: HistoryStore,
    private val sessions: SessionRepository, private val selectedHost: StateFlow<HostId?>,
    private val scope: CoroutineScope, private val now: () -> Instant) {
    private val gate = Mutex()
    private val problems = MutableStateFlow<Map<MailTarget, MailProblem>>(emptyMap())

    init {
        scope.launch {
            sessions.state.map { snapshot ->
                val proof = snapshot.verification
                if (snapshot.offline || proof == null || snapshot.mailRevision == null) null
                else Triple(proof, snapshot.mailRevision, snapshot.sessions.map { it.id })
            }.distinctUntilChanged().collectLatest { ready ->
                if (ready != null) {
                    val (proof, _, ids) = ready
                    ids.forEach { refresh(MailTarget(proof.hostId, it, proof.daemonBootId)) }
                }
            }
        }
    }

    /** Called only with the app connection and sync paused; drains writes before local deletion. */
    suspend fun maintainHistory(block: suspend () -> Unit) = gate.withLock { block() }

    /** Local-only discovery includes explicit legacy generations and needs no checkpoint or live profile. */
    fun savedDrafts(hostId: HostId): Flow<List<MailDraft>> = history.observe(HistoryQuery(hostId))
        .map { it.drafts }.distinctUntilChanged()
    fun savedDraft(target: MailDraftTarget): Flow<MailDraft?> = history.observe(HistoryQuery(target.hostId, target.sessionId))
        .map { it.drafts.find { draft -> draft.localTarget() == target } }.distinctUntilChanged()

    fun mailboxes(hostId: HostId): Flow<List<MailMailbox>> = history.observe(HistoryQuery(hostId)).map { cached ->
        val current = cached.checkpoint?.let { checkpoint -> checkpoint.sessions.map { session ->
            MailMailbox(MailTarget(hostId, session.id, checkpoint.bootId), session.name)
        } }.orEmpty()
        val retained = cached.records.mapNotNull { record -> (record.entry.content as? CachedContent.Mail)?.let {
            MailMailbox(MailTarget(hostId, record.key.sessionId, record.key.daemonBootId), it.sessionName.ifBlank { "Saved session" })
        } }
        val drafts = cached.drafts.mapNotNull { draft -> draft.daemonBootId?.let {
            MailMailbox(MailTarget(hostId, draft.sessionId, it), current.find { box -> box.target.sessionId == draft.sessionId }?.sessionName ?: "Saved session")
        } }
        (current + retained + drafts).distinctBy { it.target }
    }.distinctUntilChanged()

    fun observe(target: MailTarget): Flow<MailSnapshot> = combine(history.observe(query(target)),
        sessions.state, connection.state, selectedHost, problems) { cached, listing, connected, selected, failures ->
        val session = listing.sessions.find { listing.hostId == target.hostId && it.id == target.sessionId }
        val records = cached.records.filter { it.key == key(target) }
        val mail = records.filter { it.entry.content is CachedContent.Mail }
        val ids = mail.map { it.entry.id }.toSet()
        val messages = mail.map { record ->
            val content = record.entry.content as CachedContent.Mail
            MailItem(record.entry.id, content, record.entry.serverAt, record.entry.receivedAt,
                content.replyToMissing || (content.replyTo != null && content.replyTo !in ids))
        }
        val current = knownSession(target, cached) != null
        MailSnapshot(target, session?.name?.takeIf { current } ?: messages.firstOrNull()?.content?.sessionName?.ifBlank { "Saved session" } ?: "Saved session",
            messages, cached.drafts.filter { it.daemonBootId == target.daemonBootId },
            if (current) session?.agents.orEmpty().map { MailRecipient(it.target.windowId, it.name) } else emptyList(),
            connected !is ConnectionState.Connected || connected.host.id != target.hostId || selected != target.hostId || listing.offline,
            !current, records.any { it.entry.content is CachedContent.MailGap }, failures[target],
            if (current) cached.drafts.filter { it.generation == MailDraftGeneration.Legacy } else emptyList(),
            canSend = listing.verification?.supports("send-agent-message", "attach-presence") != false)
    }.distinctUntilChanged()

    suspend fun refresh(target: MailTarget) = gate.withLock {
        val generation = (connection.state.value as? ConnectionState.Connected)?.generation ?: return@withLock
        val cached = history.observe(query(target)).first()
        val session = currentSession(target, cached, generation) ?: return@withLock
        if (sessions.state.value.verification?.supports("read-agent-messages") == false) {
            problem(target, MailProblem.Unsupported); return@withLock
        }
        val client = JsonDaemonClient(ProtocolChannelFactory {
            check(connectionMatches(target, generation))
            val channel = connection.openChannel(ChannelKind.HUMAN)
            if (!connectionMatches(target, generation)) { channel.close(); error("Mail connection changed") }
            channel
        }, scope)
        try {
            val reply = client.request(DaemonRequest.ReadMail(session.name))
            val result = ((reply as? DaemonReply.Success)?.result as? DaemonResult.Mail)
            if (result == null) {
                problem(target, if ((reply as? DaemonReply.Failure)?.failure is ProtocolFailure.Daemon)
                    MailProblem.PermissionDenied else MailProblem.Unavailable)
                return@withLock
            }
            if (currentSession(target, history.observe(query(target)).first(), generation)?.name != session.name) return@withLock
            val existing = cached.records.filter { it.key == key(target) }.associateBy { it.entry.id }
            fun label(id: String) = if (id == "human") "Human" else session.agents.find { it.target.windowId == id }?.name ?: id
            val entries = result.messages.filter { it.session == session.name }.map { message ->
                val previous = existing[message.id.toString()]?.entry?.content as? CachedContent.Mail
                CachedEntry(message.id.toString(), CachedContent.Mail(message.kind, message.from, message.to,
                    message.text, message.threadId.toString(), message.replyTo?.toString(), message.verifiedHuman,
                    message.claimedHuman, session.name, message.fromLabel ?: label(message.from), message.toLabel ?: label(message.to), message.readAt,
                    previous?.localRead ?: false, message.replyToMissing, message.subject), now(),
                    Instant.fromEpochSeconds(message.sentAt / 1_000_000_000, message.sentAt % 1_000_000_000))
            }.toMutableList()
            if (result.evicted > 0) entries += CachedEntry("mail-gap", CachedContent.MailGap(result.evicted), now())
            history.apply(HistoryBatch(key(target), entries))
            problem(target, null)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { problem(target, MailProblem.Unavailable) }
        finally { withContext(NonCancellable) { client.close() } }
    }

    suspend fun saveDraft(draft: MailDraft) = gate.withLock {
        val saved = savedDraft(draft)
        if (saved?.status == MailDraftStatus.Uncertain || saved?.status == MailDraftStatus.Sent) return@withLock
        history.saveDraft(draft.copy(status = MailDraftStatus.Draft))
    }

    suspend fun adoptLegacyDraft(draft: MailDraft, target: MailTarget, recipient: String): Boolean = gate.withLock {
        if (draft.generation != MailDraftGeneration.Legacy || draft.hostId != target.hostId || draft.sessionId != target.sessionId ||
            draft.status !in setOf(MailDraftStatus.Draft, MailDraftStatus.Rejected)) return@withLock false
        val generation = (connection.state.value as? ConnectionState.Connected)?.generation ?: return@withLock false
        val session = currentSession(target, history.observe(query(target)).first(), generation) ?: return@withLock false
        if (session.agents.none { it.target.windowId == recipient }) return@withLock false
        history.adoptLegacyDraft(draft, draft.copy(recipient = recipient, daemonBootId = target.daemonBootId,
            threadId = null, replyTo = null, status = MailDraftStatus.Draft))
    }

    /** Called only after the user explicitly discards the saved attempt, including its warning. */
    suspend fun discardDraft(draft: MailDraft) = gate.withLock {
        history.saveDraft(draft.copy(text = "", status = MailDraftStatus.Draft))
    }

    suspend fun markRead(target: MailTarget, ids: Set<String>) = gate.withLock {
        val entries = history.observe(query(target)).first().records.filter { it.key == key(target) && it.entry.id in ids }
            .mapNotNull { record -> (record.entry.content as? CachedContent.Mail)?.let {
                record.entry.copy(content = it.copy(localRead = true))
            } }
        if (entries.isNotEmpty()) history.apply(HistoryBatch(key(target), entries))
    }

    suspend fun send(draft: MailDraft): MutationOutcome = gate.withLock {
        val target = draft.daemonBootId?.let { MailTarget(draft.hostId, draft.sessionId, it) }
            ?: return@withLock rejected("Choose a current session")
        val generation = (connection.state.value as? ConnectionState.Connected)?.generation
            ?: return@withLock rejected("This session is offline")
        if (savedDraft(draft)?.status in setOf(MailDraftStatus.Uncertain, MailDraftStatus.Sent))
            return@withLock rejected("A saved send attempt needs your review")
        val session = currentSession(target, history.observe(query(target)).first(), generation)
            ?: return@withLock rejected("This session is read only")
        if (sessions.state.value.verification?.supports("send-agent-message", "attach-presence") == false) {
            problem(target, MailProblem.Unsupported)
            return@withLock rejected("This daemon does not support sending Mail")
        }
        if (draft.text.isBlank() || session.agents.none { it.target.windowId == draft.recipient })
            return@withLock rejected("Choose a current recipient")
        val replyTo = draft.replyTo?.toULongOrNull()
        if (draft.replyTo != null && replyTo == null) return@withLock rejected("Reply message is unavailable")
        val lease = try { connection.acquirePresence(session.name, scope) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            history.saveDraft(draft.copy(status = MailDraftStatus.Rejected))
            problem(target, MailProblem.PermissionDenied)
            return@withLock rejected("Human Mail permission is unavailable")
        }
        try {
            val latest = currentSession(target, history.observe(query(target)).first(), generation)
            if (latest?.name != session.name || latest.agents.none { it.target.windowId == draft.recipient })
                return@withLock rejected("The selected host, session or recipient changed")
            // Write before any possible mutation. Process death or cancellation must retain this marker.
            history.saveDraft(draft.copy(status = MailDraftStatus.Uncertain))
            val ready = currentSession(target, history.observe(query(target)).first(), generation)
            if (ready?.name != session.name || ready.agents.none { it.target.windowId == draft.recipient }) {
                history.saveDraft(draft.copy(status = MailDraftStatus.Rejected))
                return@withLock rejected("The selected host, session or recipient changed")
            }
            val response = lease.client.request(DaemonRequest.SendMail(session.name, draft.recipient,
                lease.nonce, draft.text, replyTo))
            val reported = (response as? DaemonReply.Mutation)?.outcome ?: MutationOutcome.Uncertain
            val outcome = if (reported is MutationOutcome.Accepted &&
                (reported.receipt.verifiedHuman != true || reported.receipt.messageId == null)) MutationOutcome.Uncertain else reported
            withContext(NonCancellable) {
                history.saveDraft(draft.copy(status = when (outcome) {
                    is MutationOutcome.Accepted -> MailDraftStatus.Sent
                    is MutationOutcome.Rejected -> MailDraftStatus.Rejected
                    MutationOutcome.Uncertain -> MailDraftStatus.Uncertain
                }))
            }
            if (outcome is MutationOutcome.Rejected) problem(target, MailProblem.PermissionDenied)
            outcome
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { MutationOutcome.Uncertain }
        finally { lease.close() }
    }

    private suspend fun savedDraft(draft: MailDraft) = history.observe(HistoryQuery(draft.hostId, draft.sessionId)).first()
        .drafts.find { it.recipient == draft.recipient && it.threadId == draft.threadId && it.generation == draft.generation }

    private fun currentSession(target: MailTarget, cached: HistorySnapshot, generation: ConnectionGeneration): dev.gaiusred.tuiosmobile.sessions.SessionSummary? {
        val connected = connection.state.value as? ConnectionState.Connected ?: return null
        if (!connectionMatches(target, generation) || sessions.state.value.offline) return null
        val verified = sessions.state.value.verification ?: return null
        if (verified.hostId != target.hostId || verified.connectionGeneration !== connected.generation ||
            verified.daemonBootId != target.daemonBootId) return null
        return knownSession(target, cached)
    }
    private fun connectionMatches(target: MailTarget, generation: ConnectionGeneration): Boolean {
        val connected = connection.state.value as? ConnectionState.Connected ?: return false
        return selectedHost.value == target.hostId && connected.host.id == target.hostId && connected.generation === generation
    }
    private fun knownSession(target: MailTarget, cached: HistorySnapshot): dev.gaiusred.tuiosmobile.sessions.SessionSummary? {
        val listing = sessions.state.value
        if (selectedHost.value != target.hostId || listing.hostId != target.hostId || cached.checkpoint?.bootId != target.daemonBootId) return null
        return listing.sessions.find { it.id == target.sessionId }
    }
    private fun problem(target: MailTarget, value: MailProblem?) {
        problems.value = if (value == null) problems.value - target else problems.value + (target to value)
    }
    private fun rejected(message: String) = MutationOutcome.Rejected(ProtocolFailure.Daemon("permission_denied", message))
    private fun query(target: MailTarget) = HistoryQuery(target.hostId, target.sessionId)
    private fun key(target: MailTarget) = RecordKey(target.hostId, target.sessionId, null, target.daemonBootId, null)
}
