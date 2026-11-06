package dev.gaiusred.tuiosmobile.storage

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import dev.gaiusred.tuiosmobile.identity.HostId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

class RoomHistoryStore(context: Context, databaseName: String = "history.db") : HistoryStore, AutoCloseable {
    private val database = Room.databaseBuilder(context.applicationContext,
        AppDatabase::class.java, databaseName).addMigrations(syncCheckpointMigration, mailDraftMigration, mailDraftGenerationMigration).build()
    private val dao = database.historyDao()

    override fun observeHosts(): Flow<List<HostId>> = database.invalidationTracker
        .createFlow("history_entries", "history_cursors", "mail_drafts", "sync_checkpoints")
        .map { database.withTransaction { dao.hosts().map(::HostId) } }.distinctUntilChanged()

    override fun observe(query: HistoryQuery): Flow<HistorySnapshot> = database.invalidationTracker
        .createFlow("history_entries", "history_cursors", "mail_drafts", "sync_checkpoints")
        .map {
            // One read transaction prevents mixed snapshots across history and checkpoints.
            database.withTransaction {
                val session = query.sessionId ?: query.key?.sessionId
                val key = query.key?.encoded()
                HistorySnapshot(
                    dao.entries(query.hostId.value, session, key).map(HistoryEntity::record),
                    dao.cursors(query.hostId.value, session, key).map(CursorEntity::position),
                    dao.drafts(query.hostId.value, session).map(DraftEntity::draft),
                    dao.checkpoint(query.hostId.value)?.let { historyJson.decodeFromString<SyncCheckpoint>(it.content) },
                )
            }
        }.distinctUntilChanged()

    override suspend fun apply(batch: HistoryBatch) {
        database.withTransaction {
            val positions = dao.positions(batch.key.encoded())
                .associate { (it.entryId to it.contentType) to it.position }
            val entries = batch.entries.associateBy { it.id to it.content.type() }
            val newCount = entries.keys.count { it !in positions }
            var next = when (batch.placement) {
                BatchPlacement.Append -> (positions.values.maxOrNull() ?: -1L) + 1
                BatchPlacement.Prepend -> (positions.values.minOrNull() ?: 0L) - newCount
            }
            for ((identity, entry) in entries) {
                val entity = entry.entity(batch.key, positions[identity] ?: next++)
                dao.insertEntry(entity)
                if (entry.content !is CachedContent.TranscriptGeneration) {
                    dao.updateEntry(entity.recordKey, entity.entryId, entity.contentType, entity.content, entity.serverAt)
                }
            }
            batch.cursor?.let { dao.putCursor(it.entity(batch.key)) }
            batch.checkpoint?.let { dao.putCheckpoint(it.entity()) }
        }
    }

    override suspend fun saveCheckpoint(checkpoint: SyncCheckpoint) { dao.putCheckpoint(checkpoint.entity()) }

    private fun SyncCheckpoint.entity() = SyncCheckpointEntity(hostId.value, historyJson.encodeToString(this),
        receivedSeconds, receivedNanos)

    override suspend fun saveDraft(draft: MailDraft) { dao.putDraft(draft.entity()) }

    override suspend fun adoptLegacyDraft(legacy: MailDraft, adopted: MailDraft): Boolean = database.withTransaction {
        require(legacy.generation == MailDraftGeneration.Legacy && adopted.generation is MailDraftGeneration.Boot)
        require(legacy.hostId == adopted.hostId && legacy.sessionId == adopted.sessionId && legacy.text == adopted.text)
        require(adopted.threadId == null && adopted.replyTo == null && adopted.status == MailDraftStatus.Draft)
        val saved = dao.drafts(legacy.hostId.value, legacy.sessionId).map(DraftEntity::draft)
        if (legacy !in saved || legacy.status !in setOf(MailDraftStatus.Draft, MailDraftStatus.Rejected)) return@withTransaction false
        val destination = saved.find { it.generation == adopted.generation && it.recipient == adopted.recipient && it.threadId == null }
        if (destination != null && (destination.text.isNotBlank() || destination.status != MailDraftStatus.Draft)) return@withTransaction false
        dao.putDraft(adopted.entity())
        val original = legacy.entity()
        dao.deleteDraft(original.hostId, original.sessionId, original.generationKey, original.recipient, original.threadKey)
        true
    }

    override suspend fun prune(policy: RetentionPolicy, now: Instant) {
        val cutoff = now - (policy.days ?: return).days
        database.withTransaction {
            dao.pruneEntries(cutoff.epochSeconds, cutoff.nanosecondsOfSecond)
            dao.pruneOrphanGenerationMetadata()
            dao.pruneCursors(cutoff.epochSeconds, cutoff.nanosecondsOfSecond)
            dao.pruneCheckpoints(cutoff.epochSeconds, cutoff.nanosecondsOfSecond)
        }
    }

    override suspend fun clearHost(hostId: HostId) {
        database.withTransaction {
            dao.clearEntries(hostId.value)
            dao.clearCursors(hostId.value)
            dao.clearDrafts(hostId.value)
            dao.clearCheckpoint(hostId.value)
        }
    }

    override fun close() = database.close()
}
