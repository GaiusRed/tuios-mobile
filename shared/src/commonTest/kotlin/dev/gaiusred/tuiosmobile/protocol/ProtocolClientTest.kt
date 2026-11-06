package dev.gaiusred.tuiosmobile.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertFailsWith
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest

class ProtocolClientTest {
    @Test
    fun subscriptionPreservesCollectorExceptionAndClosesChannel() = runTest {
        val channel = ScriptedChannel { request ->
            val id = request.substringAfter("\"id\":").substringBefore(',')
            """{"id":$id,"result":{"type":"subscribed","seq":1,"boot_id":"boot"}}"""
        }
        val client = JsonDaemonClient(ProtocolChannelFactory { channel }, backgroundScope)
        val failure = IllegalArgumentException("collector failed")
        val thrown = assertFailsWith<IllegalArgumentException> {
            client.subscribe(SubscriptionRequest()).collect { throw failure }
        }
        assertSame(failure, thrown)
        assertEquals(true, channel.closed)
        client.close()
    }

    @Test
    fun missingTranscriptDoesNotDisableMail() = runTest {
        val channel = ScriptedChannel { request ->
            val id = request.substringAfter("\"id\":").substringBefore(',')
            if ("agent-transcript" in request) """{"id":$id,"error":{"code":"unknown_verb","message":"Unavailable"}}"""
            else """{"id":$id,"result":{"type":"agent_messages","session":"demo","inbox":"","messages":[],"untrusted":true,"total":0,"evicted":0}}"""
        }
        val client = JsonDaemonClient(ProtocolChannelFactory { channel }, backgroundScope)
        val transcript = client.request(DaemonRequest.Transcript("demo", "p1", "fabricated-nonce"))
        assertEquals("unknown_verb", assertIs<ProtocolFailure.Daemon>(assertIs<DaemonReply.Failure>(transcript).failure).code)
        val mail = client.request(DaemonRequest.ReadMail("demo", thread = 12u))
        assertIs<DaemonResult.Mail>(assertIs<DaemonReply.Success>(mail).result)
        assertEquals(2, channel.writes.size)
        client.close()
    }

    @Test
    fun lostReplyAfterWriteReturnsUncertain() = runTest {
        val channel = ScriptedChannel { null }
        val client = JsonDaemonClient(ProtocolChannelFactory { channel }, backgroundScope)
        val reply = client.request(DaemonRequest.SendMail("demo", "p1", "fabricated-nonce", "Hello"))
        assertIs<MutationOutcome.Uncertain>(assertIs<DaemonReply.Mutation>(reply).outcome)
        assertEquals(1, channel.writes.size, "A possibly written mutation must never replay")
        client.close()
    }
}

internal class ScriptedChannel(private val reply: (String) -> String?) : ProtocolChannel {
    val writes = mutableListOf<String>()
    private val incoming = Channel<ByteArray>(Channel.UNLIMITED)
    var closed = false
    override suspend fun send(bytes: ByteArray) {
        val text = bytes.decodeToString()
        writes += text
        val response = reply(text)
        if (response == null) incoming.close() else incoming.send((response + "\n").encodeToByteArray())
    }
    override suspend fun receive(): ByteArray? = incoming.receiveCatching().getOrNull()
    override fun close() { closed = true; incoming.close() }
}
