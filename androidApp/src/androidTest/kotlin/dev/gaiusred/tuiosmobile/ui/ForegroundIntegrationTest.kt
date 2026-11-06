package dev.gaiusred.tuiosmobile.ui

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import dev.gaiusred.tuiosmobile.attention.AttentionRepository
import dev.gaiusred.tuiosmobile.connection.*
import dev.gaiusred.tuiosmobile.identity.HostId
import dev.gaiusred.tuiosmobile.mail.*
import dev.gaiusred.tuiosmobile.storage.*
import dev.gaiusred.tuiosmobile.sync.ForegroundSync
import dev.gaiusred.tuiosmobile.ui.design.MessagesTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*
import kotlin.time.Instant

class ForegroundIntegrationTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "foreground-integration.db"
    private val host = HostProfile(HostId("fabricated"), "Fixture host", "fixture", "phone", emptyList(), "fixture", "fixture")
    private val mounted = mutableStateOf(true)
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var store: RoomHistoryStore
    private lateinit var connection: ForegroundHostConnection
    private lateinit var sync: ForegroundSync
    private lateinit var mail: MailRepository
    private lateinit var peer: ForegroundPeer
    private val selected = MutableStateFlow<HostId?>(host.id)
    private val now = Instant.parse("2026-11-06T00:00:00Z")
    private val target = MailTarget(host.id, "session-1", "boot")

    @Before fun setup() { context.deleteDatabase(dbName); store = RoomHistoryStore(context, dbName) }
    @After fun cleanup() {
        compose.runOnIdle { mounted.value = false }; compose.waitForIdle()
        if (::peer.isInitialized) peer.mux.close()
        runBlocking { if (::connection.isInitialized) connection.disconnect(); scope.coroutineContext.job.cancelAndJoin() }
        store.close(); context.deleteDatabase(dbName)
    }

    private suspend fun start(count: Int = 1, configure: (ForegroundPeer) -> Unit = {}) {
        peer = ForegroundPeer(scope, count).also(configure); peer.start()
        connection = ForegroundHostConnection(ProxyTransport { peer.mux }, scope)
        sync = ForegroundSync(connection, store, scope, selected) { now }
        mail = MailRepository(connection, store, sync, selected, scope) { now }
        sync.setForeground(true); connection.connect(host)
    }
    private suspend fun waitForMail(text: String) = withTimeout(15_000) {
        mail.observe(target).first { it.messages.any { message -> message.content.text == text } }
    }
    private fun showMail() {
        compose.setContent { if (mounted.value) MessagesTheme {
            val state by connection.state.collectAsState()
            AppRoot(sync, listOf(host), state, {}, mailRepository = mail)
        } }
        compose.onNodeWithTag("tab-Mail").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("mail-thread-1").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("mail-thread-1").performClick()
    }

    @Test fun openThreadRefreshesOnMailEventAndGapWithoutNavigation() {
        runBlocking { start(); waitForMail("First fabricated Mail") }
        showMail()
        compose.onNodeWithText("Release review").assertIsDisplayed()
        peer.mailText = "Arrived while reading"; peer.mailId = 2; peer.event("agent-message", 2)
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Arrived while reading").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Arrived while reading").assertIsDisplayed()
        peer.mailText = "Recovered after gap"; peer.mailId = 3; peer.event("gap", 50)
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Recovered after gap").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Recovered after gap").assertIsDisplayed()
        assertEquals(listOf("hello", "list-verbs", "subscribe"), peer.calls.take(3))
        assertFalse(peer.calls.any { it == "send-agent-message" || it == "respond" })
        assertTrue(peer.violations.toString(), peer.violations.isEmpty())
    }

    @Test fun subjectSurvivesRoomReopenAndOfflineThreadDisplay() {
        runBlocking {
            start(); waitForMail("First fabricated Mail")
            sync.setForeground(false); connection.setForeground(false); scope.coroutineContext.job.cancelAndJoin()
        }
        peer.mux.close(); store.close(); store = RoomHistoryStore(context, dbName)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        connection = ForegroundHostConnection(ProxyTransport { error("Offline reopening cannot connect") }, scope)
        sync = ForegroundSync(connection, store, scope, selected) { now }
        mail = MailRepository(connection, store, sync, selected, scope) { now }
        showMail()
        compose.onNodeWithText("Release review").assertIsDisplayed()
        compose.onNodeWithText("First fabricated Mail").assertIsDisplayed()
        compose.onNodeWithText("Offline · Saved Mail").assertIsDisplayed()
    }

    @Test fun manyTranscriptSessionsLeaveActualMuxCapacityForMailAndHumanQuestionReads() = runBlocking {
        start(20)
        withTimeout(20_000) { store.observe(HistoryQuery(host.id)).first { snapshot ->
            snapshot.checkpoint?.agents?.count { it.availability == TranscriptAvailability.Available } == 20
        } }
        mail.refresh(target); waitForMail("First fabricated Mail")
        val agent = withTimeout(5_000) { sync.state.first { !it.offline } }.sessions.first().agents.single().target
        val attention = AttentionRepository(connection, store, sync, selected, scope) { now }
        attention.refresh(agent)
        assertEquals("Review fixture", attention.observe(agent).first().single().summary)
        assertEquals(20, peer.calls.count { it == "agent-transcript" })
        assertTrue(peer.calls.contains("read-agent-messages"))
        assertTrue(peer.calls.contains("peek-prompt"))
        assertTrue(peer.violations.toString(), peer.violations.isEmpty())
    }

    @Test fun missingTranscriptAndSendSupportKeepMailReadableWithDisabledSend() {
        runBlocking { start { it.verbs -= setOf("agent-transcript", "send-agent-message") }; waitForMail("First fabricated Mail") }
        showMail()
        compose.onNodeWithText("First fabricated Mail").assertIsDisplayed()
        compose.onNodeWithText("This daemon does not support sending Mail.").assertIsDisplayed()
        compose.onNodeWithTag("mail-input").performTextInput("Local draft")
        compose.onNodeWithTag("mail-send").assertIsNotEnabled()
        assertFalse(peer.calls.contains("agent-transcript"))
        assertFalse(peer.calls.contains("send-agent-message"))
        assertTrue(peer.violations.toString(), peer.violations.isEmpty())
    }
}
