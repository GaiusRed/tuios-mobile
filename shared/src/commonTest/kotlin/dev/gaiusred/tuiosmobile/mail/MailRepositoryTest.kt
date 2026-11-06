package dev.gaiusred.tuiosmobile.mail

import dev.gaiusred.tuiosmobile.connection.*
import dev.gaiusred.tuiosmobile.identity.*
import dev.gaiusred.tuiosmobile.protocol.*
import dev.gaiusred.tuiosmobile.sessions.*
import dev.gaiusred.tuiosmobile.storage.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Instant

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MailRepositoryTest {
    private val host = HostId("fabricated-host")
    private val target = MailTarget(host, "stable-session-id", "boot")
    private val timestamp = Instant.parse("2026-11-05T01:00:00Z")
    private val draft = MailDraft(host, target.sessionId, "full-window-id", "7", "A reply",
        replyTo = "9", daemonBootId = "boot")

    @Test fun downloadedSubjectIsIncludedInAllowlistedCache() = runTest {
        val f = Fixture(); val repo = f.repository(backgroundScope)
        repo.refresh(target)
        val content = repo.observe(target).first().messages.single().content
        val cached = Json.encodeToJsonElement(CachedContent.Mail.serializer(), content).jsonObject
        assertEquals("Release review", cached["subject"]?.jsonPrimitive?.content)
    }

    @Test fun oldCachedMailWithoutSubjectStillDecodes() {
        val old = """{"type":"mail","kind":"message","from":"pane","to":"human","text":"Saved body","threadId":"1"}"""
        val mail = Json.decodeFromString(CachedContent.serializer(), old) as CachedContent.Mail
        assertEquals("Saved body", mail.text)
        assertNull(mail.subject)
    }

    @Test fun peekDoesNotConsumeRecipientUnreadState() = runTest {
        val fixture = Fixture()
        val repo = fixture.repository(backgroundScope)
        repo.refresh(target)
        val message = repo.observe(target).first().messages.single()
        assertEquals("Original sender label", message.content.fromLabel)
        assertEquals("Original recipient label", message.content.toLabel)
        assertEquals(Instant.parse("2023-11-14T22:13:20Z"), message.sentAt)
        assertFalse(message.content.localRead)
        assertEquals(123L, message.content.remoteReadAt)
        repo.markRead(target, setOf("9"))
        repo.refresh(target)
        val refreshed = repo.observe(target).first().messages.single()
        assertTrue(refreshed.content.localRead)
        assertEquals(123L, refreshed.content.remoteReadAt)
        assertEquals(2, fixture.peer.reads)
    }

    @Test fun replyUsesExistingMessageId() = runTest {
        val fixture = Fixture()
        val outcome = fixture.repository(backgroundScope).send(draft)
        assertIs<MutationOutcome.Accepted>(outcome)
        assertEquals(9uL, fixture.peer.replyTo)
        assertEquals(1, fixture.peer.sends)
        assertEquals(MailDraftStatus.Sent, fixture.store.state.value.drafts.single().status)
    }

    @Test fun offlineDraftSurvivesRestart() = runTest {
        val fixture = Fixture()
        fixture.connectionState.value = ConnectionState.Disconnected
        fixture.repository(backgroundScope).saveDraft(draft)
        val restored = fixture.repository(backgroundScope).observe(target).first()
        assertEquals(listOf(draft), restored.drafts)
        assertEquals(0, fixture.peer.sends)
    }

    @Test fun localDraftIndexNeedsNeitherCheckpointNorLiveProfileAndKeepsLegacySeparate() = runTest {
        val f = Fixture()
        val legacy = draft.copy(daemonBootId = null, text = "Full legacy text")
        val boot = draft.copy(text = "Full boot text", status = MailDraftStatus.Uncertain)
        f.store.state.value = HistorySnapshot(drafts = listOf(legacy, boot))
        f.selected.value = null; f.connectionState.value = ConnectionState.Disconnected
        val repo = f.repository(backgroundScope)
        assertEquals(listOf(legacy, boot), repo.savedDrafts(host).first())
        assertEquals(legacy, repo.savedDraft(legacy.localTarget()).first())
        assertEquals(boot, repo.savedDraft(boot.localTarget()).first())
        assertEquals(MailDraftGeneration.Legacy, legacy.localTarget().generation)
        assertEquals(emptyList(), repo.savedDrafts(HostId("other-host")).first())
        assertNull(repo.savedDraft(legacy.localTarget().copy(sessionId = "other-session")).first())
        assertEquals(0, f.peer.reads); assertEquals(0, f.peer.sends)
    }

    @Test fun lostSendReceiptShowsUncertainWithoutRetry() = runTest {
        val fixture = Fixture()
        fixture.peer.loseReceipt = true
        val repo = fixture.repository(backgroundScope)
        assertEquals(MutationOutcome.Uncertain, repo.send(draft))
        val reopened = fixture.repository(backgroundScope)
        assertEquals(MailDraftStatus.Uncertain, reopened.observe(target).first().drafts.single().status)
        reopened.saveDraft(draft.copy(text = "ordinary autosave"))
        assertEquals(draft.text, reopened.observe(target).first().drafts.single().text)
        assertIs<MutationOutcome.Rejected>(reopened.send(draft))
        assertEquals(1, fixture.peer.sends)
    }

    @Test fun reconnectDoesNotSubmitDraft() = runTest {
        val fixture = Fixture()
        fixture.store.saveDraft(draft)
        fixture.connectionState.value = ConnectionState.Disconnected
        val repo = fixture.repository(backgroundScope)
        assertEquals(draft, repo.observe(target).first().drafts.single())
        fixture.connectionState.value = ConnectionState.Connected(fixture.peer.profile)
        repo.refresh(target)
        assertEquals(0, fixture.peer.sends)
    }

    @Test fun mailEvictionShowsHistoryGap() = runTest {
        val fixture = Fixture()
        fixture.peer.evicted = 3
        val repo = fixture.repository(backgroundScope)
        repo.refresh(target)
        val snapshot = repo.observe(target).first()
        assertTrue(snapshot.historyGap)
        assertTrue(snapshot.messages.single().missingParent)
        assertEquals("7", snapshot.messages.single().content.replyTo)
    }

    @Test fun archivedAndWrongHostTargetsCannotSend() = runTest {
        val fixture = Fixture()
        val repo = fixture.repository(backgroundScope)
        assertIs<MutationOutcome.Rejected>(repo.send(draft.copy(daemonBootId = "old-boot")))
        fixture.selected.value = HostId("another-host")
        assertIs<MutationOutcome.Rejected>(repo.send(draft))
        assertEquals(0, fixture.peer.sends)
    }

    @Test fun hostChangeWhilePersistingAttemptPreventsSend() = runTest {
        val fixture = Fixture()
        fixture.store.beforeSave = { if (it.status == MailDraftStatus.Uncertain) fixture.selected.value = HostId("other-host") }
        val outcome = fixture.repository(backgroundScope).send(draft)
        assertIs<MutationOutcome.Rejected>(outcome)
        assertEquals(0, fixture.peer.sends)
    }

    @Test fun reconnectToSameBootDuringAttemptCannotUsePreviousLease() = runTest {
        val fixture = Fixture()
        fixture.store.beforeSave = { if (it.status == MailDraftStatus.Uncertain) {
            val replacement = ConnectionState.Connected(fixture.peer.profile)
            fixture.connectionState.value = replacement
            fixture.sessionState.value = fixture.sessionState.value.copy(
                verification = SessionVerification(host, replacement.generation, "boot"))
        } }
        val outcome = fixture.repository(backgroundScope).send(draft)
        assertIs<MutationOutcome.Rejected>(outcome)
        assertEquals(0, fixture.peer.sends)
    }

    @Test fun archivedMailKeepsDownloadedSessionNameAndNeverRefreshes() = runTest {
        val fixture = Fixture()
        val repo = fixture.repository(backgroundScope)
        repo.refresh(target)
        fixture.store.saveCheckpoint(SyncCheckpoint(host, "replacement-boot"))
        fixture.sessionState.value = fixture.sessionState.value.copy(sessions = fixture.sessionState.value.sessions.map { it.copy(name = "Renamed session") })
        val snapshot = repo.observe(target).first()
        assertTrue(snapshot.readOnly)
        assertEquals("current-session-name", snapshot.sessionName)
        repo.refresh(target)
        assertEquals(1, fixture.peer.reads)
    }

    @Test fun cancelledSendRemainsUncertainAfterReopening() = runTest {
        val fixture = Fixture()
        fixture.peer.holdReceipt = true
        val repo = fixture.repository(backgroundScope)
        val job = launch { repo.send(draft) }
        runCurrent()
        assertEquals(1, fixture.peer.sends)
        job.cancel()
        runCurrent()
        val reopened = fixture.repository(backgroundScope)
        assertEquals(MailDraftStatus.Uncertain, reopened.observe(target).first().drafts.single().status)
        assertIs<MutationOutcome.Rejected>(reopened.send(draft))
        assertEquals(1, fixture.peer.sends)
    }

    @Test fun historyDeletionWaitsForLateMailReceiptPersistence() = runTest {
        val f = Fixture(); f.peer.holdReceipt = true
        val repo = f.repository(backgroundScope)
        val send = launch { repo.send(draft) }; runCurrent()
        assertEquals(1, f.peer.sends)
        val clear = launch { repo.maintainHistory { f.store.clearHost(host) } }; runCurrent()
        assertFalse(clear.isCompleted, "Deletion must drain the possible receipt writer")
        advanceTimeBy(15_001); runCurrent(); send.join(); clear.join()
        assertEquals(HistorySnapshot(), f.store.observe(HistoryQuery(host)).first())
        assertEquals(1, f.peer.sends)
    }

    @Test fun oldGenerationAttemptDoesNotBlockCurrentSendOrChangeWhenCurrentDraftIsDiscarded() = runTest {
        val fixture = Fixture()
        val old = draft.copy(daemonBootId = "old-boot", status = MailDraftStatus.Uncertain, text = "Old possible send")
        fixture.store.saveDraft(old)
        val repo = fixture.repository(backgroundScope)
        assertIs<MutationOutcome.Accepted>(repo.send(draft))
        repo.discardDraft(draft)
        assertEquals(old, fixture.store.state.value.drafts.single { it.daemonBootId == "old-boot" })
        assertEquals("", fixture.store.state.value.drafts.single { it.daemonBootId == "boot" }.text)
        assertEquals(1, fixture.peer.sends)
    }

    @Test fun explicitLegacyAdoptionPreservesTextAndClearsPreviousReplyIdentity() = runTest {
        val fixture = Fixture()
        val legacy = draft.copy(daemonBootId = null, recipient = "previous-window", text = "Recovered text")
        fixture.store.saveDraft(legacy)
        val repo = fixture.repository(backgroundScope)
        assertFalse(repo.adoptLegacyDraft(legacy, target.copy(daemonBootId = "archived-boot"), draft.recipient))
        assertEquals(legacy, fixture.store.state.value.drafts.single())
        assertTrue(repo.adoptLegacyDraft(legacy, target, draft.recipient))
        val adopted = fixture.store.state.value.drafts.single()
        assertEquals("Recovered text", adopted.text)
        assertEquals("boot", adopted.daemonBootId)
        assertEquals("full-window-id", adopted.recipient)
        assertNull(adopted.threadId)
        assertNull(adopted.replyTo)
        assertEquals(0, fixture.peer.sends)
        assertIs<MutationOutcome.Accepted>(repo.send(adopted))
        assertNull(fixture.peer.replyTo)
    }

    @Test fun legacyAdoptionCannotReplaceCurrentUncertainAttemptOrRunOffline() = runTest {
        val fixture = Fixture()
        val legacy = draft.copy(daemonBootId = null, text = "Recovered text")
        val current = draft.copy(threadId = null, replyTo = null, status = MailDraftStatus.Uncertain)
        fixture.store.saveDraft(legacy)
        fixture.store.saveDraft(current)
        val repo = fixture.repository(backgroundScope)
        assertFalse(repo.adoptLegacyDraft(legacy, target, draft.recipient))
        assertEquals(setOf(legacy, current), fixture.store.state.value.drafts.toSet())
        fixture.connectionState.value = ConnectionState.Disconnected
        assertFalse(repo.adoptLegacyDraft(legacy, target, "another-window"))
        assertEquals(setOf(legacy, current), fixture.store.state.value.drafts.toSet())
        assertEquals(0, fixture.peer.sends)
    }

    @Test fun unsupportedOrUnverifiedHumanSendIsVisibleAndNeverAccepted() = runTest {
        val unsupported = Fixture()
        unsupported.peer.rejectSend = true
        assertIs<MutationOutcome.Rejected>(unsupported.repository(backgroundScope).send(draft))
        assertEquals(MailDraftStatus.Rejected, unsupported.store.state.value.drafts.single().status)
        val unverified = Fixture()
        unverified.peer.verified = false
        assertEquals(MutationOutcome.Uncertain, unverified.repository(backgroundScope).send(draft))
        assertEquals(MailDraftStatus.Uncertain, unverified.store.state.value.drafts.single().status)
    }

    private inner class Fixture {
        val peer = MailPeer(host)
        val store = MailMemoryStore(SyncCheckpoint(host, "boot"))
        val selected = MutableStateFlow<HostId?>(host)
        val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Connected(peer.profile))
        val connection = object : HostConnection {
            override val state = connectionState
            override suspend fun connect(host: HostProfile) { state.value = ConnectionState.Connected(host) }
            override suspend fun disconnect() { state.value = ConnectionState.Disconnected }
            override suspend fun openChannel(kind: ChannelKind): ProxyChannel = peer.open()
        }
        val sessionState = MutableStateFlow(SessionSnapshot(host, listOf(SessionSummary(
                target.sessionId, "current-session-name", 1, false, listOf(AgentSummary(
                    AgentTarget(host, target.sessionId, "full-window-id", "boot", "agent-life"),
                    "Agent label", "working", "unknown", false, true)))), offline = false,
            verification = SessionVerification(host, (connectionState.value as ConnectionState.Connected).generation, "boot")))
        val sessions = object : SessionRepository {
            override val state = sessionState
            override suspend fun refresh() = Unit
            override fun conversation(target: AgentTarget): Flow<ConversationSnapshot> = emptyFlow()
            override suspend fun loadOlder(target: AgentTarget) = Unit
            override fun retainedConversations(hostId: HostId): Flow<List<RetainedConversation>> = emptyFlow()
            override fun retainedConversation(target: RetainedConversationTarget): Flow<RetainedConversationSnapshot> = emptyFlow()
        }
        fun repository(scope: kotlinx.coroutines.CoroutineScope) = MailRepository(connection, store, sessions, selected, scope) { timestamp }
    }
}

