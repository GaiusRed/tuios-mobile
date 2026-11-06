package dev.gaiusred.tuiosmobile.connection

import dev.gaiusred.tuiosmobile.protocol.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext

enum class ChannelKind { HUMAN, SUBSCRIPTION }
interface ProxyChannel : ProtocolChannel { val isOpen: StateFlow<Boolean> }
interface ProxyLink { val isOpen: StateFlow<Boolean>; suspend fun open(human: Boolean): ProxyChannel; fun close() }
fun interface ProxyTransport { suspend fun connect(host: HostProfile): ProxyLink }

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data class Connected(val host: HostProfile, val generation: ConnectionGeneration = ConnectionGeneration()) : ConnectionState
    data object HostKeyReviewRequired : ConnectionState
    data object RepairRequired : ConnectionState
    data object AuthenticationFailed : ConnectionState
    data object PermissionDenied : ConnectionState
    data object Failed : ConnectionState
}

/** Process-local identity. A reconnect always creates a different connection generation. */
class ConnectionGeneration

class ConnectionException(val reason: ConnectionState) : Exception("Connection unavailable")

interface HostConnection {
    val state: StateFlow<ConnectionState>
    suspend fun connect(host: HostProfile)
    suspend fun openChannel(kind: ChannelKind): ProxyChannel
    suspend fun disconnect()
}

class ForegroundHostConnection(private val transport: ProxyTransport, private val scope: CoroutineScope) : HostConnection {
    private val mutableState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val state = mutableState.asStateFlow()
    private val gate = Mutex()
    private var foreground = true
    private var generation = 0L
    private var connecting: Job? = null
    private var link: ProxyLink? = null
    private var linkWatcher: Job? = null

    override suspend fun connect(host: HostProfile) {
        val job = currentCoroutineContext().job
        val attempt = gate.withLock {
            check(foreground) { "Connection requires foreground" }
            ++generation
            releaseCurrent(job)
            connecting = job
            mutableState.value = ConnectionState.Connecting
            generation
        }
        var opened: ProxyLink? = null
        try {
            opened = withTimeout(20_000) { transport.connect(host) }
            gate.withLock {
                check(generation == attempt && foreground)
                link = opened
                mutableState.value = ConnectionState.Connected(host)
                val active = opened
                linkWatcher = scope.launch {
                    active.isOpen.first { !it }
                    gate.withLock {
                        if (link === active) { link = null; mutableState.value = ConnectionState.Disconnected }
                    }
                }
            }
        } catch (failure: Exception) {
            opened?.close()
            withContext(NonCancellable) {
                gate.withLock {
                    if (generation == attempt) mutableState.value = when (failure) {
                        is kotlinx.coroutines.TimeoutCancellationException -> ConnectionState.Failed
                        is CancellationException -> ConnectionState.Disconnected
                        is ConnectionException -> failure.reason
                        else -> ConnectionState.Failed
                    }
                }
            }
            if (failure is CancellationException) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (failure !is kotlinx.coroutines.TimeoutCancellationException) throw failure
            }
        } finally {
            withContext(NonCancellable) {
                gate.withLock {
                    if (generation == attempt && connecting === job) connecting = null
                }
            }
        }
    }

    override suspend fun openChannel(kind: ChannelKind): ProxyChannel {
        val active = gate.withLock { check(foreground); checkNotNull(link) { "Host disconnected" } }
        var opened: ProxyChannel? = null
        try {
            val channel = withTimeout(10_000) {
                active.open(kind == ChannelKind.HUMAN).also { opened = it }
            }
            gate.withLock { check(link === active && foreground) { "Host disconnected" } }
            return channel
        } catch (failure: Exception) {
            opened?.close()
            throw failure
        }
    }

    override suspend fun disconnect() {
        val caller = currentCoroutineContext().job
        gate.withLock {
            ++generation
            releaseCurrent(caller)
            mutableState.value = ConnectionState.Disconnected
        }
    }

    suspend fun setForeground(foreground: Boolean) {
        val caller = currentCoroutineContext().job
        gate.withLock {
            this.foreground = foreground
            if (!foreground) {
                ++generation
                releaseCurrent(caller)
                mutableState.value = ConnectionState.Disconnected
            }
        }
    }

    /** All callers hold gate, including replacement and foreground state transitions. */
    private fun releaseCurrent(caller: Job) {
        val pending = connecting
        val previousLink = link
        connecting = null
        link = null
        linkWatcher?.cancel()
        linkWatcher = null
        if (pending !== caller) pending?.cancel()
        previousLink?.close()
    }
}

class PresenceLease(private val channel: ProxyChannel, val client: DaemonClient, private var liveNonce: String) {
    val nonce: String get() {
        if (!channel.isOpen.value) liveNonce = ""
        check(liveNonce.isNotEmpty())
        return liveNonce
    }
    internal fun invalidate() { liveNonce = "" }
    fun close() { liveNonce = ""; channel.close() }
}

suspend fun HostConnection.acquirePresence(session: String, scope: CoroutineScope, isCurrent: () -> Boolean = { true }): PresenceLease {
    require(session.isNotBlank()) { "Presence requires explicit session" }
    check(isCurrent())
    val channel = openChannel(ChannelKind.HUMAN)
    val client = JsonDaemonClient(ProtocolChannelFactory { check(isCurrent()); channel }, scope)
    try {
        check(isCurrent())
        val reply = client.request(DaemonRequest.AttachPresence(session))
        check(isCurrent())
        val presence = ((reply as? DaemonReply.Success)?.result as? DaemonResult.Presence)
            ?: throw ConnectionException(ConnectionState.PermissionDenied)
        check(presence.session == session && presence.humanNonce.isNotBlank())
        return PresenceLease(channel, client, presence.humanNonce).also { lease ->
            scope.launch { channel.isOpen.first { !it }; lease.invalidate() }
        }
    } catch (failure: Exception) {
        channel.close()
        throw failure
    }
}

fun HostConnection.protocolChannels(): ProtocolChannelFactory = ProtocolChannelFactory { human ->
    openChannel(if (human) ChannelKind.HUMAN else ChannelKind.SUBSCRIPTION)
}
