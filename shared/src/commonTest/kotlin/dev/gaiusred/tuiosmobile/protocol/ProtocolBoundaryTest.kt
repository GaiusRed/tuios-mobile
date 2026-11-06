package dev.gaiusred.tuiosmobile.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ProtocolBoundaryTest {
    @Test
    fun malformedLineRecoversWithoutLeakingContent() {
        val frames = ProtocolCodec().feed("private-invalid\n{\"id\":2,\"result\":{\"type\":\"future\"}}\n".encodeToByteArray())
        val failure = assertIs<ProtocolFailure.Malformed>(assertIs<WireMessage.Invalid>(frames[0]).failure)
        assertTrue("private-invalid" !in failure.toString())
        assertEquals(2, assertIs<WireMessage.Reply>(frames[1]).id)
    }

    @Test
    fun oversizedLineIsDiscardedUntilLfAndThenRecovers() {
        val codec = ProtocolCodec(maxLineBytes = 64)
        val first = codec.feed("x".repeat(80).encodeToByteArray())
        assertIs<ProtocolFailure.Oversized>(assertIs<WireMessage.Invalid>(first.single()).failure)
        val next = codec.feed("xxxx\n{\"id\":2,\"result\":{\"type\":\"future\"}}\n".encodeToByteArray())
        assertEquals(2, assertIs<WireMessage.Reply>(next.single()).id)
    }

    @Test
    fun incompleteLineAtEofIsTransportFailureAndBufferIsCleared() {
        val codec = ProtocolCodec()
        codec.feed("{\"id\":1".encodeToByteArray())
        assertIs<ProtocolFailure.Transport>(assertIs<WireMessage.Invalid>(codec.endOfInput().single()).failure)
        assertEquals(emptyList(), codec.endOfInput())
    }

    @Test
    fun invalidUtf8DoesNotSilentlyChangeTranscriptText() {
        val bytes = "{\"id\":1,\"result\":{\"type\":\"future\",\"text\":\"".encodeToByteArray() +
            byteArrayOf(0xc3.toByte(), 0x28) + "\"}}\n".encodeToByteArray()
        assertIs<ProtocolFailure.Malformed>(assertIs<WireMessage.Invalid>(ProtocolCodec().feed(bytes).single()).failure)
    }

    @Test
    fun mailReadIsPassiveAndUnsignedThreadIdIsExact() {
        val json = Json.parseToJsonElement(ProtocolCodec().encode(3, DaemonRequest.ReadMail("demo", ULong.MAX_VALUE)).decodeToString()).jsonObject
        assertEquals("read-agent-messages", json["verb"]?.jsonPrimitive?.content)
        val params = json.getValue("params").jsonObject
        assertEquals("18446744073709551615", params["thread"].toString())
        assertEquals(true, params["peek"]?.jsonPrimitive?.boolean)
        assertTrue("to" !in params && "unread" !in params)
    }

    @Test
    fun transcriptCannotSendBothCursorDirections() {
        assertFailsWith<IllegalArgumentException> {
            ProtocolCodec().encode(1, DaemonRequest.Transcript("demo", "p1", "fake", after = "a", before = "b"))
        }
    }

    @Test
    fun mailSendUsesHumanProofAndCorrectReplyVerb() {
        val params = Json.parseToJsonElement(ProtocolCodec().encode(4,
            DaemonRequest.SendMail("demo", "p1", "fake", "line1\nline2", 42u)).decodeToString()).jsonObject
        assertEquals("send-agent-message", params["verb"]?.jsonPrimitive?.content)
        val body = params.getValue("params").jsonObject
        assertEquals("human", body["from"]?.jsonPrimitive?.content)
        assertEquals("fake", body["human_nonce"]?.jsonPrimitive?.content)
        assertEquals("42", body["reply_to"].toString())
        assertEquals("line1\nline2", body["text"]?.jsonPrimitive?.content)
    }

    @Test
    fun concurrentRepliesRouteByIdRatherThanArrivalOrder() = runTest {
        val channel = ManualChannel()
        val client = JsonDaemonClient(ProtocolChannelFactory { channel }, backgroundScope)
        val first = async { client.request(DaemonRequest.ListSessions) }
        val second = async { client.request(DaemonRequest.ListVerbs) }
        runCurrent()
        val firstWrite = channel.sent.tryReceive()
        val secondWrite = channel.sent.tryReceive()
        assertTrue(firstWrite.isSuccess && secondWrite.isSuccess, "Both requests must be written")
        val a = firstWrite.getOrThrow().id()
        val b = secondWrite.getOrThrow().id()
        assertTrue(a != b)
        channel.incoming.send("""{"id":$b,"result":{"type":"second"}}
{"id":$a,"result":{"type":"first"}}
""".encodeToByteArray())
        assertEquals("first", assertIs<DaemonResult.Unknown>(assertIs<DaemonReply.Success>(first.await()).result).type)
        assertEquals("second", assertIs<DaemonResult.Unknown>(assertIs<DaemonReply.Success>(second.await()).result).type)
        client.close()
    }

    @Test
    fun transportFailureRetriesReadOnceOnNewStream() = runTest {
        var opened = 0
        val failed = ScriptedChannel { null }
        val recovered = ScriptedChannel { """{"id":${it.id()},"result":{"type":"future"}}""" }
        val client = JsonDaemonClient(ProtocolChannelFactory { if (opened++ == 0) failed else recovered }, backgroundScope)
        assertIs<DaemonReply.Success>(client.request(DaemonRequest.ListSessions))
        assertEquals(2, opened)
        assertTrue(failed.closed)
        client.close()
    }

    @Test
    fun timeoutAfterMutationWriteIsUncertainWithoutRetry() = runTest {
        var opened = 0
        val channel = ManualChannel()
        val client = JsonDaemonClient(ProtocolChannelFactory { opened++; channel }, backgroundScope, requestTimeoutMillis = 50)
        val reply = client.request(DaemonRequest.SendMail("demo", "p1", "fake", "Hello"))
        assertIs<MutationOutcome.Uncertain>(assertIs<DaemonReply.Mutation>(reply).outcome)
        assertEquals(1, opened)
        assertTrue(channel.closed)
        client.close()
    }

    @Test
    fun failureDuringSendIsPossiblyWrittenAndNeverRetried() = runTest {
        var sends = 0
        val channel = object : ProtocolChannel {
            override suspend fun send(bytes: ByteArray) { sends++; error("socket failed after partial write") }
            override suspend fun receive(): ByteArray? = CompletableDeferred<ByteArray?>().await()
            override fun close() = Unit
        }
        val client = JsonDaemonClient(ProtocolChannelFactory { channel }, backgroundScope)
        val reply = client.request(DaemonRequest.SendMail("demo", "p1", "fake", "Hello"))
        assertIs<MutationOutcome.Uncertain>(assertIs<DaemonReply.Mutation>(reply).outcome)
        assertEquals(1, sends)
        client.close()
    }

    @Test
    fun structuredMutationFailureIsRejectedWithOriginalCode() = runTest {
        val channel = ScriptedChannel { """{"id":${it.id()},"error":{"code":"prompt_changed","message":"Refresh"}}""" }
        val client = JsonDaemonClient(ProtocolChannelFactory { channel }, backgroundScope)
        val reply = client.request(DaemonRequest.Respond("demo", "p1", "choose", "prompt", "fake", "2"))
        val failure = assertIs<MutationOutcome.Rejected>(assertIs<DaemonReply.Mutation>(reply).outcome).failure
        assertEquals("prompt_changed", assertIs<ProtocolFailure.Daemon>(failure).code)
        client.close()
    }

    @Test
    fun notAppliedHumanAnswerIsRejected() = runTest {
        val channel = ScriptedChannel { """{"id":${it.id()},"result":{"type":"ask_answered","request_id":"q","answer":"yes","applied":false,"reason":"already_answered"}}""" }
        val client = JsonDaemonClient(ProtocolChannelFactory { channel }, backgroundScope)
        val reply = client.request(DaemonRequest.AnswerAsk("q", "yes", "Question?", "fake"))
        assertIs<MutationOutcome.Rejected>(assertIs<DaemonReply.Mutation>(reply).outcome)
        client.close()
    }

    @Test
    fun subscriptionUsesSeparateStreamRetainsReplayAndClosesOnCancellation() = runTest {
        val command = ScriptedChannel { """{"id":${it.id()},"result":{"type":"future"}}""" }
        val subscription = ManualChannel()
        val opened = mutableListOf<Boolean>()
        val client = JsonDaemonClient(ProtocolChannelFactory { human -> opened += human; if (human) command else subscription }, backgroundScope)
        client.request(DaemonRequest.ListSessions)
        val events = async { client.subscribe(SubscriptionRequest("demo", 15u, "boot")).take(2).toList() }
        runCurrent()
        val written = subscription.sent.tryReceive()
        assertTrue(written.isSuccess, "Subscription request must be written")
        val request = written.getOrThrow()
        val body = Json.parseToJsonElement(request).jsonObject.getValue("params").jsonObject
        assertEquals("15", body["after_seq"].toString())
        assertTrue(body.getValue("types").jsonArray.any { it.jsonPrimitive.content == "transcript" })
        assertTrue(body.getValue("types").jsonArray.none { it.jsonPrimitive.content in setOf("output", "notification") })
        subscription.incoming.send("""{"id":${request.id()},"result":{"type":"subscribed","seq":20,"boot_id":"boot","replayed":1}}
{"type":"future_event","seq":16,"boot_id":"boot","unknown":"preserve"}
""".encodeToByteArray())
        val result = events.await()
        assertEquals(20uL, assertIs<DaemonEvent.Subscribed>(result[0]).seq)
        assertEquals("future_event", assertIs<DaemonEvent.Record>(result[1]).type)
        assertEquals(listOf(true, false), opened)
        assertTrue(subscription.closed)
        assertTrue(!command.closed)
        client.close()
    }

    @Test
    fun cancellingRequestClosesItsChannel() = runTest {
        val channel = ManualChannel()
        val client = JsonDaemonClient(ProtocolChannelFactory { channel }, backgroundScope)
        val request = launch { client.request(DaemonRequest.ListSessions) }
        runCurrent()
        request.cancel()
        runCurrent()
        assertTrue(channel.closed)
        client.close()
    }
}

internal fun String.id(): Long = Json.parseToJsonElement(this).jsonObject.getValue("id").jsonPrimitive.long

internal class ManualChannel : ProtocolChannel {
    val sent = Channel<String>(Channel.UNLIMITED)
    val incoming = Channel<ByteArray>(Channel.UNLIMITED)
    var closed = false
    override suspend fun send(bytes: ByteArray) { sent.send(bytes.decodeToString()) }
    override suspend fun receive(): ByteArray? = incoming.receiveCatching().getOrNull()
    override fun close() { closed = true; incoming.close() }
}
