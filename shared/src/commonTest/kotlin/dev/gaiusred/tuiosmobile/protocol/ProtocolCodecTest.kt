package dev.gaiusred.tuiosmobile.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class ProtocolCodecTest {
    @Test
    fun fragmentedJsonLinesDecodeOnce() {
        val codec = ProtocolCodec()
        val wire = """{"id":7,"result":{"type":"future_result","text":"こんにちは 🐈"}}
""".encodeToByteArray()
        val frames = wire.flatMap { codec.feed(byteArrayOf(it)) }
        assertEquals(1, frames.size)
        val frame = assertIs<WireMessage.Reply>(frames.single())
        assertEquals(7, frame.id)
        assertEquals("こんにちは 🐈", assertIs<DaemonResult.Unknown>(frame.result).raw["text"]?.let {
            Json.decodeFromJsonElement(kotlinx.serialization.serializer<String>(), it)
        })
        assertEquals(emptyList(), codec.feed(byteArrayOf()))
    }

    @Test
    fun structuredFailurePreservesCode() {
        val frame = ProtocolCodec().feed("""{"id":9,"error":{"code":"future_policy","message":"Unavailable","hint":{"retry":false,"next":"manual"}}}
""".encodeToByteArray()).single()
        val failure = assertIs<WireMessage.Error>(frame).failure
        assertEquals("future_policy", assertIs<ProtocolFailure.Daemon>(failure).code)
        assertEquals("manual", failure.hint?.get("next")?.let {
            Json.decodeFromJsonElement(kotlinx.serialization.serializer<String>(), it)
        })
    }

    @Test
    fun unknownTranscriptEntryIsRetained() {
        val frame = ProtocolCodec().feed("""{"id":1,"result":{"type":"agent_transcript","session":"demo","window":"p1","harness":"future","cursor":"opaque","reset":false,"more":false,"older":"","entries":[{"id":"x1","role":"future","kind":"future_tool","text":"<script>not executable</script>","new_payload":{"x":3}}],"untrusted":true,"future_field":123}}
""".encodeToByteArray()).single()
        val result = assertIs<DaemonResult.Transcript>(assertIs<WireMessage.Reply>(frame).result)
        assertEquals("future_tool", result.entries.single().kind)
        assertEquals("<script>not executable</script>", result.entries.single().text)
        assertEquals("3", result.entries.single().raw["new_payload"]?.jsonObject?.get("x").toString())
        assertEquals("123", result.raw["future_field"].toString())
    }
}