internal class MailMemoryStore(checkpoint: SyncCheckpoint) : HistoryStore {
    val state = MutableStateFlow(HistorySnapshot(checkpoint = checkpoint))
    var beforeSave: ((MailDraft) -> Unit)? = null
    override fun observe(query: HistoryQuery) = state.map { snapshot -> snapshot.copy(
        records = snapshot.records.filter { it.key.hostId == query.hostId && (query.sessionId == null || it.key.sessionId == query.sessionId) && (query.key == null || query.key == it.key) },
        drafts = snapshot.drafts.filter { it.hostId == query.hostId && (query.sessionId == null || it.sessionId == query.sessionId) },
        checkpoint = snapshot.checkpoint?.takeIf { it.hostId == query.hostId }) }
    override suspend fun apply(batch: HistoryBatch) {
        val incoming = batch.entries.map { HistoryRecord(batch.key, it) }
        state.value = state.value.copy(records = state.value.records.filter { old -> incoming.none { it.key == old.key && it.entry.id == old.entry.id && it.entry.content::class == old.entry.content::class } } + incoming)
    }
    override suspend fun saveDraft(draft: MailDraft) { beforeSave?.invoke(draft); state.value = state.value.copy(drafts = state.value.drafts.filterNot {
        it.hostId == draft.hostId && it.sessionId == draft.sessionId && it.recipient == draft.recipient && it.threadId == draft.threadId && it.generation == draft.generation
    } + draft) }
    override suspend fun saveCheckpoint(checkpoint: SyncCheckpoint) { state.value = state.value.copy(checkpoint = checkpoint) }
    override suspend fun adoptLegacyDraft(legacy: MailDraft, adopted: MailDraft): Boolean {
        if (legacy !in state.value.drafts || state.value.drafts.any { it.generation == adopted.generation && it.hostId == adopted.hostId &&
            it.sessionId == adopted.sessionId && it.recipient == adopted.recipient && it.threadId == adopted.threadId &&
            (it.text.isNotBlank() || it.status != MailDraftStatus.Draft) }) return false
        state.value = state.value.copy(drafts = state.value.drafts - legacy)
        saveDraft(adopted)
        return true
    }
    override suspend fun prune(policy: RetentionPolicy, now: Instant) = Unit
    override suspend fun clearHost(hostId: HostId) { state.value = HistorySnapshot() }
}

