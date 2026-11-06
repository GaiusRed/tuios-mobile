package dev.gaiusred.tuiosmobile.protocol

import kotlinx.serialization.json.*

internal fun decodeResult(raw: JsonObject): DaemonResult = when (val type = raw.string("type")) {
    "hello" -> DaemonResult.Hello(raw.int("protocol"), raw.int("min_protocol"), raw.string("daemon_version"), raw)
    "verb_list" -> DaemonResult.Verbs(raw.objects("verbs").map { it.string("verb") }.toSet(), raw)
    "session_list" -> DaemonResult.Sessions(raw.objects("sessions").map(::session), raw)
    "agent_list" -> DaemonResult.Agents(raw.objects("agents").map(::agent), raw.int("total"), raw)
    "agent_transcript" -> DaemonResult.Transcript(raw.string("session"), raw.string("window"), raw.string("harness"),
        raw.string("cursor"), raw.bool("reset"), raw.bool("more"), raw.optionalString("older") ?: "",
        raw.objects("entries").map(::entry), raw.bool("untrusted"), raw)
    "agent_messages" -> DaemonResult.Mail(raw.objects("messages").map(::mail), raw.int("total"), raw.int("evicted", 0), raw)
    "attention_list" -> DaemonResult.Attention(raw.objects("items").map(::attention), raw.ulong("seq"), raw.string("boot_id"), raw)
    "approval" -> DaemonResult.Approval(raw.string("request_id"), raw.string("kind"), raw.string("session"),
        raw.string("window"), raw.string("summary"), raw.string("tool"), raw.string("target"), raw.strings("options"),
        raw.strings("always_scope"), raw.string("plan"), raw.string("plan_sha"),
        raw.objects("risk").map { ApprovalRisk(it.string("rule"), it.string("why")) },
        raw.bool("deny_message"), raw.bool("untrusted"), raw)
    "prompt_peek" -> DaemonResult.Prompt(raw.optionalString("session") ?: "", raw.string("window"), raw.optionalString("prompt_id"),
        raw.bool("found"), raw.bool("answerable"), raw.strings("actions"), raw.objects("options").map {
            PromptOption(it.int("n"), it.string("label"))
        }, raw)
    "presence" -> DaemonResult.Presence(raw.string("human_nonce"), raw.string("client_id"), raw.optionalString("session"), raw)
    "agent_message_sent", "prompt_response", "approval_replied", "ask_answered", "attention_dismissed" ->
        DaemonResult.Receipt(MutationReceipt(type, raw, raw.optionalULong("message_id"), raw.optionalBool("verified_human"),
            raw.optionalBool("applied"), raw.optionalString("settled_by")), raw)
    else -> DaemonResult.Unknown(type, raw)
}

private fun session(o: JsonObject) = SessionInfo(o.string("id"), o.string("name"), o.long("created"), o.long("last_active"),
    o.int("window_count"), o.bool("attached"), o.int("width"), o.int("height"), o.objects("windows"), o)

private fun agent(o: JsonObject) = AgentRow(o.string("session"), o.string("window_id"), o.string("name"),
    o.string("state"), o.string("reason"), o.string("message"), o.string("source"), o.string("harness_id"),
    o.string("foreground"), o.string("cwd"), o.string("blocked_by"), o.string("confidence"), o.string("identity"),
    o.string("agent_session_id"), o.string("group"), o.string("protocol"), o.long("agent_state_at"),
    o.int("workspace"), o.int("unread"), o.int("seen"), o.int("queued"), o.int("subagents"),
    o.optionalLong("evidence_age_ms"), o.ulong("completion_seq"), o.bool("focused"), o.bool("ready"),
    o.bool("needs_you"), o.bool("finished_unread"), o.bool("draft_held"), o.bool("settled"), o)

private fun entry(o: JsonObject) = TranscriptEntry(o.string("id"), o.string("role"), o.string("kind"),
    o.optionalString("text") ?: "", o.optionalLong("at"), o.optionalBool("truncated") ?: false, o)

private fun mail(o: JsonObject) = MailMessage(o.ulong("id"), o.string("kind"), o.string("session"),
    o.optionalString("from") ?: "", o.optionalString("to") ?: "", o.string("text"), o.ulong("thread_id"),
    o.optionalULong("reply_to"), o.long("sent_at"), o.optionalLong("read_at"), o.optionalLong("seen_at"),
    o.optionalBool("verified_human") ?: false, o.optionalBool("claimed_human") ?: false, o,
    o.optionalString("from_label"), o.optionalString("to_label"), o.optionalBool("reply_to_missing") ?: false, o.optionalString("subject"))

private fun attention(o: JsonObject) = AttentionItem(o.string("id"), o.string("kind"), o.string("session"),
    o.optionalString("window"), o.optionalString("host"), o.optionalString("summary") ?: "", o.strings("options"),
    o.optionalString("request_id"), o.long("since"), o.ulong("seq"), o.strings("risk"), o.optionalString("plan_sha"), o)

internal fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.let { require(it.isString); it.content }
internal fun JsonObject.optionalString(key: String): String? = nonNull(key)?.let { string(key) }
internal fun JsonObject.long(key: String): Long = number(key).long
internal fun JsonObject.optionalLong(key: String): Long? = nonNull(key)?.let { long(key) }
internal fun JsonObject.ulong(key: String): ULong = number(key).content.toULong()
internal fun JsonObject.optionalULong(key: String): ULong? = nonNull(key)?.let { ulong(key) }
internal fun JsonObject.int(key: String): Int = number(key).int
internal fun JsonObject.int(key: String, default: Int): Int = nonNull(key)?.let { int(key) } ?: default
internal fun JsonObject.bool(key: String): Boolean = getValue(key).jsonPrimitive.let { require(!it.isString); it.boolean }
internal fun JsonObject.optionalBool(key: String): Boolean? = nonNull(key)?.let { bool(key) }
internal fun JsonObject.objects(key: String): List<JsonObject> = nonNull(key)?.jsonArray?.map { it.jsonObject } ?: emptyList()
internal fun JsonObject.strings(key: String): List<String> = nonNull(key)?.jsonArray?.map { it.jsonPrimitive.let { p -> require(p.isString); p.content } } ?: emptyList()
private fun JsonObject.number(key: String): JsonPrimitive = getValue(key).jsonPrimitive.also { require(!it.isString && it != JsonNull) }
private fun JsonObject.nonNull(key: String): JsonElement? = get(key)?.takeUnless { it == JsonNull }
