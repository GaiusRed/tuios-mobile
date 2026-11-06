package dev.gaiusred.tuiosmobile.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.gaiusred.tuiosmobile.identity.HostId
import dev.gaiusred.tuiosmobile.identity.RecordKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Instant

@RunWith(AndroidJUnit4::class)
class RoomHistoryStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "storage-test.db"
    private val host = HostId("host-a")
    private val otherHost = HostId("host-b")
    private val now = Instant.parse("2026-11-05T12:00:00Z")
    private val key = RecordKey(host, "session-id", "pane-id", "boot-1", "transcript-1")
    private lateinit var store: RoomHistoryStore

    @Before fun setup() { context.deleteDatabase(name); store = RoomHistoryStore(context, name) }
    @After fun cleanup() { store.close(); context.deleteDatabase(name) }

    @Test fun batchAndCursorCommitTogether() = runBlocking {
        store.apply(HistoryBatch(key, listOf(entry("one")), HistoryCursor("cursor-1", now)))
        val snapshot = store.observe(HistoryQuery(host, key = key)).first()
        assertEquals(listOf("one"), snapshot.records.map { it.entry.id })
        assertEquals("cursor-1", snapshot.positions.single().cursor.value)
    }

    @Test fun cachedHostIndexSurvivesReopenAndIncludesDraftOnlyHosts() = runBlocking {
        store.apply(HistoryBatch(key, listOf(entry("one"))))
        store.saveDraft(MailDraft(otherHost, "session", "agent", text = "unsent"))
        store.close(); store = RoomHistoryStore(context, name)
        assertEquals(setOf(host, otherHost), store.observeHosts().first().toSet())
        store.clearHost(host)
        assertEquals(listOf(otherHost), store.observeHosts().first())
        store.prune(RetentionPolicy.SevenDays, now + 100.days)
        assertEquals(listOf(otherHost), store.observeHosts().first())
    }

    @Test fun cursorFailureRollsBackEntries() = runBlocking {
        store.apply(HistoryBatch(key, listOf(entry("one")), HistoryCursor("cursor-1", now)))
        assertEquals(1, store.observe(HistoryQuery(host)).first().records.size)
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { database ->
            database.execSQL("CREATE TRIGGER reject_cursor BEFORE INSERT ON history_cursors " +
                "BEGIN SELECT RAISE(ABORT, 'test cursor failure'); END")
        }
        var failed = false
        try { store.apply(HistoryBatch(key, listOf(entry("two")), HistoryCursor("cursor-2", now))) }
        catch (_: Exception) { failed = true }
        assertTrue("The database constraint must reject the transaction", failed)
        val snapshot = store.observe(HistoryQuery(host)).first()
        assertEquals(listOf("one"), snapshot.records.map { it.entry.id })
        assertEquals("cursor-1", snapshot.positions.single().cursor.value)
    }

    @Test fun duplicateEntryUpsertsOnce() = runBlocking {
        store.apply(HistoryBatch(key, listOf(entry("one", text = "before"))))
        store.apply(HistoryBatch(key, listOf(entry("one", now + 1.days, "after"))))
        val snapshot = store.observe(HistoryQuery(host)).first()
        assertEquals(1, snapshot.records.size)
        val record = snapshot.records.single().entry
        assertEquals("after", (record.content as CachedContent.Transcript).text)
        assertEquals("First receipt must survive replay", now, record.receivedAt)
    }

    @Test fun pruneKeepsDraftsAndConnections() = runBlocking {
        val preferences = context.getSharedPreferences("test-connections", Context.MODE_PRIVATE)
        preferences.edit().putString("host", "retained-host-descriptor").commit()
        val draft = MailDraft(host, "session-id", "agent", text = "unsent")
        store.saveDraft(draft)
        store.apply(HistoryBatch(key, listOf(entry("old", now - 31.days)), HistoryCursor("old", now - 31.days)))
        store.prune(RetentionPolicy.ThirtyDays, now)
        val snapshot = store.observe(HistoryQuery(host)).first()
        assertTrue(snapshot.records.isEmpty())
        assertTrue(snapshot.positions.isEmpty())
        assertEquals(listOf(draft), snapshot.drafts)
        assertEquals("retained-host-descriptor", preferences.getString("host", null))
        preferences.edit().clear().commit()
        Unit
    }

    @Test fun clearHostRemovesHistoryCursorsAndDrafts() = runBlocking {
        val other = key.copy(hostId = otherHost)
        for (recordKey in listOf(key, other)) {
            store.apply(HistoryBatch(recordKey, listOf(entry("one")), HistoryCursor("cursor", now)))
            store.saveDraft(MailDraft(recordKey.hostId, "session-id", "agent", text = "draft"))
        }
        store.clearHost(host)
        val cleared = store.observe(HistoryQuery(host)).first()
        assertEquals(HistorySnapshot(), cleared)
        val retained = store.observe(HistoryQuery(otherHost)).first()
        assertEquals(1, retained.records.size)
        assertEquals(1, retained.positions.size)
        assertEquals(1, retained.drafts.size)
    }

    @Test fun sevenAndThirtyDayBoundariesUseLocalReceipt() = runBlocking {
        for (policy in listOf(RetentionPolicy.SevenDays, RetentionPolicy.ThirtyDays)) {
            store.clearHost(host)
            val cutoff = now - policy.days!!.days
            store.apply(HistoryBatch(key, listOf(entry("expired", cutoff - 1.nanoseconds),
                entry("boundary", cutoff), entry("fresh", now))))
            store.prune(policy, now)
            assertEquals(listOf("boundary", "fresh"),
                store.observe(HistoryQuery(host)).first().records.map { it.entry.id }.sorted())
        }
    }

    @Test fun foreverRetentionKeepsOldContent() = runBlocking {
        store.apply(HistoryBatch(key, listOf(entry("old", now - 900.days))))
        store.prune(RetentionPolicy.Forever, now)
        val snapshot = store.observe(HistoryQuery(host)).first()
        assertEquals(1, snapshot.records.size)
        assertEquals("old", snapshot.records.single().entry.id)
    }

    @Test fun draftsAndCachedNavigationSurviveProcessRestart() = runBlocking {
        val sessionKey = RecordKey(host, "session-id", null, "boot-1", null)
        store.apply(HistoryBatch(sessionKey, listOf(CachedEntry("session-id",
            CachedContent.Session("Display name", 1, true), now))))
        store.apply(HistoryBatch(key, listOf(CachedEntry("agent-id",
            CachedContent.Agent("Agent name", "working", "codex", "agent-session", false, true), now))))
        store.saveDraft(MailDraft(host, "session-id", "agent", text = "first"))
        store.saveDraft(MailDraft(host, "session-id", "agent", text = "updated"))
        store.close()
        store = RoomHistoryStore(context, name)
        val snapshot = store.observe(HistoryQuery(host)).first()
        assertEquals(2, snapshot.records.size)
        assertEquals("updated", snapshot.drafts.single().text)
    }

    @Test fun reusedPaneAndDaemonRestartKeepSeparateTranscripts() = runBlocking {
        val reset = key.copy(transcriptId = "transcript-2")
        val restart = key.copy(daemonBootId = "boot-2")
        for ((index, recordKey) in listOf(key, reset, restart).withIndex()) {
            store.apply(HistoryBatch(recordKey, listOf(entry("same-entry", text = "body-$index"))))
        }
        assertEquals(3, store.observe(HistoryQuery(host)).first().records.size)
        assertEquals("body-0", (store.observe(HistoryQuery(host, key = key)).first()
            .records.single().entry.content as CachedContent.Transcript).text)
    }

    @Test fun sessionWideMailHasNoPaneOwner() = runBlocking {
        val sessionKey = RecordKey(host, "session-id", null, "boot-1", null)
        store.apply(HistoryBatch(sessionKey, listOf(CachedEntry("mail-1",
            CachedContent.Mail("human", "human", "agent", "hello", "1"), now))))
        assertEquals(1, store.observe(HistoryQuery(host, sessionId = "session-id")).first().records.size)
        assertTrue(store.observe(HistoryQuery(otherHost)).first().records.isEmpty())
    }

    @Test fun uncertainMailAndLocalReadMarkSurviveDatabaseReopenAndRetention() = runBlocking {
        val mailKey = RecordKey(host, "session-id", null, "mail-boot", null)
        val uncertain = MailDraft(host, "session-id", "full-window", "7", "May have been sent",
            replyTo = "9", daemonBootId = "mail-boot", status = MailDraftStatus.Uncertain)
        store.saveDraft(uncertain)
        store.apply(HistoryBatch(mailKey, listOf(CachedEntry("9", CachedContent.Mail("message", "full-window", "human",
            "Retained Mail", "7", sessionName = "Original session", fromLabel = "Original sender",
            remoteReadAt = 123, localRead = true), now))))
        store.close()
        store = RoomHistoryStore(context, name)
        val restored = store.observe(HistoryQuery(host)).first()
        assertEquals(uncertain, restored.drafts.single())
        val mail = restored.records.single().entry.content as CachedContent.Mail
        assertTrue(mail.localRead)
        assertEquals(123L, mail.remoteReadAt)
        assertEquals("Original sender", mail.fromLabel)
        store.prune(RetentionPolicy.SevenDays, now + 8.days)
        assertEquals(listOf(uncertain), store.observe(HistoryQuery(host)).first().drafts)
    }

    @Test fun draftsWithEqualRootAndThreadIdentitiesRemainSeparateAcrossBoots() = runBlocking {
        val oldRoot = MailDraft(host, "session-id", "full-window", text = "Old root", daemonBootId = "old")
        val newRoot = oldRoot.copy(text = "Current root", daemonBootId = "new")
        val oldReply = oldRoot.copy(threadId = "7", replyTo = "9", text = "Old uncertain reply", status = MailDraftStatus.Uncertain)
        val newReply = newRoot.copy(threadId = "7", replyTo = "9", text = "Current reply")
        listOf(oldRoot, newRoot, oldReply, newReply).forEach { store.saveDraft(it) }
        store.close(); store = RoomHistoryStore(context, name)
        val drafts = store.observe(HistoryQuery(host)).first().drafts
        assertEquals(4, drafts.size)
        assertTrue(drafts.containsAll(listOf(oldRoot, newRoot, oldReply, newReply)))
        store.saveDraft(newReply.copy(text = "Edited current reply", status = MailDraftStatus.Sent))
        assertEquals(oldReply, store.observe(HistoryQuery(host)).first().drafts.single { it.daemonBootId == "old" && it.threadId == "7" })
    }

    @Test fun legacyAdoptionPreservesOtherBootsAndCannotOverwriteAnAttempt() = runBlocking {
        val legacy = MailDraft(host, "session-id", "full-window", "7", "Recovered text", replyTo = "9")
        val old = legacy.copy(threadId = null, replyTo = null, daemonBootId = "old", status = MailDraftStatus.Uncertain)
        val blocked = old.copy(daemonBootId = "new")
        val adopted = legacy.copy(threadId = null, replyTo = null, daemonBootId = "new")
        listOf(legacy, old, blocked).forEach { store.saveDraft(it) }
        assertFalse(store.adoptLegacyDraft(legacy, adopted))
        assertEquals(setOf(legacy, old, blocked), store.observe(HistoryQuery(host)).first().drafts.toSet())
        store.clearHost(host)
        listOf(legacy, old).forEach { store.saveDraft(it) }
        assertTrue(store.adoptLegacyDraft(legacy, adopted))
        store.close(); store = RoomHistoryStore(context, name)
        assertEquals(setOf(old, adopted), store.observe(HistoryQuery(host)).first().drafts.toSet())
    }

    @Test fun equalReceiptTimesPreserveWireOrderAcrossReplays() = runBlocking {
        store.apply(HistoryBatch(key, listOf(entry("entry-2"), entry("entry-10"))))
        store.apply(HistoryBatch(key, listOf(entry("entry-2", text = "updated"))))
        assertEquals(listOf("entry-2", "entry-10"),
            store.observe(HistoryQuery(host, key = key)).first().records.map { it.entry.id })
    }

    @Test fun olderPagePrependsInWireOrderAndKeepsOverlappingEntriesInPlace() = runBlocking {
        store.apply(HistoryBatch(key, listOf(entry("opaque-z"), entry("opaque-a"))))
        store.apply(HistoryBatch(key, listOf(entry("opaque-y", now + 1.days),
            entry("opaque-b", now + 1.days), entry("opaque-z", now + 1.days)),
            placement = BatchPlacement.Prepend))
        val snapshot = store.observe(HistoryQuery(host, key = key)).first()
        assertEquals(listOf("opaque-y", "opaque-b", "opaque-z", "opaque-a"),
            snapshot.records.map { it.entry.id })
        assertEquals(now, snapshot.records[2].entry.receivedAt)
    }

    private fun entry(id: String, received: Instant = now, text: String = id) = CachedEntry(id,
        CachedContent.Transcript("assistant", "text", text), received,
        serverAt = Instant.parse("2050-01-01T00:00:00Z"))
}
