package dev.gaiusred.tuiosmobile.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

interface DaemonClient {
    suspend fun request(request: DaemonRequest): DaemonReply
    fun subscribe(request: SubscriptionRequest): Flow<DaemonEvent>
}

/** One already-opened logical JSON stream; SSH framing belongs to the platform adapter. */
interface ProtocolChannel {
    suspend fun send(bytes: ByteArray)
    suspend fun receive(): ByteArray?
    fun close()
}

fun interface ProtocolChannelFactory {
    suspend fun open(human: Boolean): ProtocolChannel
}

class JsonDaemonClient(private val channels: ProtocolChannelFactory, private val scope: CoroutineScope,
    private val requestTimeoutMillis: Long = 15_000) : DaemonClient {
    init { require(requestTimeoutMillis > 0) }

    private val gate = kotlinx.coroutines.sync.Mutex()
    private var active: RequestSession? = null
    private var closed = false
    private var nextId = 1L
    private val subscriptionChannels = mutableSetOf<ProtocolChannel>()

    override suspend fun request(request: DaemonRequest): DaemonReply {
        val first = requestOnce(request)
        val failure = (first as? DaemonReply.Failure)?.failure
        return if (request.retryClass == RetryClass.SAFE_READ &&
            (failure is ProtocolFailure.Transport || failure == ProtocolFailure.Timeout)) requestOnce(request) else first
    }

    private suspend fun requestOnce(request: DaemonRequest): DaemonReply {
        val id = allocateId()
        val bytes = encodeRequest(id, request)
        val session = try {
            session()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return failed(request, ProtocolFailure.Transport("Unable to open request stream"), possiblyWritten = false)
        }
        val pending = Pending(request)
        gate.withLock {
            if (active === session && !closed) session.pending[id] = pending
            else pending.reply.complete(failed(request, ProtocolFailure.Transport("Request stream closed"), false))
        }
        try {
            session.writeLock.withLock {
                val shouldWrite = gate.withLock {
                    if (session.pending[id] === pending) { pending.possiblyWritten = true; true } else false
                }
                if (shouldWrite) {
                    try {
                        session.channel.send(bytes)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        retire(session, ProtocolFailure.Transport("Request stream write failed"))
                    }
                }
            }
            val response = kotlinx.coroutines.withTimeoutOrNull(requestTimeoutMillis) { pending.reply.await() }
            if (response != null) return response
            retire(session, ProtocolFailure.Timeout)
            return pending.reply.await()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                retire(session, ProtocolFailure.Transport("Request cancelled"))
            }
            throw cancelled
        }
    }

    private suspend fun allocateId(): Long = gate.withLock {
        check(nextId < Long.MAX_VALUE) { "Request IDs exhausted" }
        nextId++
    }

    private suspend fun session(): RequestSession = gate.withLock {
        check(!closed) { "Client is closed" }
        active?.let { return@withLock it }
        RequestSession(channels.open(human = true)).also { session ->
            active = session
            session.reader = scope.launch { readReplies(session) }
        }
    }

    private suspend fun readReplies(session: RequestSession) {
        try {
            while (true) {
                val bytes = session.channel.receive() ?: break
                for (message in session.codec.feed(bytes)) {
                    when (message) {
                        is WireMessage.Reply -> route(session, message.id) { request -> success(request, message.result) }
                        is WireMessage.Error -> route(session, message.id) { request -> failed(request, message.failure, false) }
                        is WireMessage.Invalid -> failPending(session, message.failure)
                        is WireMessage.Event -> Unit // Only dedicated subscription streams consume events.
                    }
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Close below with a content-free transport failure.
        } finally {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                val partial = session.codec.endOfInput().filterIsInstance<WireMessage.Invalid>().firstOrNull()?.failure
                retire(session, partial ?: ProtocolFailure.Transport("Request stream ended"))
            }
        }
    }

    private suspend fun route(session: RequestSession, id: Long, reply: (DaemonRequest) -> DaemonReply) {
        gate.withLock { session.pending.remove(id)?.let { it.reply.complete(reply(it.request)) } }
    }

    private suspend fun failPending(session: RequestSession, failure: ProtocolFailure) {
        gate.withLock {
            session.pending.values.forEach { it.reply.complete(failed(it.request, failure, it.possiblyWritten)) }
            session.pending.clear()
        }
    }

    private suspend fun retire(session: RequestSession, failure: ProtocolFailure) {
        gate.withLock {
            if (active === session) active = null
            session.pending.values.forEach { it.reply.complete(failed(it.request, failure, it.possiblyWritten)) }
            session.pending.clear()
            session.channel.close()
            session.reader?.cancel()
        }
    }

    override fun subscribe(request: SubscriptionRequest): Flow<DaemonEvent> = flow {
        val id = allocateId()
        val bytes = encodeSubscription(id, request)
        val channel = try {
            gate.withLock {
                check(!closed)
                channels.open(human = false).also { subscriptionChannels += it }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emit(DaemonEvent.Failure(ProtocolFailure.Transport("Unable to open subscription stream")))
            return@flow
        }
        val codec = ProtocolCodec()
        try {
            channel.send(bytes)
            val initial = kotlinx.coroutines.withTimeoutOrNull(requestTimeoutMillis) {
                val messages = mutableListOf<WireMessage>()
                while (messages.isEmpty()) {
                    val chunk = channel.receive() ?: return@withTimeoutOrNull null
                    messages += codec.feed(chunk)
                }
                messages
            }
            if (initial == null) {
                emit(DaemonEvent.Failure(ProtocolFailure.Transport("Subscription acknowledgment not received")))
                return@flow
            }
            val ack = initial.first()
            when {
                ack is WireMessage.Error && ack.id == id -> { emit(DaemonEvent.Failure(ack.failure)); return@flow }
                ack is WireMessage.Invalid -> { emit(DaemonEvent.Failure(ack.failure)); return@flow }
                ack is WireMessage.Reply && ack.id == id && ack.result.raw.optionalString("type") == "subscribed" -> {
                    val raw = ack.result.raw
                    emit(DaemonEvent.Subscribed(raw.ulong("seq"), raw.string("boot_id"), raw.int("replayed", 0)))
                }
                else -> { emit(DaemonEvent.Failure(ProtocolFailure.Malformed("Invalid subscription acknowledgment"))); return@flow }
            }
            suspend fun emitEvent(message: WireMessage) {
                when (message) {
                    is WireMessage.Event -> emit(message.event)
                    is WireMessage.Invalid -> emit(DaemonEvent.Failure(message.failure))
                    else -> emit(DaemonEvent.Failure(ProtocolFailure.Malformed("Unexpected subscription response")))
                }
            }
            initial.drop(1).forEach { emitEvent(it) }
            while (true) {
                val chunk = channel.receive() ?: break
                codec.feed(chunk).forEach { emitEvent(it) }
            }
            val partial = codec.endOfInput().filterIsInstance<WireMessage.Invalid>().firstOrNull()?.failure
            emit(DaemonEvent.Failure(partial ?: ProtocolFailure.Transport("Subscription stream ended")))
        } finally {
            channel.close()
            codec.endOfInput()
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                gate.withLock { subscriptionChannels -= channel }
            }
        }
    }.catch { failure ->
        if (failure is kotlinx.coroutines.CancellationException) throw failure
        emit(DaemonEvent.Failure(ProtocolFailure.Transport("Subscription stream failed")))
    }

    suspend fun close() {
        val session = gate.withLock {
            closed = true
            subscriptionChannels.forEach { it.close() }
            subscriptionChannels.clear()
            active
        }
        session?.let { retire(it, ProtocolFailure.Transport("Client closed")) }
    }

    private class RequestSession(val channel: ProtocolChannel) {
        val codec = ProtocolCodec()
        val pending = mutableMapOf<Long, Pending>()
        val writeLock = kotlinx.coroutines.sync.Mutex()
        var reader: kotlinx.coroutines.Job? = null
    }
    private class Pending(val request: DaemonRequest) {
        val reply = kotlinx.coroutines.CompletableDeferred<DaemonReply>()
        var possiblyWritten = false
    }
}

