package dev.gaiusred.tuiosmobile.sync

import dev.gaiusred.tuiosmobile.connection.*
import dev.gaiusred.tuiosmobile.identity.HostId
import dev.gaiusred.tuiosmobile.sessions.*
import dev.gaiusred.tuiosmobile.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundSync(private val connection: HostConnection, private val history: HistoryStore,
    private val scope: CoroutineScope, private val selectedHostId: StateFlow<HostId?>,
    private val clock: () -> Instant) : SessionRepository {
    private val foreground = MutableStateFlow(false)
    private val problem = MutableStateFlow<Pair<HostId?, SyncProblem?>>(null to null)
    private val verification = MutableStateFlow<SessionVerification?>(null)
    private val mailRevision = MutableStateFlow(0L)
    private var active: SessionSynchronizer? = null
    private var activeJob: Job? = null
    private val cached = selectedHostId.flatMapLatest { host ->
        if (host == null) flowOf(HistorySnapshot()) else history.observe(HistoryQuery(host))
    }

    override val state: StateFlow<SessionSnapshot> = combine(cached, selectedHostId, connection.state, foreground,
        combine(problem, verification, mailRevision) { problem, proof, revision -> Triple(problem, proof, revision) }) {
        snapshot, host, connectionState, visible, status ->
        val (failure, proof, revision) = status
        val verified = proof?.takeIf { connected(host, connectionState, visible) && it.hostId == host &&
            it.connectionGeneration === (connectionState as? ConnectionState.Connected)?.generation &&
            it.daemonBootId == snapshot.checkpoint?.bootId }
        (snapshot.takeIf { it.checkpoint?.hostId == host }?.sessions(host, verified == null,
            failure.second.takeIf { failure.first == host }) ?: SessionSnapshot(host,
                problem = failure.second.takeIf { failure.first == host })).copy(verification = verified, mailRevision = revision)
    }.stateIn(scope, SharingStarted.Eagerly, SessionSnapshot(selectedHostId.value))

    init {
        scope.launch {
            combine(connection.state, selectedHostId, foreground) { state, host, visible ->
                (state as? ConnectionState.Connected)?.takeIf { visible && it.host.id == host }
            }.distinctUntilChanged().collectLatest { connected ->
                verification.value = null
                if (connected != null) coroutineScope {
                    val host = connected.host
                    val workerJob = currentCoroutineContext().job
                    val worker = SessionSynchronizer(host.id, connection, history, this, clock,
                        isCurrent = { foreground.value && selectedHostId.value == host.id &&
                            (connection.state.value as? ConnectionState.Connected)?.generation === connected.generation },
                        report = { problem.value = host.id to it },
                        verified = { boot, verbs -> verification.value = boot?.let { SessionVerification(host.id, connected.generation, it, verbs) } },
                        invalidateMail = { mailRevision.value++ })
                    active = worker
                    activeJob = workerJob
                    try { worker.run() } finally {
                        withContext(NonCancellable) { worker.close() }
                        if (active === worker) { active = null; activeJob = null; verification.value = null }
                    }
                }
            }
        }
    }

    /** Call from the app's foreground lifecycle; false waits for streams and presence to close. */
    suspend fun setForeground(foreground: Boolean) {
        this.foreground.value = foreground
        if (!foreground) { verification.value = null; activeJob?.cancelAndJoin() }
    }

    override suspend fun refresh() { active?.refresh() }

    override fun conversation(target: AgentTarget): Flow<ConversationSnapshot> = combine(
        history.observe(HistoryQuery(target.hostId, target.sessionId)), connection.state, foreground, selectedHostId, verification,
    ) { snapshot, connectionState, visible, selected, proof ->
        snapshot.conversation(target, selected != target.hostId || !connected(target.hostId, connectionState, visible) ||
            proof?.connectionGeneration !== (connectionState as? ConnectionState.Connected)?.generation ||
            proof?.daemonBootId != target.daemonBootId)
    }.distinctUntilChanged()

    override suspend fun loadOlder(target: AgentTarget) { active?.loadOlder(target) }

    override fun retainedConversations(hostId: HostId): Flow<List<RetainedConversation>> =
        history.observe(HistoryQuery(hostId)).map { it.retainedConversations() }.distinctUntilChanged()

    override fun retainedConversation(target: RetainedConversationTarget): Flow<RetainedConversationSnapshot> =
        history.observe(HistoryQuery(target.key.hostId, key = target.key)).map { it.retainedConversation(target) }
            .distinctUntilChanged()

    private fun connected(host: HostId?, state: ConnectionState, visible: Boolean): Boolean =
        visible && host != null && (state as? ConnectionState.Connected)?.host?.id == host
}
