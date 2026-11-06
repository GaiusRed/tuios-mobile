package dev.gaiusred.tuiosmobile.ui

import dev.gaiusred.tuiosmobile.connection.FederationMux
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.*
import java.io.*
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList

/** Fabricated federation peer. Uses the production mux's unchanged 16-stream budget. */
internal class ForegroundPeer(private val scope: CoroutineScope, val sessionCount: Int = 1) {
    private val input = PipedInputStream(65536)
    private val replies = DataOutputStream(PipedOutputStream(input))
    private val pending = ByteArrayOutputStream()
    private val presence = mutableMapOf<Int, Pair<String, String>>()
    private val human = mutableSetOf<Int>()
    private var subscription: Int? = null
    private var helloDone = false
    private var verbsDone = false
    val calls = CopyOnWriteArrayList<String>()
    val violations = CopyOnWriteArrayList<String>()
    var protocol = 1
    var minimum = 1
    var verbs = setOf("hello", "list-verbs", "subscribe", "list-sessions", "list-agents", "attach-presence",
        "agent-transcript", "read-agent-messages", "send-agent-message", "list-attention", "peek-prompt", "respond")
    @Volatile var mailText = "First fabricated Mail"
    @Volatile var mailId = 1
    @Volatile var subject: String? = "Release review"
    val mux = FederationMux(input, object : OutputStream() {
        override fun write(value: Int) { pending.write(value) }
        override fun write(bytes: ByteArray, offset: Int, length: Int) { pending.write(bytes, offset, length) }
        override fun flush() {
            val frame = DataInputStream(ByteArrayInputStream(pending.toByteArray())); pending.reset()
            val type = frame.readUnsignedByte(); val id = frame.readInt(); val length = frame.readInt()
            val bytes = ByteArray(length); frame.readFully(bytes)
            when (type) {
                1 -> if (bytes.isNotEmpty()) { check(String(bytes) == "{\"human\":true}"); human += id }
                3 -> { human -= id; presence -= id }
                2 -> try { request(id, Json.parseToJsonElement(String(bytes)).jsonObject) }
                    catch (failure: Throwable) { violations += failure.message ?: "Peer contract violation"; throw failure }
                else -> error("Unexpected federation type")
            }
        }
    }, { input.close(); replies.close() }, scope)

    suspend fun start() { replies.writeBytes("TUIOS-LINK 1\n"); replies.flush(); mux.start() }

    private fun request(stream: Int, request: JsonObject) {
        val verb = request.getValue("verb").jsonPrimitive.content
        val params = request.getValue("params").jsonObject
        calls += verb
        check(verb in verbs) { "Unsupported request $verb" }
        if (verb !in setOf("hello", "list-verbs")) check(helloDone && verbsDone) { "Feature request before discovery" }
        val name = params["session"]?.jsonPrimitive?.content
        val result = when (verb) {
            "hello" -> { check(params["protocol"] == JsonPrimitive(1)); helloDone = true
                buildJsonObject { put("type", "hello"); put("protocol", protocol); put("min_protocol", minimum); put("daemon_version", "fabricated") } }
            "list-verbs" -> { check(helloDone); verbsDone = true
                buildJsonObject { put("type", "verb_list"); put("verbs", buildJsonArray { verbs.forEach { add(buildJsonObject { put("verb", it) }) } }) } }
            "subscribe" -> { subscription = stream
                buildJsonObject { put("type", "subscribed"); put("seq", 1); put("boot_id", "boot"); put("replayed", 0) } }
            "list-sessions" -> buildJsonObject { put("type", "session_list"); put("sessions", buildJsonArray {
                (1..sessionCount).forEach { n -> add(buildJsonObject {
                    put("id", "session-$n"); put("name", "Session $n"); put("created", 1); put("last_active", 1)
                    put("window_count", 1); put("attached", false); put("width", 80); put("height", 24)
                }) }
            }) }
            "list-agents" -> { check(name in (1..sessionCount).map { "Session $it" })
                buildJsonObject { put("type", "agent_list"); put("total", 1); put("agents", buildJsonArray { add(agent(requireNotNull(name))) }) } }
            "attach-presence" -> { check(stream in human); check(name != null)
                presence[stream] = name to "fabricated-nonce-$stream"
                buildJsonObject { put("type", "presence"); put("session", name); put("human_nonce", "fabricated-nonce-$stream"); put("client_id", "fixture") } }
            "agent-transcript" -> { check(presence[stream] == name to params["human_nonce"]?.jsonPrimitive?.content)
                buildJsonObject { put("type", "agent_transcript"); put("session", name); put("window", "pane"); put("harness", "fixture")
                    put("cursor", "cursor"); put("older", ""); put("more", false); put("reset", false); put("untrusted", true)
                    put("entries", buildJsonArray { add(buildJsonObject { put("id", "1"); put("role", "assistant"); put("kind", "text"); put("text", "Transcript $name") }) }) } }
            "read-agent-messages" -> { check(params["peek"] == JsonPrimitive(true)); check("to" !in params && "unread" !in params)
                buildJsonObject { put("type", "agent_messages"); put("total", 1); put("evicted", 0)
                    put("messages", buildJsonArray { add(buildJsonObject {
                        put("id", mailId); put("kind", "message"); put("session", name); put("from", "pane"); put("to", "human")
                        put("text", mailText); subject?.let { put("subject", it) }; put("thread_id", 1); put("sent_at", 1)
                    }) }) } }
            "list-attention" -> { check(presence[stream]?.first == name)
                buildJsonObject { put("type", "attention_list"); put("seq", 1); put("boot_id", "boot"); put("items", buildJsonArray {
                    add(buildJsonObject { put("id", "question"); put("kind", "question"); put("session", name); put("window", "pane")
                        put("summary", "Review fixture"); put("since", 1); put("seq", 1) })
                }) } }
            "peek-prompt" -> { check(presence[stream]?.first == name)
                buildJsonObject { put("type", "prompt_peek"); put("session", name); put("window", "pane"); put("prompt_id", "fixture-question")
                    put("found", true); put("answerable", true); put("message", "Review fixture")
                    put("actions", buildJsonArray { add("text") }) } }
            else -> error("No mutations are authorized in this peer")
        }
        emit(stream, buildJsonObject { put("id", request.getValue("id")); put("result", result) })
    }

    private fun agent(name: String) = buildJsonObject {
        for (key in listOf("reason", "message", "source", "foreground", "cwd", "blocked_by", "confidence", "identity", "group", "protocol")) put(key, "")
        for (key in listOf("agent_state_at", "workspace", "unread", "seen", "queued", "subagents", "completion_seq")) put(key, 0)
        for (key in listOf("focused", "ready", "finished_unread", "draft_held", "settled")) put(key, false)
        put("needs_you", true); put("session", name); put("window_id", "pane"); put("name", "Build")
        put("state", "working"); put("harness_id", "fixture"); put("agent_session_id", "life")
    }

    fun event(type: String, seq: Int) = emit(requireNotNull(subscription), buildJsonObject {
        put("type", type); put("seq", seq); put("boot_id", "boot"); if (type == "gap") put("reason", "evicted")
    })
    @Synchronized private fun emit(stream: Int, value: JsonObject) {
        val payload = (value.toString() + "\n").toByteArray()
        replies.write(ByteBuffer.allocate(9 + payload.size).put(2).putInt(stream).putInt(payload.size).put(payload).array()); replies.flush()
    }
}
