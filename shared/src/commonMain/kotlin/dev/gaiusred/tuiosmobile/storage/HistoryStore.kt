package dev.gaiusred.tuiosmobile.storage

import dev.gaiusred.tuiosmobile.identity.HostId
import dev.gaiusred.tuiosmobile.identity.RecordKey
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

interface HistoryStore {
    /** Noncredential index includes history, drafts, cursors and checkpoints, even without a saved connection. */
    fun observeHosts(): Flow<List<HostId>> = kotlinx.coroutines.flow.flowOf(emptyList())
    fun observe(query: HistoryQuery): Flow<HistorySnapshot>
    suspend fun apply(batch: HistoryBatch)
    suspend fun saveCheckpoint(checkpoint: SyncCheckpoint)
    suspend fun saveDraft(draft: MailDraft)
    /** Atomically move an unchanged legacy draft into an empty, verified generation's root slot. */
    suspend fun adoptLegacyDraft(legacy: MailDraft, adopted: MailDraft): Boolean
    suspend fun prune(policy: RetentionPolicy, now: Instant)
    suspend fun clearHost(hostId: HostId)
}

data class HistoryQuery(val hostId: HostId, val sessionId: String? = null, val key: RecordKey? = null)
/** Entries are in daemon order. Prepend is for older pages; replay keeps existing positions.
 * TranscriptGeneration metadata is immutable once inserted and lives while its generation has content.
 */
data class HistoryBatch(val key: RecordKey, val entries: List<CachedEntry>, val cursor: HistoryCursor? = null,
    val placement: BatchPlacement = BatchPlacement.Append, val checkpoint: SyncCheckpoint? = null) {
    init {
        require(checkpoint == null || checkpoint.hostId == key.hostId) { "Checkpoint must belong to batch host" }
        require(entries.none { it.content is CachedContent.Transcript || it.content is CachedContent.TranscriptGeneration }
            || key.transcriptId != null) {
            "Transcript entries require an explicit transcript generation"
        }
    }
}
enum class BatchPlacement { Append, Prepend }
data class HistorySnapshot(val records: List<HistoryRecord> = emptyList(),
    val positions: List<HistoryPosition> = emptyList(), val drafts: List<MailDraft> = emptyList(),
    val checkpoint: SyncCheckpoint? = null)
data class HistoryRecord(val key: RecordKey, val entry: CachedEntry)
data class HistoryPosition(val key: RecordKey, val cursor: HistoryCursor)
data class CachedEntry(val id: String, val content: CachedContent, val receivedAt: Instant,
    val serverAt: Instant? = null)
data class HistoryCursor(val value: String, val receivedAt: Instant)
data class MailDraft(val hostId: HostId, val sessionId: String, val recipient: String,
    val threadId: String? = null, val text: String, val replyTo: String? = null,
    val daemonBootId: String? = null, val status: MailDraftStatus = MailDraftStatus.Draft) {
    val generation: MailDraftGeneration get() = daemonBootId?.let(MailDraftGeneration::Boot) ?: MailDraftGeneration.Legacy
}
sealed interface MailDraftGeneration {
    data object Legacy : MailDraftGeneration
    data class Boot(val id: String) : MailDraftGeneration
}
enum class MailDraftStatus { Draft, Sent, Rejected, Uncertain }
enum class RetentionPolicy(val days: Int?) {
    SevenDays(7), ThirtyDays(30), Forever(null);
    companion object { val Default = ThirtyDays }
}
