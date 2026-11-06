package dev.gaiusred.tuiosmobile.protocol

import kotlinx.serialization.json.*

internal enum class RetryClass { SAFE_READ, PRESENCE_BOUND_READ, MUTATION, PRESENCE }

internal val DaemonRequest.retryClass: RetryClass
    get() = when (this) {
        is DaemonRequest.SendMail, is DaemonRequest.Respond, is DaemonRequest.ReplyApproval,
        is DaemonRequest.AnswerAsk, is DaemonRequest.DismissAttention -> RetryClass.MUTATION
        is DaemonRequest.AttachPresence -> RetryClass.PRESENCE
        // A replacement stream has no live presence nonce. Reconciliation reacquires it.
        is DaemonRequest.Transcript -> RetryClass.PRESENCE_BOUND_READ
        else -> RetryClass.SAFE_READ
    }

internal fun encodeRequest(id: Long, request: DaemonRequest): ByteArray {
    val params = buildJsonObject {
        when (request) {
            is DaemonRequest.Hello -> { put("client", request.client); put("version", request.version); put("protocol", 1) }
            DaemonRequest.ListSessions, DaemonRequest.ListVerbs -> Unit
            is DaemonRequest.ListAgents -> { putOptional("session", request.session); if (request.session == null) put("all_sessions", true) }
            is DaemonRequest.ListAttention -> putOptional("session", request.session)
            is DaemonRequest.PeekPrompt -> { put("session", request.session); put("window", request.window) }
            is DaemonRequest.GetApproval -> { put("session", request.session); put("request_id", request.requestId) }
            is DaemonRequest.AttachPresence -> putOptional("session", request.session)
            is DaemonRequest.Transcript -> {
                require(request.after == null || request.before == null) { "Choose one transcript cursor direction" }
                require(request.limit in 1..1000)
                put("session", request.session); put("window", request.window); put("human_nonce", request.humanNonce)
                putOptional("after", request.after); putOptional("before", request.before); put("limit", request.limit)
            }
            is DaemonRequest.ReadMail -> {
                require(request.limit > 0)
                put("session", request.session); put("peek", true); put("limit", request.limit)
                request.thread?.let { putUnsigned("thread", it) }
            }
            is DaemonRequest.SendMail -> {
                put("session", request.session); put("to", request.to); put("from", "human")
                put("human_nonce", request.humanNonce); put("text", request.text)
                request.replyTo?.let { putUnsigned("reply_to", it) }
            }
            is DaemonRequest.Respond -> {
                put("session", request.session); put("window", request.window); put("action", request.action)
                put("prompt_id", request.promptId); put("human_nonce", request.humanNonce)
                putOptional("value", request.value); putStrings("risk_ack", request.riskAck)
            }
            is DaemonRequest.ReplyApproval -> {
                put("request_id", request.requestId); put("session", request.session); put("window", request.window)
                put("decision", request.decision); put("summary", request.summary); put("human_nonce", request.humanNonce)
                putStrings("risk_ack", request.riskAck); putOptional("plan_sha", request.planSha); putOptional("message", request.message)
            }
            is DaemonRequest.AnswerAsk -> {
                put("request_id", request.requestId); put("answer", request.answer)
                put("question", request.question); put("human_nonce", request.humanNonce)
            }
            is DaemonRequest.DismissAttention -> { put("id", request.id); put("human_nonce", request.humanNonce) }
        }
    }
    val verb = when (request) {
        is DaemonRequest.Hello -> "hello"
        DaemonRequest.ListSessions -> "list-sessions"
        DaemonRequest.ListVerbs -> "list-verbs"
        is DaemonRequest.ListAgents -> "list-agents"
        is DaemonRequest.ListAttention -> "list-attention"
        is DaemonRequest.PeekPrompt -> "peek-prompt"
        is DaemonRequest.GetApproval -> "get-approval"
        is DaemonRequest.AttachPresence -> "attach-presence"
        is DaemonRequest.Transcript -> "agent-transcript"
        is DaemonRequest.ReadMail -> "read-agent-messages"
        is DaemonRequest.SendMail -> "send-agent-message"
        is DaemonRequest.Respond -> "respond"
        is DaemonRequest.ReplyApproval -> "reply-approval"
        is DaemonRequest.AnswerAsk -> "answer-ask"
        is DaemonRequest.DismissAttention -> "dismiss-attention"
    }
    return envelope(id, verb, params)
}

internal fun encodeSubscription(id: Long, request: SubscriptionRequest): ByteArray {
    require((request.afterSeq == null) == (request.bootId == null)) { "Resume needs both boot ID and sequence" }
    return envelope(id, "subscribe", buildJsonObject {
        putOptional("session", request.session); putOptional("window", request.window)
        request.afterSeq?.let { putUnsigned("after_seq", it) }
        putOptional("boot_id", request.bootId)
        // Explicitly exclude raw output and notification streams. Unknown events remain decodable.
        putStrings("types", listOf("session-created", "session-closed", "window-created", "window-closed",
            "window-retitled", "agent-state", "agent-message", "attention", "transcript"))
    })
}

private fun envelope(id: Long, verb: String, params: JsonObject): ByteArray =
    (buildJsonObject { put("id", id); put("verb", verb); put("params", params) }.toString() + "\n").encodeToByteArray()

private fun JsonObjectBuilder.putOptional(name: String, value: String?) { if (value != null) put(name, value) }
private fun JsonObjectBuilder.putUnsigned(name: String, value: ULong) { put(name, Json.parseToJsonElement(value.toString())) }
private fun JsonObjectBuilder.putStrings(name: String, values: List<String>) { put(name, JsonArray(values.map(::JsonPrimitive))) }
