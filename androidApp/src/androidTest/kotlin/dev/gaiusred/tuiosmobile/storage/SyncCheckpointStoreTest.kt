package dev.gaiusred.tuiosmobile.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.gaiusred.tuiosmobile.identity.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Instant
import dev.gaiusred.tuiosmobile.connection.*
import dev.gaiusred.tuiosmobile.sync.ForegroundSync
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

@RunWith(AndroidJUnit4::class)
class SyncCheckpointStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "sync-checkpoint-test.db"
    private val host = HostId("fabricated-host")
    private val now = Instant.parse("2026-11-05T01:00:00Z")
    private val key = RecordKey(host, "stable-session", "stable-window", "boot-A", "current-generation")
    private lateinit var store: RoomHistoryStore
    @Before fun setup() { context.deleteDatabase(name); store = RoomHistoryStore(context, name) }
    @After fun cleanup() { store.close(); context.deleteDatabase(name) }

    @Test fun retainedGenerationIdentitySurvivesPruningCurrentReplacementAndReopen() = runBlocking {
        val firstReceipt = now - 31.days
        val metadata = CachedContent.TranscriptGeneration("original session", "original agent", "original-agent-life", "future")
        store.apply(HistoryBatch(key, listOf(CachedEntry("identity", metadata, firstReceipt),
            CachedEntry("expired", CachedContent.Transcript("assistant", "text", "old"), firstReceipt),
            CachedEntry("retained", CachedContent.Transcript("assistant", "text", "still downloaded"), now)),
            checkpoint = checkpoint().copy(agents = emptyList(), sessions = emptyList())))
        store.prune(RetentionPolicy.ThirtyDays, now)
        assertEquals(metadata, store.observe(HistoryQuery(host, key = key)).first().records
            .map { it.entry.content }.filterIsInstance<CachedContent.TranscriptGeneration>().singleOrNull())
        // Receiving a newer page must not overwrite the generation's original identity metadata.
        store.apply(HistoryBatch(key, listOf(CachedEntry("identity", metadata.copy(agentName = "renamed later"), now))))
        store.close(); store = RoomHistoryStore(context, name)
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val offlineConnection = object : HostConnection {
                override val state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
                override suspend fun connect(host: HostProfile) { error("Read-only retained lookup cannot connect") }
                override suspend fun openChannel(kind: ChannelKind): ProxyChannel = error("No network read allowed")
                override suspend fun disconnect() = Unit
            }
            val repo = ForegroundSync(offlineConnection, store, owner, MutableStateFlow<HostId?>(host)) { now }
            val retained = repo.retainedConversations(host).first().single()
            assertEquals("original agent", retained.agentName)
            assertEquals("original-agent-life", retained.agentSessionId)
            assertEquals(key, retained.target.key)
            assertEquals(listOf("still downloaded"), repo.retainedConversation(retained.target).first().entries.map { it.content.text })
            store.prune(RetentionPolicy.ThirtyDays, now + 31.days)
            assertTrue(repo.retainedConversations(host).first().isEmpty())
            assertTrue(store.observe(HistoryQuery(host)).first().records.isEmpty())
            store.apply(HistoryBatch(key, listOf(CachedEntry("identity", metadata, now), entry("fresh"))))
            assertEquals(1, repo.retainedConversations(host).first().size)
            store.clearHost(host)
            assertTrue(repo.retainedConversations(host).first().isEmpty())
        } finally { owner.cancel() }
    }

    @Test fun currentPointerAndExpandableDataSurviveDatabaseRestart() = runBlocking {
        val checkpoint = checkpoint()
        val detail = CachedContent.Transcript("tool", "tool_result", "result", tool = "apply_patch", target = "a.kt",
            status = "ok", toolId = "tool-1", plan = "plan", todos = listOf(CachedTodo("check", "pending")),
            diff = CachedDiff("a.kt", 1, 0, listOf(CachedHunk(1, 1, listOf(CachedDiffLine("+", "line"))))))
        store.apply(HistoryBatch(key, listOf(CachedEntry("detail", detail, now)), checkpoint = checkpoint))
        store.apply(HistoryBatch(key.copy(transcriptId = "older-generation"),
            listOf(CachedEntry("later", CachedContent.Transcript("assistant", "text", "old branch"), now + 1.days))))
        store.close(); store = RoomHistoryStore(context, name)
        val snapshot = store.observe(HistoryQuery(host)).first()
        assertEquals(checkpoint, snapshot.checkpoint)
        assertEquals("current-generation", snapshot.checkpoint?.agents?.single()?.transcript?.generation)
        assertEquals(detail, snapshot.records.single { it.key == key }.entry.content)
    }

    @Test fun checkpointFailureRollsBackTranscriptAndCurrentPointerTogether() = runBlocking {
        store.apply(HistoryBatch(key, listOf(entry("old")), checkpoint = checkpoint()))
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { database ->
            database.execSQL("CREATE TRIGGER reject_checkpoint BEFORE INSERT ON sync_checkpoints " +
                "BEGIN SELECT RAISE(ABORT, 'fabricated checkpoint failure'); END")
        }
        var failed = false
        try {
            store.apply(HistoryBatch(key.copy(transcriptId = "new-generation"), listOf(entry("new")),
                checkpoint = checkpoint().copy(nextGeneration = 2)))
        } catch (_: Exception) { failed = true }
        assertTrue(failed)
        val snapshot = store.observe(HistoryQuery(host)).first()
        assertEquals(listOf("old"), snapshot.records.map { it.entry.id })
        assertEquals(1L, snapshot.checkpoint?.nextGeneration)
    }

    @Test fun pruneAndClearHostRemoveCheckpointWithoutCrossingHosts() = runBlocking {
        val other = HostId("other-host")
        val cutoff = now - 30.days
        store.saveCheckpoint(checkpoint(at = cutoff - 1.nanoseconds))
        store.saveCheckpoint(checkpoint(other, cutoff))
        store.prune(RetentionPolicy.ThirtyDays, now)
        assertNull(store.observe(HistoryQuery(host)).first().checkpoint)
        assertNotNull(store.observe(HistoryQuery(other)).first().checkpoint)
        store.clearHost(other)
        assertNull(store.observe(HistoryQuery(other)).first().checkpoint)
    }

    @Test fun versionOneMigratesWithoutLosingExistingDrafts() = runBlocking {
        store.close(); context.deleteDatabase(name)
        val assetContext = InstrumentationRegistry.getInstrumentation().context
        val schema = assetContext.assets.open("dev.gaiusred.tuiosmobile.storage.AppDatabase/1.json")
            .bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject.getValue("database").jsonObject }
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { database ->
            schema.getValue("entities").jsonArray.forEach { value ->
                val entity = value.jsonObject
                val table = entity.getValue("tableName").jsonPrimitive.content
                database.execSQL(entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                entity["indices"]?.jsonArray?.forEach { index ->
                    database.execSQL(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
            database.execSQL("INSERT INTO mail_drafts VALUES ('fabricated-host','stable-session','agent','null','draft survives')")
            database.version = 1
        }
        store = RoomHistoryStore(context, name)
        assertEquals("draft survives", store.observe(HistoryQuery(host)).first().drafts.single().text)
        store.saveCheckpoint(checkpoint())
        assertEquals(checkpoint(), store.observe(HistoryQuery(host)).first().checkpoint)
    }

    private fun checkpoint(id: HostId = host, at: Instant = now) = SyncCheckpoint(id, "boot-A", eventSeq = 7u,
        sessions = listOf(CachedSession(key.sessionId, "display", 1, false)),
        agents = listOf(CachedAgent(key.sessionId, key.paneId!!, "agent", "working", "future-harness", "agent-life",
            false, true, TranscriptCheckpoint("current-generation", "opaque-forward", "opaque-older"), TranscriptAvailability.Available)),
        nextGeneration = 1, receivedSeconds = at.epochSeconds, receivedNanos = at.nanosecondsOfSecond)

    private fun entry(id: String) = CachedEntry(id, CachedContent.Transcript("assistant", "text", id), now)
}
