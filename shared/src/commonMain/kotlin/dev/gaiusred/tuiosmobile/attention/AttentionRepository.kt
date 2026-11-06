package dev.gaiusred.tuiosmobile.attention

import dev.gaiusred.tuiosmobile.connection.*
import dev.gaiusred.tuiosmobile.identity.HostId
import dev.gaiusred.tuiosmobile.identity.RecordKey
import dev.gaiusred.tuiosmobile.protocol.*
import dev.gaiusred.tuiosmobile.sessions.*
import dev.gaiusred.tuiosmobile.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import kotlin.time.Instant

data class AttentionItem(val target: AgentTarget, val id: String, val kind: String, val sessionName: String,
    val agentName: String, val summary: String, val actions: List<String>, val requestId: String? = null,
    val promptId: String? = null, val promptOptions: List<PromptOption> = emptyList(),
    val risks: List<ApprovalRisk> = emptyList(), val plan: String = "", val planSha: String = "",
    val alwaysScope: List<String> = emptyList(), val tool: String = "", val resource: String = "", val denyMessage: Boolean = false,
    val enabled: Boolean = false, val uncertain: Boolean = false, val answered: Boolean = false)
data class HumanResponse(val item: AttentionItem, val action: String, val value: String? = null,
    val riskAck: Set<String> = emptySet())

