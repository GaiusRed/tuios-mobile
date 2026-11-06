package dev.gaiusred.tuiosmobile.storage

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [HistoryEntity::class, CursorEntity::class, DraftEntity::class, SyncCheckpointEntity::class],
    version = 4, exportSchema = true)
internal abstract class AppDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao
}

internal val mailDraftGenerationMigration = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE mail_drafts_new (hostId TEXT NOT NULL, sessionId TEXT NOT NULL, " +
            "recipient TEXT NOT NULL, threadKey TEXT NOT NULL, text TEXT NOT NULL, replyTo TEXT, " +
            "generationKey TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'Draft', " +
            "PRIMARY KEY(hostId, sessionId, generationKey, recipient, threadKey))")
        db.execSQL("INSERT INTO mail_drafts_new (hostId, sessionId, recipient, threadKey, text, replyTo, generationKey, status) " +
            "SELECT hostId, sessionId, recipient, threadKey, text, replyTo, " +
            "CASE WHEN daemonBootId IS NULL THEN 'legacy' ELSE 'boot:' || daemonBootId END, status FROM mail_drafts")
        db.execSQL("DROP TABLE mail_drafts")
        db.execSQL("ALTER TABLE mail_drafts_new RENAME TO mail_drafts")
    }
}

internal val mailDraftMigration = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE mail_drafts ADD COLUMN replyTo TEXT")
        db.execSQL("ALTER TABLE mail_drafts ADD COLUMN daemonBootId TEXT")
        db.execSQL("ALTER TABLE mail_drafts ADD COLUMN status TEXT NOT NULL DEFAULT 'Draft'")
    }
}

internal val syncCheckpointMigration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `sync_checkpoints` (`hostId` TEXT NOT NULL, " +
            "`content` TEXT NOT NULL, `receivedSeconds` INTEGER NOT NULL, `receivedNanos` INTEGER NOT NULL, " +
            "PRIMARY KEY(`hostId`))")
    }
}
