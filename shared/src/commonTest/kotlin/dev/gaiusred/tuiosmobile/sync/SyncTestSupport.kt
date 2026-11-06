package dev.gaiusred.tuiosmobile.sync

import dev.gaiusred.tuiosmobile.connection.*
import dev.gaiusred.tuiosmobile.identity.*
import dev.gaiusred.tuiosmobile.storage.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import kotlin.time.Instant

internal val receiptTime = Instant.parse("2026-11-05T01:00:00Z")
internal val emptyJson = JsonObject(emptyMap())

/** Only the persistence boundary is replaced. Replay/order semantics match HistoryStore. */
internal class TestHistoryStore : HistoryStore {
    private val snapshots = MutableStateFlow(HistorySnapshot())
    override fun observe(query: HistoryQuery): Flow<HistorySnapshot> = snapshots.map { all ->
        fun matches(key: RecordKey) = key.hostId == query.hostId &&
            (query.sessionId == null || key.sessionId == query.sessionId) && (query.key == null || key == query.key)
        all.copy(records = all.records.filter { matches(it.key) }, positions = all.positions.filter { matches(it.key) },
            checkpoint = checkpoints[query.hostId])
    }
    val checkpoints = mutableMapOf<HostId, SyncCheckpoint>()
    val writes = mutableListOf<HistoryBatch>()
    override suspend fun apply(batch: HistoryBatch) {
        writes += batch
        val all = snapshots.value
        val existing = all.records.filter { it.key == batch.key }
        val incoming = batch.entries.distinctBy { it.id to it.content::class }
        val new = incoming.filter { entry -> existing.none { it.entry.id == entry.id && it.entry.content::class == entry.content::class } }
            .map { HistoryRecord(batch.key, it) }
        val updated = existing.map { record ->
            incoming.find { record.entry.content !is CachedContent.TranscriptGeneration &&
                it.id == record.entry.id && it.content::class == record.entry.content::class }
                ?.let { record.copy(entry = it.copy(receivedAt = record.entry.receivedAt)) } ?: record
        }
        batch.checkpoint?.let { checkpoints[it.hostId] = it }
        snapshots.value = all.copy(records = all.records.filter { it.key != batch.key } +
            if (batch.placement == BatchPlacement.Prepend) new + updated else updated + new,
            positions = if (batch.cursor == null) all.positions else
                all.positions.filter { it.key != batch.key } + HistoryPosition(batch.key, batch.cursor),
            checkpoint = batch.checkpoint ?: all.checkpoint)
    }
    override suspend fun saveCheckpoint(checkpoint: SyncCheckpoint) {
        checkpoints[checkpoint.hostId] = checkpoint
        snapshots.value = snapshots.value.copy(checkpoint = checkpoint)
    }
    override suspend fun saveDraft(draft: MailDraft) {}
    override suspend fun adoptLegacyDraft(legacy: MailDraft, adopted: MailDraft) = false
    override suspend fun prune(policy: RetentionPolicy, now: Instant) {}
    override suspend fun clearHost(hostId: HostId) {
        checkpoints.remove(hostId)
        snapshots.value = snapshots.value.copy(records = snapshots.value.records.filter { it.key.hostId != hostId }, checkpoint = null)
    }
}