/** Strict fabricated peer: IDs as session selectors, consuming filters and cross-stream nonces fail. */
private class MailPeer(host: HostId) {
    val profile = HostProfile(host, "fabricated", "user", "phone", emptyList(), "public", "alias")
    var reads = 0
    var sends = 0
    var replyTo: ULong? = null
    var loseReceipt = false
    var holdReceipt = false
    var rejectSend = false
    var verified = true
    var evicted = 0
    private var channelNumber = 0
    fun open(): ProxyChannel = object : ProxyChannel {
        override val isOpen = MutableStateFlow(true)
        val incoming = Channel<ByteArray>(Channel.UNLIMITED)
        val ownNonce = "stream-${++channelNumber}"
        var attached = false
        override suspend fun send(bytes: ByteArray) {
            val request = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
            val params = request.getValue("params").jsonObject
            check(params.getValue("session").jsonPrimitive.content == "current-session-name") { "Wire requires current session NAME" }
            val verb = request.getValue("verb").jsonPrimitive.content
            val result = when (verb) {
                "attach-presence" -> { attached = true; buildJsonObject {
                    put("type", "presence"); put("session", "current-session-name"); put("client_id", "phone"); put("human_nonce", ownNonce)
                } }
                "read-agent-messages" -> {
                    check(params["peek"] == JsonPrimitive(true)); check("to" !in params && "recipient" !in params && "unread" !in params)
                    reads++
                    buildJsonObject { put("type", "agent_messages"); put("total", 1); put("evicted", evicted)
                        put("messages", buildJsonArray { add(buildJsonObject {
                            put("id", 9); put("kind", "agent"); put("session", "current-session-name"); put("from", "full-window-id"); put("to", "human")
                            put("from_label", "Original sender label"); put("to_label", "Original recipient label")
                            put("subject", "Release review")
                            put("text", "Fabricated Mail"); put("thread_id", 7); put("reply_to", 7); put("sent_at", 1700000000000000000); put("read_at", 123)
                        }) }) }
                }
                "send-agent-message" -> {
                    check(attached && params.getValue("human_nonce").jsonPrimitive.content == ownNonce)
                    check(params.getValue("to").jsonPrimitive.content == "full-window-id")
                    check(params.getValue("from").jsonPrimitive.content == "human")
                    sends++; replyTo = params["reply_to"]?.jsonPrimitive?.content?.toULong()
                    if (loseReceipt) { close(); return }
                    if (holdReceipt) return
                    buildJsonObject { put("type", "agent_message_sent"); put("message_id", 10); put("verified_human", verified) }
                }
                else -> error("Unexpected $verb")
            }
            val response = buildJsonObject { put("id", request.getValue("id"))
                if (verb == "send-agent-message" && rejectSend) put("error", buildJsonObject { put("code", "permission_denied"); put("message", "Human send unsupported") })
                else put("result", result) }
            incoming.send((response.toString() + "\n").encodeToByteArray())
        }
        override suspend fun receive() = incoming.receiveCatching().getOrNull()
        override fun close() { isOpen.value = false; incoming.close() }
    }
}


