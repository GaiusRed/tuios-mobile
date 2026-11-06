package dev.gaiusred.tuiosmobile.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert

@Dao
internal interface HistoryDao {
    @Query("SELECT hostId FROM history_entries UNION SELECT hostId FROM history_cursors " +
        "UNION SELECT hostId FROM mail_drafts UNION SELECT hostId FROM sync_checkpoints ORDER BY hostId")
    suspend fun hosts(): List<String>
    @Query("SELECT * FROM sync_checkpoints WHERE hostId = :host")
    suspend fun checkpoint(host: String): SyncCheckpointEntity?

    @Upsert suspend fun putCheckpoint(checkpoint: SyncCheckpointEntity)

    @Query("DELETE FROM sync_checkpoints WHERE hostId = :host") suspend fun clearCheckpoint(host: String)

    @Query("DELETE FROM sync_checkpoints WHERE receivedSeconds < :seconds " +
        "OR (receivedSeconds = :seconds AND receivedNanos < :nanos)")
    suspend fun pruneCheckpoints(seconds: Long, nanos: Int)

    @Query("SELECT * FROM history_entries WHERE hostId = :host AND (:session IS NULL OR sessionId = :session) " +
        "AND (:key IS NULL OR recordKey = :key) ORDER BY recordKey, position")
    suspend fun entries(host: String, session: String?, key: String?): List<HistoryEntity>

    @Query("SELECT entryId, contentType, position FROM history_entries WHERE recordKey = :key")
    suspend fun positions(key: String): List<EntryPosition>

    @Query("SELECT * FROM history_cursors WHERE hostId = :host AND (:session IS NULL OR sessionId = :session) " +
        "AND (:key IS NULL OR recordKey = :key) ORDER BY recordKey")
    suspend fun cursors(host: String, session: String?, key: String?): List<CursorEntity>

    @Query("SELECT * FROM mail_drafts WHERE hostId = :host AND (:session IS NULL OR sessionId = :session) " +
        "ORDER BY sessionId, generationKey, recipient, threadKey")
    suspend fun drafts(host: String, session: String?): List<DraftEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEntry(entry: HistoryEntity)

    // Replay may update content, but cannot rejuvenate retention by changing first receipt.
    @Query("UPDATE history_entries SET content = :content, serverAt = :serverAt " +
        "WHERE recordKey = :key AND entryId = :id AND contentType = :type")
    suspend fun updateEntry(key: String, id: String, type: String, content: String, serverAt: String?)

    @Upsert suspend fun putCursor(cursor: CursorEntity)
    @Upsert suspend fun putDraft(draft: DraftEntity)
    @Query("DELETE FROM mail_drafts WHERE hostId = :host AND sessionId = :session AND generationKey = :generation " +
        "AND recipient = :recipient AND threadKey = :thread")
    suspend fun deleteDraft(host: String, session: String, generation: String, recipient: String, thread: String)

    @Query("DELETE FROM history_entries WHERE contentType NOT IN ('transcript_generation', 'attention_attempt') AND (receivedSeconds < :seconds " +
        "OR (receivedSeconds = :seconds AND receivedNanos < :nanos))")
    suspend fun pruneEntries(seconds: Long, nanos: Int)

    @Query("DELETE FROM history_entries WHERE contentType = 'transcript_generation' AND NOT EXISTS " +
        "(SELECT 1 FROM history_entries AS transcript WHERE transcript.recordKey = history_entries.recordKey " +
        "AND transcript.contentType = 'transcript')")
    suspend fun pruneOrphanGenerationMetadata()

    @Query("DELETE FROM history_cursors WHERE receivedSeconds < :seconds " +
        "OR (receivedSeconds = :seconds AND receivedNanos < :nanos)")
    suspend fun pruneCursors(seconds: Long, nanos: Int)

    @Query("DELETE FROM history_entries WHERE hostId = :host") suspend fun clearEntries(host: String)
    @Query("DELETE FROM history_cursors WHERE hostId = :host") suspend fun clearCursors(host: String)
    @Query("DELETE FROM mail_drafts WHERE hostId = :host") suspend fun clearDrafts(host: String)
}
