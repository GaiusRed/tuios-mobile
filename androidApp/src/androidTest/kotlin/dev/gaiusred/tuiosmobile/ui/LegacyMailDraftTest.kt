package dev.gaiusred.tuiosmobile.ui

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.gaiusred.tuiosmobile.connection.*
import dev.gaiusred.tuiosmobile.mail.MailRepository
import dev.gaiusred.tuiosmobile.sessions.SessionVerification
import dev.gaiusred.tuiosmobile.storage.*
import dev.gaiusred.tuiosmobile.ui.design.MessagesTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import kotlin.time.Instant

/** A real v2 database, actual repository and composer, and a strict fabricated human Mail peer. */
class LegacyMailDraftTest {
    @get:Rule val compose = createComposeRule()
    private val fixture = SessionFixtures(unsupported = true)
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "legacy-mail-compose-test.db"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var history: RoomHistoryStore
    private lateinit var repo: MailRepository
    private lateinit var connected: ConnectionState.Connected
    private var sends = 0
    private var sentParams: JsonObject? = null

    @Before fun setup() = runBlocking {
        context.deleteDatabase(name)
        val schema = InstrumentationRegistry.getInstrumentation().context.assets
            .open("dev.gaiusred.tuiosmobile.storage.AppDatabase/2.json").bufferedReader().use {
                Json.parseToJsonElement(it.readText()).jsonObject.getValue("database").jsonObject
            }
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
            schema.getValue("entities").jsonArray.forEach { value ->
                val entity = value.jsonObject
                val table = entity.getValue("tableName").jsonPrimitive.content
                db.execSQL(entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                entity["indices"]?.jsonArray?.forEach { index ->
                    db.execSQL(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
            db.execSQL("INSERT INTO mail_drafts VALUES (?, ?, ?, ?, ?)", arrayOf(fixture.host.id.value, "studio", "previous-window", "\"7\"", "Restored legacy text"))
            db.version = 2
        }
        history = RoomHistoryStore(context, name)
        assertEquals(MailDraftGeneration.Legacy, history.observe(HistoryQuery(fixture.host.id)).first().drafts.single().generation)
        history.saveCheckpoint(SyncCheckpoint(fixture.host.id, "boot", sessions = listOf(CachedSession("studio", "Mobile client", 2, true))))
        connected = ConnectionState.Connected(fixture.host)
        fixture.state.value = fixture.state.value.copy(offline = false,
            verification = SessionVerification(fixture.host.id, connected.generation, "boot"))
        val connection = object : HostConnection {
            override val state = MutableStateFlow<ConnectionState>(connected)
            override suspend fun connect(host: HostProfile) = Unit
            override suspend fun disconnect() = Unit
            override suspend fun openChannel(kind: ChannelKind): ProxyChannel = humanChannel()
        }
        repo = MailRepository(connection, history, fixture, MutableStateFlow(fixture.host.id), scope) {
            Instant.parse("2026-11-05T08:24:00Z")
        }
    }
    @After fun cleanup() { scope.cancel(); history.close(); context.deleteDatabase(name) }

    @Test fun migratedDraftRequiresExplicitCurrentRecipientAdoptionBeforeSending() {
        compose.setContent { MessagesTheme { AppRoot(fixture, listOf(fixture.host), connected, {}, mailRepository = repo) } }
        compose.onNodeWithTag("tab-Mail").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Draft to review: Restored legacy text").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Draft to review: Restored legacy text").performClick()
        compose.onNodeWithTag("mail-input").assertTextContains("Restored legacy text").assertIsNotEnabled()
        compose.onNodeWithTag("mail-send").assertIsNotEnabled()
        compose.onNodeWithText("Choose recipient").performClick()
        compose.onNodeWithTag("mail-recipient-pane").performClick()
        compose.onNodeWithText("Use in current session").performClick()
        compose.onNodeWithText("Use draft").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("mail-input") and hasText("Restored legacy text")).fetchSemanticsNodes().any {
            !it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
        } }
        compose.onNodeWithTag("mail-input").assertTextContains("Restored legacy text").assertIsEnabled()
        assertEquals(0, sends)
        compose.onNodeWithTag("mail-send").assertIsEnabled().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Sent · Verified human").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, sends)
        assertFalse(requireNotNull(sentParams).containsKey("reply_to"))
        runBlocking {
            val saved = history.observe(HistoryQuery(fixture.host.id)).first().drafts.single()
            assertEquals("boot", saved.daemonBootId)
            assertEquals("pane", saved.recipient)
            assertEquals("Restored legacy text", saved.text)
            assertNull(saved.threadId)
            assertNull(saved.replyTo)
            assertEquals(MailDraftStatus.Sent, saved.status)
        }
    }

    private fun humanChannel(): ProxyChannel = object : ProxyChannel {
        override val isOpen = MutableStateFlow(true)
        val incoming = Channel<ByteArray>(Channel.UNLIMITED)
        val nonce = "fabricated-channel-${System.identityHashCode(this)}"
        var attached = false
        override suspend fun send(bytes: ByteArray) {
            val request = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
            val verb = request.getValue("verb").jsonPrimitive.content
            val params = request.getValue("params").jsonObject
            check(params["session"] == JsonPrimitive("Mobile client")) { "Current session NAME required" }
            val result = when (verb) {
                "read-agent-messages" -> {
                    check(params["peek"] == JsonPrimitive(true) && "to" !in params && "unread" !in params)
                    buildJsonObject { put("type", "agent_messages"); put("total", 0); put("evicted", 0); put("messages", JsonArray(emptyList())) }
                }
                "attach-presence" -> { attached = true; buildJsonObject {
                    put("type", "presence"); put("session", "Mobile client"); put("client_id", "fabricated"); put("human_nonce", nonce)
                } }
                "send-agent-message" -> {
                    check(attached && params["human_nonce"] == JsonPrimitive(nonce))
                    check(params["to"] == JsonPrimitive("pane") && params["from"] == JsonPrimitive("human"))
                    check(params["text"] == JsonPrimitive("Restored legacy text"))
                    check("reply_to" !in params)
                    sends++; sentParams = params
                    buildJsonObject { put("type", "agent_message_sent"); put("message_id", 42); put("verified_human", true) }
                }
                else -> error("Unexpected verb $verb")
            }
            incoming.send((buildJsonObject { put("id", request.getValue("id")); put("result", result) }.toString() + "\n").encodeToByteArray())
        }
        override suspend fun receive() = incoming.receiveCatching().getOrNull()
        override fun close() { isOpen.value = false; incoming.close() }
    }
}
