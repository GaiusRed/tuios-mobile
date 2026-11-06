package dev.gaiusred.tuiosmobile.storage

import dev.gaiusred.tuiosmobile.identity.HostId
import dev.gaiusred.tuiosmobile.identity.RecordKey
import kotlinx.serialization.json.Json
import kotlin.time.Instant

internal val historyJson = Json { classDiscriminator = "contentType" }

internal fun RecordKey.encoded(): String = historyJson.encodeToString(this)

internal fun CachedEntry.entity(key: RecordKey, position: Long): HistoryEntity = HistoryEntity(
    key.encoded(), key.hostId.value, key.sessionId, id, content.type(), position,
    historyJson.encodeToString(content), receivedAt.epochSeconds, receivedAt.nanosecondsOfSecond,
    serverAt?.toString(),
)

internal fun CachedContent.type(): String = when (this) {
    is CachedContent.TranscriptGeneration -> "transcript_generation"
    is CachedContent.Transcript -> "transcript"
    is CachedContent.Mail -> "mail"
    is CachedContent.MailGap -> "mail_gap"
    is CachedContent.Attention -> "attention"
    is CachedContent.AttentionAttempt -> "attention_attempt"
    is CachedContent.Session -> "session"
    is CachedContent.Agent -> "agent"
}

internal fun HistoryEntity.record() = HistoryRecord(historyJson.decodeFromString(recordKey),
    CachedEntry(entryId, historyJson.decodeFromString(content),
        Instant.fromEpochSeconds(receivedSeconds, receivedNanos), serverAt?.let(Instant::parse)))

internal fun CursorEntity.position() = HistoryPosition(historyJson.decodeFromString(recordKey),
    HistoryCursor(value, Instant.fromEpochSeconds(receivedSeconds, receivedNanos)))

internal fun HistoryCursor.entity(key: RecordKey) = CursorEntity(key.encoded(), key.hostId.value,
    key.sessionId, value, receivedAt.epochSeconds, receivedAt.nanosecondsOfSecond)

internal fun MailDraft.entity() = DraftEntity(hostId.value, sessionId, recipient,
    historyJson.encodeToString(threadId), text, replyTo, generation.key(), status.name)

internal fun DraftEntity.draft() = MailDraft(HostId(hostId), sessionId, recipient,
    historyJson.decodeFromString(threadKey), text, replyTo, generationKey.bootId(),
    enumValues<MailDraftStatus>().find { it.name == status } ?: MailDraftStatus.Uncertain)

internal fun MailDraftGeneration.key(): String = when (this) {
    MailDraftGeneration.Legacy -> "legacy"
    is MailDraftGeneration.Boot -> "boot:$id"
}
private fun String.bootId(): String? = when {
    this == "legacy" -> null
    startsWith("boot:") -> removePrefix("boot:")
    else -> error("Unsupported draft generation")
}