private fun failed(request: DaemonRequest, failure: ProtocolFailure, possiblyWritten: Boolean): DaemonReply =
    if (request.retryClass == RetryClass.MUTATION) DaemonReply.Mutation(
        if (possiblyWritten) MutationOutcome.Uncertain else MutationOutcome.Rejected(failure)
    ) else DaemonReply.Failure(failure)

private fun success(request: DaemonRequest, result: DaemonResult): DaemonReply {
    if (request.retryClass != RetryClass.MUTATION) return DaemonReply.Success(result)
    val receipt = (result as? DaemonResult.Receipt)?.receipt ?: return DaemonReply.Mutation(MutationOutcome.Uncertain)
    val expectedType = when (request) {
        is DaemonRequest.SendMail -> "agent_message_sent"
        is DaemonRequest.Respond -> "prompt_response"
        is DaemonRequest.ReplyApproval -> "approval_replied"
        is DaemonRequest.AnswerAsk -> "ask_answered"
        is DaemonRequest.DismissAttention -> "attention_dismissed"
        else -> error("Expected mutation")
    }
    if (receipt.type != expectedType) return DaemonReply.Mutation(MutationOutcome.Uncertain)
    val needsApplied = request is DaemonRequest.ReplyApproval || request is DaemonRequest.AnswerAsk
    val outcome = if (needsApplied && receipt.applied != true) MutationOutcome.Rejected(
        ProtocolFailure.Daemon("not_applied", "Human answer was not applied", receipt.raw)
    ) else MutationOutcome.Accepted(receipt)
    return DaemonReply.Mutation(outcome)
}