/** Fabricated daemon, with actual JSON client and channel lifetimes exercised by sync tests. */
internal class ScriptedDaemon(val hostId: HostId = HostId("host-A")) {
    var boot = "boot-A"
    var protocol = 1
    var minProtocol = 1
    var verbs = setOf("hello", "list-verbs", "subscribe", "list-sessions", "list-agents", "attach-presence",
        "agent-transcript", "read-agent-messages", "send-agent-message", "list-attention", "peek", "get-approval", "respond", "reply-approval", "answer-ask")
    var mailText = "Fabricated new-daemon Mail"
    var mailId = 1
    var rejectSubscription = false
    var sequence = 20uL
    var sessions = listOf(session("session-stable", "display-name"))
    var agents = listOf(agent())
    var onListAgents: (() -> Unit)? = null
    var transcript: (JsonObject) -> JsonObject = { page(listOf(entry("z", "initial")), "forward-1", "older-1") }
    val requests = mutableListOf<Pair<String, JsonObject>>()
    val channels = mutableListOf<DaemonChannel>()
    var transcriptFailure: String? = null
    var rejectResume = false
    var subscriptionGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    val profile = HostProfile(hostId, "fabricated", "user", "phone", listOf(HostEndpoint("100.64.0.1", 22)), "public", "alias")
    fun event(type: String, seq: ULong? = null, eventBoot: String = boot, session: String? = null, window: String? = null) {
        val value = buildJsonObject {
            put("type", type); put("boot_id", eventBoot)
            seq?.let { put("seq", Json.parseToJsonElement(it.toString())) }
            session?.let { put("session", it) }; window?.let { put("window", it) }
            if (type == "gap") put("reason", "evicted")
        }
        channels.filter { it.subscription && !it.closed }.forEach { it.incoming.trySend((value.toString() + "\n").encodeToByteArray()) }
    }
    inner class DaemonChannel : ProxyChannel {
        override val isOpen = MutableStateFlow(true)
        val incoming = Channel<ByteArray>(Channel.UNLIMITED)
        var subscription = false
        var closed = false
        private var presenceNonce: String? = null
        override suspend fun send(bytes: ByteArray) {
            val request = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
            val verb = request.getValue("verb").jsonPrimitive.content
            val params = request.getValue("params").jsonObject
            requests += verb to params
            val selectedSession = sessions.find { it.getValue("name") == params["session"] }
            val invalidSession = verb in setOf("list-agents", "attach-presence", "agent-transcript", "read-agent-messages", "send-agent-message") && selectedSession == null
            val result = if (invalidSession) emptyJson else when (verb) {
                "hello" -> buildJsonObject { put("type", "hello"); put("protocol", protocol); put("min_protocol", minProtocol); put("daemon_version", "fixture") }
                "list-verbs" -> buildJsonObject { put("type", "verb_list"); put("verbs", buildJsonArray { verbs.forEach { add(buildJsonObject { put("verb", it) }) } }) }
                "subscribe" -> { subscription = true; subscriptionGate?.await(); buildJsonObject {
                    put("type", "subscribed"); put("boot_id", boot); put("seq", Json.parseToJsonElement(sequence.toString())); put("replayed", 0)
                } }
                "list-sessions" -> buildJsonObject { put("type", "session_list"); put("sessions", JsonArray(sessions)) }
                "list-agents" -> buildJsonObject {
                    val selectedAgents = agents.filter { it["session"] == params["session"] }
                    put("type", "agent_list"); put("agents", JsonArray(selectedAgents)); put("total", selectedAgents.size)
                }.also { onListAgents?.invoke() }
                "attach-presence" -> buildJsonObject {
                    presenceNonce = "fabricated-channel-${channels.indexOf(this@DaemonChannel)}"
                    put("type", "presence"); put("session", params.getValue("session")); put("human_nonce", presenceNonce); put("client_id", "fabricated")
                }
                "agent-transcript" -> {
                    check(presenceNonce != null && params.getValue("human_nonce").jsonPrimitive.content == presenceNonce) {
                        "Nonce must belong to this exact channel"
                    }
                    JsonObject(transcript(params) + ("session" to params.getValue("session")))
                }
                "read-agent-messages" -> {
                    check(params["peek"] == JsonPrimitive(true) && "to" !in params && "unread" !in params)
                    buildJsonObject { put("type", "agent_messages"); put("total", 1); put("evicted", 0)
                        put("messages", buildJsonArray { add(buildJsonObject {
                            put("id", mailId); put("kind", "message"); put("session", params.getValue("session")); put("from", "window-stable"); put("to", "human")
                            put("text", mailText); put("thread_id", 1); put("sent_at", 1)
                        }) }) }
                }
                "send-agent-message" -> {
                    check(presenceNonce != null && params["human_nonce"] == JsonPrimitive(presenceNonce))
                    check(params["to"] == JsonPrimitive("window-stable") && params["from"] == JsonPrimitive("human"))
                    buildJsonObject { put("type", "agent_message_sent"); put("verified_human", true); put("message_id", 2) }
                }
                else -> error("Unexpected verb $verb")
            }
            val response = buildJsonObject {
                put("id", request.getValue("id"))
                if (invalidSession) put("error", buildJsonObject {
                    put("code", "session_not_found"); put("message", "Unknown session name")
                }) else if (verb == "subscribe" && rejectSubscription) put("error", buildJsonObject {
                    put("code", "unknown_verb"); put("message", "Subscription unsupported")
                }) else if (verb == "subscribe" && rejectResume && "after_seq" in params) put("error", buildJsonObject {
                    put("code", "invalid_param"); put("message", "Resume unsupported")
                }) else if (verb == "agent-transcript" && transcriptFailure != null) put("error", buildJsonObject {
                    put("code", transcriptFailure); put("message", "Fabricated unavailable feature")
                }) else put("result", result)
            }
            incoming.send((response.toString() + "\n").encodeToByteArray())
        }
        override suspend fun receive(): ByteArray? = incoming.receiveCatching().getOrNull()
        override fun close() { closed = true; isOpen.value = false; incoming.close() }
    }
}

internal class TestHostConnection(var daemon: ScriptedDaemon) : HostConnection {
    override val state = MutableStateFlow<ConnectionState>(ConnectionState.Connected(daemon.profile))
    override suspend fun connect(host: HostProfile) { state.value = ConnectionState.Connected(host) }
    override suspend fun openChannel(kind: ChannelKind): ProxyChannel = daemon.DaemonChannel().also { daemon.channels += it }
    override suspend fun disconnect() { state.value = ConnectionState.Disconnected }
}

internal fun session(id: String, name: String) = buildJsonObject {
    put("id", id); put("name", name); put("created", 1); put("last_active", 2); put("window_count", 1)
    put("attached", false); put("width", 80); put("height", 24)
}

internal fun agent(identity: String = "agent-life-1", name: String = "agent") = buildJsonObject {
    for (key in listOf("reason", "message", "source", "foreground", "cwd", "blocked_by", "confidence", "identity", "group", "protocol")) put(key, "")
    for (key in listOf("agent_state_at", "workspace", "unread", "seen", "queued", "subagents", "completion_seq")) put(key, 0)
    for (key in listOf("focused", "ready", "needs_you", "finished_unread", "draft_held", "settled")) put(key, false)
    put("session", "display-name"); put("window_id", "window-stable"); put("name", name)
    put("state", "working"); put("harness_id", "unknown-future-harness"); put("agent_session_id", identity)
}

internal fun entry(id: String, text: String) = buildJsonObject {
    put("id", id); put("role", "assistant"); put("kind", "text"); put("text", text)
}

internal fun page(entries: List<JsonObject>, cursor: String, older: String = "", reset: Boolean = false,
    more: Boolean = false) = buildJsonObject {
    put("type", "agent_transcript"); put("session", "display-name"); put("window", "window-stable")
    put("harness", "unknown-future-harness"); put("cursor", cursor); put("older", older); put("reset", reset)
    put("more", more); put("untrusted", true); put("entries", JsonArray(entries))
}