class AttentionRepository(private val connection: HostConnection, private val history: HistoryStore,
    private val sessions: SessionRepository, private val selectedHost: StateFlow<HostId?>,
    private val scope: CoroutineScope, private val now: () -> Instant) {
    private val gate = Mutex()

    /** Called only with the app connection and sync paused; drains response receipts before deletion. */
    suspend fun maintainHistory(block: suspend () -> Unit) = gate.withLock { block() }
    private val items = MutableStateFlow<Map<AgentTarget, List<AttentionItem>>>(emptyMap())
    private val failures = MutableStateFlow<Map<AgentTarget, String>>(emptyMap())

    fun problem(target: AgentTarget): Flow<String?> = failures.map { it[target] }.distinctUntilChanged()

    fun observe(target: AgentTarget): Flow<List<AttentionItem>> = combine(items, sessions.state, connection.state,
        selectedHost, history.observe(HistoryQuery(target.hostId, target.sessionId))) { loaded, _, _, _, cached ->
        val attempts = cached.records.filter { it.key == key(target) }.mapNotNull {
            (it.entry.content as? CachedContent.AttentionAttempt)?.takeIf { a -> a.agentSessionId == target.agentSessionId }
        }
        val live = loaded[target].orEmpty()
        val pending = attempts.filter { it.status == "uncertain" && live.none { row -> row.id == it.itemId && identity(row) == (it.requestId ?: it.promptId ?: it.itemId) } }
            .map { AttentionItem(target, it.itemId, it.kind, it.sessionName, it.agentName, it.summary,
                emptyList(), it.requestId, it.promptId, uncertain = true) }
        live.map { row ->
            val attempt = attempts.find { it.itemId == row.id && (it.requestId ?: it.promptId ?: it.itemId) == identity(row) }
            row.copy(enabled = current(target) != null && attempt?.status !in setOf("uncertain", "accepted"),
                uncertain = attempt?.status == "uncertain", answered = attempt?.status == "accepted")
        } + pending
    }.distinctUntilChanged()

    suspend fun refresh(target: AgentTarget) = gate.withLock { refreshLocked(target) }

    private suspend fun refreshLocked(target: AgentTarget) {
        val generation = (connection.state.value as? ConnectionState.Connected)?.generation ?: return
        val session = current(target, generation) ?: return
        var lease: PresenceLease? = null
        try {
            lease = connection.acquirePresence(session.name, scope) { current(target, generation)?.name == session.name }
            if (current(target, generation)?.name != session.name) return
            replace(target, read(target, generation, session.name, lease.client))
            failures.value -= target
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failures.value += target to "Current questions are unavailable. Refresh when connected." }
        finally { lease?.close() }
    }

    suspend fun respond(response: HumanResponse): MutationOutcome = gate.withLock {
        val shown = response.item
        val target = shown.target
        val generation = (connection.state.value as? ConnectionState.Connected)?.generation
            ?: return@withLock rejected("Reconnect before answering")
        val session = current(target, generation) ?: return@withLock rejected("This agent is no longer current")
        if (hasAttempt(shown)) return@withLock rejected("This response already has an attempt. Check the current state.")
        if (current(target, generation)?.name != session.name) return@withLock rejected("The connection changed")
        var lease: PresenceLease? = null
        var attempted = false
        try {
            lease = connection.acquirePresence(session.name, scope) { current(target, generation)?.name == session.name }
            if (current(target, generation)?.name != session.name) return@withLock rejected("The connection changed")
            val refreshed = read(target, generation, session.name, lease.client)
            replace(target, refreshed)
            val latest = refreshed.find { it.id == shown.id }
            if (latest == null || review(latest) != review(shown)) return@withLock rejected("The request changed. Review the current request.")
            val request = dispatch(response, lease.nonce) ?: return@withLock rejected("Choose an offered action and acknowledge each risk")
            if (current(target, generation)?.name != session.name) return@withLock rejected("The connection changed")
            saveAttempt(response, "uncertain")
            if (current(target, generation)?.name != session.name) {
                saveAttempt(response, "rejected")
                return@withLock rejected("The connection changed")
            }
            attempted = true
            val reply = lease.client.request(request)
            // An answer can have arrived even when a reconnect replaced this connection during its receipt.
            val currentConnection = current(target, generation)?.name == session.name
            val outcome = if (currentConnection) (reply as? DaemonReply.Mutation)?.outcome ?: MutationOutcome.Uncertain
                else MutationOutcome.Uncertain
            withContext(NonCancellable) { saveAttempt(response, when (outcome) {
                is MutationOutcome.Accepted -> "accepted"
                is MutationOutcome.Rejected -> "rejected"
                MutationOutcome.Uncertain -> "uncertain"
            }) }
            if (outcome is MutationOutcome.Rejected && currentConnection) {
                try { replace(target, read(target, generation, session.name, lease.client)) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { failures.value += target to "The response was rejected. Current questions could not be refreshed." }
            }
            outcome
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { if (attempted) MutationOutcome.Uncertain else rejected("Refresh the current request before answering") }
        finally { lease?.close() }
    }

    private suspend fun read(target: AgentTarget, generation: ConnectionGeneration, name: String, client: DaemonClient): List<AttentionItem> {
        suspend fun request(request: DaemonRequest): DaemonResult {
            check(current(target, generation)?.name == name)
            val reply = client.request(request)
            check(current(target, generation)?.name == name)
            return (reply as? DaemonReply.Success)?.result ?: error("Attention read unavailable")
        }
        val listing = request(DaemonRequest.ListAttention(name)) as? DaemonResult.Attention ?: error("Invalid attention listing")
        check(listing.bootId == target.daemonBootId)
        val agent = current(target, generation)!!.agents.single { it.target == target }
        return listing.items.filter { it.session == name && it.window == target.windowId && it.host.isNullOrBlank() &&
            it.raw["closed"]?.jsonPrimitive?.content.orEmpty().isEmpty() && it.raw["stale"] != JsonPrimitive(true) &&
            it.kind in setOf("approval", "plan", "ask", "question") }.mapNotNull { wire ->
            val base = AttentionItem(target, wire.id, wire.kind, name, agent.name, wire.summary, wire.options,
                wire.requestId?.takeIf { it.isNotBlank() })
            when {
                wire.kind == "ask" -> base.takeIf { it.requestId != null }
                base.requestId != null && wire.kind in setOf("approval", "plan") -> {
                    val detail = request(DaemonRequest.GetApproval(name, base.requestId)) as? DaemonResult.Approval ?: error("Approval detail unavailable")
                    check(detail.session == name && detail.window == target.windowId && detail.requestId == base.requestId && detail.kind == wire.kind)
                    base.copy(summary = detail.summary, actions = detail.options, risks = detail.risks, plan = detail.plan,
                        planSha = detail.planSha, alwaysScope = detail.alwaysScope, tool = detail.tool, resource = detail.target,
                        denyMessage = detail.denyMessage)
                }
                else -> {
                    val prompt = request(DaemonRequest.PeekPrompt(name, target.windowId)) as? DaemonResult.Prompt ?: error("Prompt unavailable")
                    check(prompt.session == name && prompt.window == target.windowId)
                    if (!prompt.found || !prompt.answerable || prompt.promptId.isNullOrBlank()) null else base.copy(
                        summary = prompt.raw["message"]?.jsonPrimitive?.content.orEmpty(), promptId = prompt.promptId,
                        actions = prompt.actions, promptOptions = prompt.options,
                        plan = prompt.raw["lines"]?.jsonArray?.joinToString("\n") { it.jsonPrimitive.content }.orEmpty(),
                        risks = wire.risk.map { ApprovalRisk(it, "") })
                }
            }
        }
    }

    private fun dispatch(response: HumanResponse, nonce: String): DaemonRequest? {
        val item = response.item
        if (response.action !in item.actions) return null
        if (response.action != "deny" && response.riskAck != item.risks.map { it.rule }.toSet()) return null
        return when {
            item.kind == "ask" && item.requestId != null -> DaemonRequest.AnswerAsk(item.requestId, response.action, item.summary, nonce)
            item.requestId != null && item.kind in setOf("approval", "plan") -> {
                if (item.kind == "plan" && (item.plan.isBlank() || item.planSha.isBlank())) return null
                DaemonRequest.ReplyApproval(item.requestId, item.sessionName, item.target.windowId, response.action,
                    item.summary, nonce, response.riskAck.toList(), item.planSha.takeIf { it.isNotBlank() },
                    response.value?.takeIf { item.denyMessage && response.action == "deny" })
            }
            item.promptId != null -> {
                if (response.action !in setOf("approve", "approve_always", "deny", "choose", "text")) return null
                if (response.action == "choose" && item.promptOptions.none { it.n.toString() == response.value }) return null
                if (response.action == "text" && response.value.isNullOrBlank()) return null
                DaemonRequest.Respond(item.sessionName, item.target.windowId, response.action, item.promptId, nonce,
                    response.value, response.riskAck.toList())
            }
            else -> null
        }
    }

    private fun current(target: AgentTarget, generation: ConnectionGeneration? = null): SessionSummary? {
        val connected = connection.state.value as? ConnectionState.Connected ?: return null
        val listing = sessions.state.value
        val proof = listing.verification ?: return null
        if (selectedHost.value != target.hostId || connected.host.id != target.hostId || listing.hostId != target.hostId ||
            proof.hostId != target.hostId || listing.offline || proof.daemonBootId != target.daemonBootId ||
            proof.connectionGeneration !== connected.generation || (generation != null && connected.generation !== generation)) return null
        return listing.sessions.find { it.id == target.sessionId && it.agents.any { agent -> agent.target == target } }
    }
    private suspend fun hasAttempt(item: AttentionItem) = history.observe(HistoryQuery(item.target.hostId, item.target.sessionId)).first()
        .records.any { it.key == key(item.target) && it.entry.id == attemptId(item) &&
            (it.entry.content as? CachedContent.AttentionAttempt)?.status in setOf("accepted", "uncertain") }
    private suspend fun saveAttempt(response: HumanResponse, status: String) {
        val item = response.item
        history.apply(HistoryBatch(key(item.target), listOf(CachedEntry(attemptId(item), CachedContent.AttentionAttempt(
            item.target.agentSessionId, item.id, item.kind, item.sessionName, item.agentName, item.summary,
            item.requestId, item.promptId, response.action, status), now()))))
    }
    private fun key(target: AgentTarget) = RecordKey(target.hostId, target.sessionId, target.windowId, target.daemonBootId, null)
    private fun identity(item: AttentionItem) = item.requestId ?: item.promptId ?: item.id
    private fun attemptId(item: AttentionItem) = JsonArray(listOf(item.target.agentSessionId, item.id, identity(item)).map(::JsonPrimitive)).toString()
    private fun review(item: AttentionItem) = item.copy(enabled = false, uncertain = false, answered = false)
    private fun replace(target: AgentTarget, value: List<AttentionItem>) { items.value += target to value }
    private fun rejected(message: String) = MutationOutcome.Rejected(ProtocolFailure.Daemon("not_applied", message))
}
