package dev.gaiusred.tuiosmobile.storage

import androidx.room.Entity
import androidx.room.Index

@Entity(tableName = "history_entries", primaryKeys = ["recordKey", "entryId", "contentType"],
    indices = [Index(value = ["hostId", "sessionId"])])
internal data class HistoryEntity(
    val recordKey: String,
    val hostId: String,
    val sessionId: String,
    val entryId: String,
    val contentType: String,
    val position: Long,
    val content: String,
    val receivedSeconds: Long,
    val receivedNanos: Int,
    val serverAt: String?,
)

internal data class EntryPosition(val entryId: String, val contentType: String, val position: Long)

@Entity(tableName = "history_cursors", primaryKeys = ["recordKey"],
    indices = [Index(value = ["hostId", "sessionId"])])
internal data class CursorEntity(
    val recordKey: String,
    val hostId: String,
    val sessionId: String,
    val value: String,
    val receivedSeconds: Long,
    val receivedNanos: Int,
)

@Entity(tableName = "mail_drafts", primaryKeys = ["hostId", "sessionId", "generationKey", "recipient", "threadKey"])
internal data class DraftEntity(
    val hostId: String,
    val sessionId: String,
    val recipient: String,
    val threadKey: String,
    val text: String,
    val replyTo: String? = null,
    val generationKey: String,
    @androidx.room.ColumnInfo(defaultValue = "'Draft'") val status: String = "Draft",
)

@Entity(tableName = "sync_checkpoints", primaryKeys = ["hostId"])
internal data class SyncCheckpointEntity(val hostId: String, val content: String,
    val receivedSeconds: Long, val receivedNanos: Int)
