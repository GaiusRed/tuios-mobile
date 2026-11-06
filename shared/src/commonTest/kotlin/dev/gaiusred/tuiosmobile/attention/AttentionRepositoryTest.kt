package dev.gaiusred.tuiosmobile.attention

import dev.gaiusred.tuiosmobile.connection.*
import dev.gaiusred.tuiosmobile.identity.*
import dev.gaiusred.tuiosmobile.mail.MailMemoryStore
import dev.gaiusred.tuiosmobile.protocol.*
import dev.gaiusred.tuiosmobile.sessions.*
import dev.gaiusred.tuiosmobile.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class AttentionRepositoryTest {
    @Test fun stalePromptNeverReceivesOldApproval() = runTest {
        val f = Fixture(); val repo = f.repo(backgroundScope); repo.refresh(f.target)
        val shown = repo.observe(f.target).first().single(); f.summary = "Replacement request"
        assertIs<MutationOutcome.Rejected>(repo.respond(HumanResponse(shown, "once")))
        assertEquals(0, f.writes); assertEquals("Replacement request", repo.observe(f.target).first().single().summary)
    }
    @Test fun alreadyAnsweredItemRefreshes() = runTest {
        val f = Fixture(); val repo = f.repo(backgroundScope); repo.refresh(f.target)
        val shown = repo.observe(f.target).first().single(); f.applied = false
        assertIs<MutationOutcome.Rejected>(repo.respond(HumanResponse(shown, "once")))
        assertTrue(f.reads >= 3)
    }
    @Test fun riskAcknowledgmentIsExplicit() = runTest {
        val f = Fixture(); f.risky = true; val repo = f.repo(backgroundScope); repo.refresh(f.target)
        val shown = repo.observe(f.target).first().single()
        assertEquals("Deletes retained files", shown.risks.single().why)
        assertIs<MutationOutcome.Rejected>(repo.respond(HumanResponse(shown, "once")))
        assertEquals(0, f.writes)
        assertIs<MutationOutcome.Accepted>(repo.respond(HumanResponse(shown, "once", riskAck = setOf("delete"))))
        assertEquals(listOf("delete"), f.lastParams!!.getValue("risk_ack").jsonArray.map { it.jsonPrimitive.content })
    }
    @Test fun lostApprovalReceiptIsNeverRetried() = runTest {
        val f = Fixture(); f.loseReceipt = true; val repo = f.repo(backgroundScope); repo.refresh(f.target)
        val response = HumanResponse(repo.observe(f.target).first().single(), "once")
        assertEquals(MutationOutcome.Uncertain, repo.respond(response))
        val reopened = f.repo(backgroundScope)
        assertTrue(reopened.observe(f.target).first().single().uncertain)
        assertIs<MutationOutcome.Rejected>(reopened.respond(response)); assertEquals(1, f.writes)
    }
    @Test fun nonceScopeCannotCrossSessions() = runTest {
        val f = Fixture(); val repo = f.repo(backgroundScope); repo.refresh(f.target)
        val shown = repo.observe(f.target).first().single()
        assertIs<MutationOutcome.Rejected>(repo.respond(HumanResponse(shown.copy(target = f.target.copy(sessionId = "other")), "once")))
        assertIs<MutationOutcome.Accepted>(repo.respond(HumanResponse(shown, "once"))); assertEquals(1, f.writes)
    }
    @Test fun onlyDaemonOfferedActionsAreEnabled() = runTest {
        val f = Fixture(); val repo = f.repo(backgroundScope); repo.refresh(f.target)
        val shown = repo.observe(f.target).first().single()
        assertIs<MutationOutcome.Rejected>(repo.respond(HumanResponse(shown, "always"))); assertEquals(0, f.writes)
    }
    @Test fun reconnectDuringReadInvalidatesOldPresence() = runTest {
        val f = Fixture(); val repo = f.repo(backgroundScope); repo.refresh(f.target)
        val shown = repo.observe(f.target).first().single(); f.afterRead = { f.reconnect() }
        assertIs<MutationOutcome.Rejected>(repo.respond(HumanResponse(shown, "once"))); assertEquals(0, f.writes)
    }
    @Test fun staleAgentIdentityAndUnverifiedListingCannotRead() = runTest {
        val f = Fixture(); val repo = f.repo(backgroundScope)
        repo.refresh(f.target.copy(agentSessionId = "previous-agent")); assertEquals(0, f.reads)
        f.listing.value = f.listing.value.copy(verification = null)
        repo.refresh(f.target); assertEquals(0, f.reads)
    }
    @Test fun questionUsesExactOfferedAnswerAndQuestion() = runTest {
        val f = Fixture(); f.kind = "ask"; val repo = f.repo(backgroundScope); repo.refresh(f.target)
        val shown = repo.observe(f.target).first().single()
        assertIs<MutationOutcome.Accepted>(repo.respond(HumanResponse(shown, "Yes")))
        assertEquals("answer-ask", f.lastVerb); assertEquals(f.summary, f.lastParams!!["question"]!!.jsonPrimitive.content)
    }
    @Test fun reconnectWhileOpeningPresenceCannotIssueReadOnReplacementConnection() = runTest {
        val f = Fixture(); f.afterOpen = { f.reconnect() }
        f.repo(backgroundScope).refresh(f.target)
        assertEquals(0, f.presences); assertEquals(0, f.reads)
    }
    @Test fun cancelledResponseRetainsUncertaintyAndCannotReplayAfterRecreation() = runTest {
        val f = Fixture(); f.holdReceipt = true; val repo = f.repo(backgroundScope); repo.refresh(f.target)
        val response = HumanResponse(repo.observe(f.target).first().single(), "once")
        val job = launch { repo.respond(response) }; runCurrent(); assertEquals(1, f.writes)
        job.cancelAndJoin()
        val reopened = f.repo(backgroundScope)
        assertTrue(reopened.observe(f.target).first().single().uncertain)
        assertIs<MutationOutcome.Rejected>(reopened.respond(response)); assertEquals(1, f.writes)
    }
    @Test fun historyDeletionWaitsForLateResponseReceiptPersistence() = runTest {
        val f = Fixture(); f.holdReceipt = true; val repo = f.repo(backgroundScope); repo.refresh(f.target)
        val response = HumanResponse(repo.observe(f.target).first().single(), "once")
        val send = launch { repo.respond(response) }; runCurrent(); assertEquals(1, f.writes)
        val clear = launch { repo.maintainHistory { f.history.clearHost(f.host) } }; runCurrent()
        assertFalse(clear.isCompleted, "Deletion must drain the response attempt writer")
        advanceTimeBy(15_001); runCurrent(); send.join(); clear.join()
        assertEquals(HistorySnapshot(), f.history.observe(HistoryQuery(f.host)).first())
        assertEquals(1, f.writes)
    }
    @Test fun readRetryCannotReusePresenceAfterConnectionChanges() = runTest {
        val f = Fixture(); f.dropRead = true; f.afterRead = { f.reconnect() }
        f.repo(backgroundScope).refresh(f.target)
        assertEquals(1, f.reads)
    }
    @Test fun rejectedReceiptStaysRejectedWhenReconciliationReadFails() = runTest {
        val f = Fixture(); val repo = f.repo(backgroundScope); repo.refresh(f.target)
        f.applied = false; f.afterWrite = { f.dropRead = true }
        assertIs<MutationOutcome.Rejected>(repo.respond(HumanResponse(repo.observe(f.target).first().single(), "once")))
    }
    @Test fun newConnectionDuringReceiptIsUncertainEvenWhenSameDaemonReturnsSuccess() = runTest {
        val f = Fixture(); val repo = f.repo(backgroundScope); repo.refresh(f.target)
        f.afterWrite = { f.reconnect() }
        assertEquals(MutationOutcome.Uncertain, repo.respond(HumanResponse(repo.observe(f.target).first().single(), "once")))
        assertTrue(repo.observe(f.target).first().single().uncertain)
    }
    @Test fun planAndPromptAreRevalidatedInFull() = runTest {
        val f = Fixture(); f.kind = "plan"; val repo = f.repo(backgroundScope); repo.refresh(f.target)
        val shown = repo.observe(f.target).first().single(); assertEquals(f.plan, shown.plan)
        f.plan = "A revised plan"
        assertIs<MutationOutcome.Rejected>(repo.respond(HumanResponse(shown, "once"))); assertEquals(0, f.writes)
        f.kind = "question"; repo.refresh(f.target); val prompt = repo.observe(f.target).first().single()
        assertIs<MutationOutcome.Accepted>(repo.respond(HumanResponse(prompt, "choose", "2")))
        assertEquals("respond", f.lastVerb); assertEquals("screen-1", f.lastParams!!["prompt_id"]!!.jsonPrimitive.content)
    }

    private class Fixture {
        val host = HostId("fabricated"); val target = AgentTarget(host, "stable-id", "full-window", "boot", "agent-life")
        val profile = HostProfile(host, "fabricated", "user", "phone", emptyList(), "public", "alias")
        val connected = MutableStateFlow<ConnectionState>(ConnectionState.Connected(profile))
        val selected = MutableStateFlow<HostId?>(host)
        val listing = MutableStateFlow(SessionSnapshot(host, listOf(SessionSummary("stable-id", "Current name", 1, false,
            listOf(AgentSummary(target, "Builder", "needs_input", "unknown", true, false)))), false,
            verification = SessionVerification(host, (connected.value as ConnectionState.Connected).generation, "boot")))
        val sessions = object : SessionRepository {
            override val state = listing
            override suspend fun refresh() = Unit
            override fun conversation(target: AgentTarget): Flow<ConversationSnapshot> = emptyFlow()
            override suspend fun loadOlder(target: AgentTarget) = Unit
            override fun retainedConversations(hostId: HostId): Flow<List<RetainedConversation>> = emptyFlow()
            override fun retainedConversation(target: RetainedConversationTarget): Flow<RetainedConversationSnapshot> = emptyFlow()
        }
        val history = MailMemoryStore(SyncCheckpoint(host, "boot"))
        var summary = "Proceed with this operation?"; var kind = "approval"; var plan = "First inspect.\nThen update."
        var risky = false; var applied = true; var loseReceipt = false; var reads = 0; var writes = 0
        var lastParams: JsonObject? = null; var lastVerb = ""; var afterRead: (() -> Unit)? = null
        var afterOpen: (() -> Unit)? = null; var afterWrite: (() -> Unit)? = null; var presences = 0; var holdReceipt = false; var dropRead = false
        fun reconnect() { connected.value = ConnectionState.Connected(profile); listing.value = listing.value.copy(
            verification = SessionVerification(host, (connected.value as ConnectionState.Connected).generation, "boot")) }
        val connection = object : HostConnection {
            override val state = connected
            override suspend fun connect(host: HostProfile) = Unit
            override suspend fun disconnect() = Unit
            override suspend fun openChannel(kind: ChannelKind): ProxyChannel = peer().also { afterOpen?.invoke() }
        }
        fun repo(scope: CoroutineScope) = AttentionRepository(connection, history, sessions, selected, scope) { Instant.parse("2026-11-05T01:00:00Z") }
        private fun peer() = object : ProxyChannel {
            override val isOpen = MutableStateFlow(true)
            val incoming = Channel<ByteArray>(Channel.UNLIMITED); val nonce = "nonce-${Any().hashCode()}"; var attached = false
            override suspend fun send(bytes: ByteArray) {
                val req = Json.parseToJsonElement(bytes.decodeToString()).jsonObject; val p = req.getValue("params").jsonObject
                val verb = req.getValue("verb").jsonPrimitive.content
                if (verb != "answer-ask") check(p.getValue("session").jsonPrimitive.content == "Current name")
                val result = when (verb) {
                    "attach-presence" -> { presences++; attached = true; buildJsonObject { put("type", "presence"); put("session", "Current name"); put("client_id", "phone"); put("human_nonce", nonce) } }
                    "list-attention" -> { reads++; buildJsonObject {
                        put("type", "attention_list"); put("boot_id", "boot"); put("seq", 1)
                        put("items", buildJsonArray { add(buildJsonObject {
                            put("id", "item"); put("kind", kind); put("session", "Current name"); put("window", "full-window")
                            put("summary", summary); put("request_id", if (kind == "question") "" else "request"); put("since", 1); put("seq", 1)
                            put("options", buildJsonArray { if (kind == "ask") add("Yes") else { add("once"); add("deny") } })
                        }) })
                    }.also { afterRead?.invoke() } }
                    "get-approval" -> buildJsonObject {
                        put("type", "approval"); put("request_id", "request"); put("kind", kind); put("session", "Current name"); put("window", "full-window")
                        put("summary", summary); put("tool", "shell"); put("target", "test-dir"); put("plan", if (kind == "plan") plan else ""); put("plan_sha", if (kind == "plan") "sha" else "")
                        put("options", buildJsonArray { add("once"); add("deny") }); put("always_scope", buildJsonArray {})
                        put("risk", buildJsonArray { if (risky) add(buildJsonObject { put("rule", "delete"); put("why", "Deletes retained files") }) }); put("deny_message", false); put("untrusted", true)
                    }
                    "peek-prompt" -> buildJsonObject { put("type", "prompt_peek"); put("session", "Current name"); put("window", "full-window"); put("found", true); put("answerable", true); put("prompt_id", "screen-1"); put("message", summary)
                        put("actions", buildJsonArray { add("choose") }); put("options", buildJsonArray { add(buildJsonObject { put("n", 2); put("label", "Continue") }) }) }
                    "reply-approval", "answer-ask", "respond" -> {
                        check(attached && p.getValue("human_nonce").jsonPrimitive.content == nonce)
                        writes++; lastParams = p; lastVerb = verb
                        afterWrite?.invoke()
                        if (loseReceipt) { close(); return }
                        if (holdReceipt) return
                        buildJsonObject { put("type", when (verb) { "reply-approval" -> "approval_replied"; "answer-ask" -> "ask_answered"; else -> "prompt_response" }); put("applied", applied) }
                    }
                    else -> error("Unexpected operation $verb")
                }
                if (verb == "list-attention" && dropRead) { close(); return }
                incoming.send((buildJsonObject { put("id", req.getValue("id")); put("result", result) }.toString() + "\n").encodeToByteArray())
            }
            override suspend fun receive() = incoming.receiveCatching().getOrNull()
            override fun close() { isOpen.value = false; incoming.close() }
        }
    }
}
