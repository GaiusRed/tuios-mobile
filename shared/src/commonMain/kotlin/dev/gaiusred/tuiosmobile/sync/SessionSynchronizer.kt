package dev.gaiusred.tuiosmobile.sync

import dev.gaiusred.tuiosmobile.connection.*
import dev.gaiusred.tuiosmobile.identity.*
import dev.gaiusred.tuiosmobile.protocol.*
import dev.gaiusred.tuiosmobile.sessions.*
import dev.gaiusred.tuiosmobile.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Instant

/** One foreground connection generation. Gate orders checkpoint changes and page application. */
internal class SessionSynchronizer(private val host: HostId, private val connection: HostConnection,
    private val history: HistoryStore, private val scope: CoroutineScope, private val clock: () -> Instant,
    private val isCurrent: () -> Boolean, private val report: (SyncProblem?) -> Unit,
    private val verified: (String?, Set<String>?) -> Unit, private val invalidateMail: () -> Unit) {
    private val client = JsonDaemonClient(connection.protocolChannels(), scope)
    private val gate = Mutex()
    private var checkpoint: SyncCheckpoint? = null
    private var observedBoot: String? = null
    private var verbs: Set<String> = emptySet()

    suspend fun run() {
        checkpoint = history.observe(HistoryQuery(host)).first().checkpoint
        var discoveryDelay = 1_000L
        while (current()) {
            val failure = discover()
            if (failure == null) break
            report(failure)
            if (failure != SyncProblem.DiscoveryUnavailable) awaitCancellation()
            delay(discoveryDelay)
            discoveryDelay = (discoveryDelay * 2).coerceAtMost(30_000L)
        }
        while (currentCoroutineContext().isActive && isCurrent()) {
            var unsupportedSubscription = false
            val saved = checkpoint
            val resume = saved?.eventSeq
            client.subscribe(SubscriptionRequest(afterSeq = resume, bootId = saved?.bootId?.takeIf { resume != null }))
                .transformWhile { event -> emit(event); event !is DaemonEvent.Failure }
                .takeWhile { current() }.collect { event -> gate.withLock {
                    if (!current()) return@withLock
                    when (event) {
                        is DaemonEvent.Subscribed -> {
                            observedBoot = event.bootId
                            adoptBoot(event.bootId)
                            reconcile()
                        }
                        is DaemonEvent.Record -> {
                            event.bootId?.let { adoptBoot(it) }
                            val previous = checkpoint?.eventSeq
                            if (event.seq != null && previous != null && event.seq <= previous) return@withLock
                            val gap = event.seq != null && previous != null && event.seq > previous + 1u
                            val applied = if (event.type == "transcript" && !gap) {
                                refreshTranscript(event)
                            } else reconcile()
                            if (applied && event.seq != null) persist(requireNotNull(checkpoint).copy(eventSeq = event.seq))
                        }
                        is DaemonEvent.Failure -> {
                            observedBoot = null
                            verified(null, null)
                            // Older daemons may support subscriptions without resume parameters.
                            // Drop the resume position, reconcile reads, then open a fresh stream.
                            checkpoint?.let { saved ->
                                persist(saved.copy(eventSeq = null))
                            }
                            unsupportedSubscription = (event.failure as? ProtocolFailure.Daemon)?.code == "unknown_verb"
                            report(if (unsupportedSubscription) SyncProblem.SubscriptionUnsupported else SyncProblem.SubscriptionUnavailable)
                        }
                    }
                } }
            if (unsupportedSubscription) awaitCancellation()
            if (current()) delay(1_000) // Bounded foreground retry; no background work survives this scope.
        }
    }

    private suspend fun discover(): SyncProblem? {
        val hello = result(client.request(DaemonRequest.Hello())) as? DaemonResult.Hello
            ?: return SyncProblem.DiscoveryUnavailable
        if (hello.minProtocol < 1 || hello.protocol < hello.minProtocol || 1 !in hello.minProtocol..hello.protocol)
            return SyncProblem.UnsupportedProtocol
        val available = result(client.request(DaemonRequest.ListVerbs)) as? DaemonResult.Verbs
            ?: return SyncProblem.DiscoveryUnavailable
        if (!current()) return SyncProblem.DiscoveryUnavailable
        verbs = available.names
        if ("subscribe" !in verbs) return SyncProblem.SubscriptionUnsupported
        if (!verbs.containsAll(listOf("list-sessions", "list-agents"))) return SyncProblem.ListingUnsupported
        return null
    }

    suspend fun refresh() = gate.withLock { if (current() && observedBoot != null) reconcile() }

    suspend fun loadOlder(target: AgentTarget) = gate.withLock {
        if (!current() || target.hostId != host || target.daemonBootId != checkpoint?.bootId) return@withLock
        val agent = checkpoint?.agents?.find { it.sessionId == target.sessionId && it.windowId == target.windowId &&
            it.agentSessionId == target.agentSessionId } ?: return@withLock
        if (!agent.transcript?.older.isNullOrEmpty()) readTranscript(agent, older = true)
    }

    private suspend fun adoptBoot(boot: String) {
        if (checkpoint?.bootId == boot) return
        verified(null, null)
        observedBoot = boot
        persist(SyncCheckpoint(host, boot, nextGeneration = checkpoint?.nextGeneration ?: 0))
    }

    private suspend fun reconcile(): Boolean {
        verified(null, null)
        if (observedBoot == null) return false
        val sessions = result(client.request(DaemonRequest.ListSessions)) as? DaemonResult.Sessions
            ?: return listingFailed()
        val agents = mutableListOf<CachedAgent>()
        val previous = requireNotNull(checkpoint)
        for (session in sessions.sessions) {
            val listed = result(client.request(DaemonRequest.ListAgents(session.name))) as? DaemonResult.Agents
                ?: return listingFailed()
            for (agent in listed.agents) {
                val old = previous.agents.find { it.sessionId == session.id && it.windowId == agent.windowId &&
                    it.agentSessionId == agent.agentSessionId && it.harnessId == agent.harnessId }
                agents += CachedAgent(session.id, agent.windowId, agent.name, agent.state, agent.harnessId,
                    agent.agentSessionId, agent.needsYou, agent.ready, old?.transcript,
                    old?.availability ?: TranscriptAvailability.NotLoaded)
            }
        }
        if (!current()) return false
        val storedSessions = sessions.sessions.map { CachedSession(it.id, it.name, it.windowCount, it.attached) }
        persist(previous.copy(sessions = storedSessions, agents = agents))
        if (!current()) return false
        verified(previous.bootId, verbs)
        invalidateMail()
        report(null)
        var complete = true
        for (agent in agents) if (!readTranscript(agent)) complete = false
        return complete
    }

    private fun listingFailed(): Boolean { verified(null, null); report(SyncProblem.ListingUnavailable); return false }

    private suspend fun refreshTranscript(event: DaemonEvent.Record): Boolean {
        val saved = requireNotNull(checkpoint)
        val session = saved.sessions.find { it.name == event.session || it.id == event.session }
        val agent = saved.agents.find { it.sessionId == session?.id && it.windowId == event.window }
        return if (agent == null) reconcile() else readTranscript(agent)
    }

    private suspend fun readTranscript(initialAgent: CachedAgent, older: Boolean = false): Boolean {
        if (!verbs.containsAll(listOf("agent-transcript", "attach-presence")))
            return unavailable(initialAgent, TranscriptAvailability.Unavailable)
        var agent = initialAgent
        do {
            if (!current()) return false
            val session = requireNotNull(checkpoint).sessions.find { it.id == agent.sessionId } ?: return false
            val lease = try {
                connection.acquirePresence(session.name, scope, isCurrent)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { return unavailable(agent, TranscriptAvailability.Failed) }
            val position = agent.transcript
            val response = try {
                lease.client.request(DaemonRequest.Transcript(session.name, agent.windowId, lease.nonce,
                    after = position?.forward?.takeUnless { older }, before = position?.older?.takeIf { older }))
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                return unavailable(agent, TranscriptAvailability.Failed)
            } finally { lease.close() }
            if (!current()) return false
            val page = result(response) as? DaemonResult.Transcript
            if (page == null) {
                val code = ((response as? DaemonReply.Failure)?.failure as? ProtocolFailure.Daemon)?.code
                return unavailable(agent, if (code in setOf("unknown_verb", "unsupported_harness", "no_transcript"))
                    TranscriptAvailability.Unavailable else TranscriptAvailability.Failed)
            }
            if (page.window != agent.windowId || (page.session != session.name && page.session != session.id)) {
                return unavailable(agent, TranscriptAvailability.Failed)
            }
            if (!older && page.more && (page.cursor.isEmpty() || page.cursor == position?.forward)) {
                return unavailable(agent, TranscriptAvailability.Failed)
            }
            val saved = requireNotNull(checkpoint)
            val replace = page.reset || position == null
            val generation = if (replace) "generation-${saved.nextGeneration + 1}" else position.generation
            val updatedPosition = TranscriptCheckpoint(generation,
                if (older && !replace) position.forward else page.cursor.takeIf { it.isNotEmpty() },
                if (older || replace) page.older.takeIf { it.isNotEmpty() } else position.older,
                if (older && !replace) position.more else page.more)
            val updated = agent.copy(transcript = updatedPosition, availability = TranscriptAvailability.Available)
            val next = stamped(saved.copy(agents = saved.agents.map { if (it.samePane(agent)) updated else it },
                nextGeneration = saved.nextGeneration + if (replace) 1 else 0))
            val metadata = CachedEntry("generation-identity", CachedContent.TranscriptGeneration(session.name,
                agent.name, agent.agentSessionId, agent.harnessId), clock())
            history.apply(HistoryBatch(RecordKey(host, agent.sessionId, agent.windowId, saved.bootId, generation),
                listOf(metadata) + page.entries.map { CachedEntry(it.id, it.cached(), clock(), it.at?.let(Instant::fromEpochMilliseconds)) },
                placement = if (older && !replace) BatchPlacement.Prepend else BatchPlacement.Append, checkpoint = next))
            checkpoint = next
            agent = updated
        } while (!older && agent.transcript?.more == true)
        return true
    }

    private suspend fun unavailable(agent: CachedAgent, status: TranscriptAvailability): Boolean {
        if (!current()) return false
        val saved = requireNotNull(checkpoint)
        persist(saved.copy(agents = saved.agents.map { if (it.samePane(agent)) it.copy(availability = status) else it }))
        return status == TranscriptAvailability.Unavailable
    }

    private fun CachedAgent.samePane(other: CachedAgent) = sessionId == other.sessionId && windowId == other.windowId
    private fun result(reply: DaemonReply): DaemonResult? = (reply as? DaemonReply.Success)?.result
    private suspend fun current(): Boolean { currentCoroutineContext().ensureActive(); return isCurrent() }
    private fun stamped(value: SyncCheckpoint): SyncCheckpoint = clock().let { value.copy(receivedSeconds = it.epochSeconds, receivedNanos = it.nanosecondsOfSecond) }
    private suspend fun persist(value: SyncCheckpoint) {
        if (!current()) return
        val next = stamped(value)
        history.saveCheckpoint(next)
        checkpoint = next
    }

    suspend fun close() {
        client.close()
    }
}
