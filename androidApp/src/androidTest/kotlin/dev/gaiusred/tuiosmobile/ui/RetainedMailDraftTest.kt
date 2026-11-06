package dev.gaiusred.tuiosmobile.ui

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ApplicationProvider
import dev.gaiusred.tuiosmobile.connection.*
import dev.gaiusred.tuiosmobile.mail.MailRepository
import dev.gaiusred.tuiosmobile.storage.*
import dev.gaiusred.tuiosmobile.ui.design.MessagesTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*
import kotlin.time.Instant
import kotlin.time.Duration.Companion.days

/** Cold Room reopen, no saved SSH profile, and no channel allowed for retained local drafts. */
class RetainedMailDraftTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "retained-draft-access-test.db"
    private val fixture = SessionFixtures()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mounted = mutableStateOf(true)
    private lateinit var history: RoomHistoryStore
    private val now = Instant.parse("2026-11-05T12:00:00Z")
    private var channelAttempts = 0
    private val legacy = MailDraft(fixture.host.id, "old-session", "old-agent", "7",
        "A legacy draft kept entirely on this phone.\nIts full second line is retained.\nThe last legacy line remains readable.", replyTo = "9")

    @Before fun setup() { context.deleteDatabase(name); history = RoomHistoryStore(context, name) }
    @After fun cleanup() {
        compose.runOnIdle { mounted.value = false }; compose.waitForIdle()
        runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        history.close(); context.deleteDatabase(name)
    }
    private fun coldOpen(withSavedHost: Boolean = false) {
        history.close(); history = RoomHistoryStore(context, name)
        val connection = object : HostConnection {
            override val state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
            override suspend fun connect(host: HostProfile) = error("Retained draft cannot connect")
            override suspend fun disconnect() = Unit
            override suspend fun openChannel(kind: ChannelKind): ProxyChannel { channelAttempts++; error("Retained draft cannot open a channel") }
        }
        val repo = MailRepository(connection, history, fixture, MutableStateFlow(if (withSavedHost) fixture.host.id else null), scope) { now }
        compose.setContent { if (mounted.value) {
            val cachedHosts by history.observeHosts().collectAsState(emptyList())
            MessagesTheme { AppRoot(fixture, if (withSavedHost) listOf(fixture.host) else emptyList(), ConnectionState.Disconnected, {}, mailRepository = repo,
                cachedHostIds = cachedHosts) }
        } }
        compose.onNodeWithTag("tab-Mail").performClick()
    }
    private fun openDraft(body: String, label: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithText("$label: $body").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("$label: $body").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("saved-draft-body").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("saved-draft-body").assertTextEquals(body)
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("saved-draft-body").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertFalse("The saved body must not be clipped to preview height", layout.didOverflowHeight)
        assertTrue(layout.lineCount >= body.lineSequence().count())
        assertFalse((0 until layout.lineCount).any { layout.isLineEllipsized(it) })
        compose.onNodeWithTag("mail-send").assertDoesNotExist()
        compose.onNodeWithTag("mail-input").assertDoesNotExist()
        compose.onNodeWithText("Use in current session").assertDoesNotExist()
        compose.onNodeWithText("New message").assertDoesNotExist()
        assertEquals(0, channelAttempts)
    }
    @Test fun removedHostWithOnlyLegacyDraftRemainsReadableAfterColdOpen() {
        runBlocking { history.saveDraft(legacy) }
        coldOpen()
        openDraft(legacy.text, "Draft to review")
        compose.onNodeWithText("Legacy draft", substring = true).assertExists()
    }
    @Test fun explicitConversationMailReplacesSavedDraftButTabSwitchKeepsIt() {
        runBlocking {
            history.saveDraft(legacy)
            history.saveCheckpoint(SyncCheckpoint(fixture.host.id, "boot",
                sessions = listOf(CachedSession("studio", "Mobile client", 2, true))))
        }
        coldOpen(withSavedHost = true)
        openDraft(legacy.text, "Draft to review")
        compose.onNodeWithTag("tab-Sessions").performClick()
        compose.onNodeWithTag("tab-Mail").performClick()
        compose.onNodeWithTag("saved-draft-body").assertTextEquals(legacy.text)
        compose.onNodeWithTag("tab-Sessions").performClick()
        compose.onNodeWithTag("agent-Build").performScrollTo().performClick()
        compose.onNode(hasText("Mail") and hasClickAction() and !hasTestTag("tab-Mail")).performClick()
        compose.onNodeWithText("To: Build").assertIsDisplayed()
        compose.onNodeWithTag("saved-draft-body").assertDoesNotExist()
        compose.onNodeWithTag("mail-input").assertIsDisplayed()
        compose.onNodeWithTag("mail-send").assertIsNotEnabled()
        assertEquals(0, channelAttempts)
    }
    @Test fun expiredCheckpointCannotHidePreservedLegacyDraft() {
        runBlocking {
            history.saveCheckpoint(SyncCheckpoint(fixture.host.id, "old-boot",
                sessions = listOf(CachedSession("old-session", "Earlier session", 1, false)),
                receivedSeconds = (now - 31.days).epochSeconds))
            history.saveDraft(legacy)
            history.prune(RetentionPolicy.ThirtyDays, now)
            assertNull(history.observe(HistoryQuery(fixture.host.id)).first().checkpoint)
        }
        coldOpen(); openDraft(legacy.text, "Draft to review")
    }
    @Test fun longBootDraftAndUncertainAttemptAreFullyReadableAfterRemovalAndColdOpen() {
        val longText = (1..14).joinToString("\n") { "Saved line $it: keep every word of this offline draft." }
        val draft = legacy.copy(daemonBootId = "old-boot", threadId = null, replyTo = null, text = longText)
        val uncertain = draft.copy(threadId = "7", replyTo = "9", status = MailDraftStatus.Uncertain,
            text = "$longText\nThe final possibly sent line must remain readable.")
        runBlocking { history.saveDraft(draft); history.saveDraft(uncertain) }
        coldOpen(); openDraft(draft.text, "Draft")
        compose.onNodeWithTag("mail-back").performClick()
        openDraft(uncertain.text, "Possibly sent")
        compose.onNodeWithText("May have been sent.", substring = true).assertExists()
        runBlocking { assertEquals(setOf(draft, uncertain), history.observe(HistoryQuery(fixture.host.id)).first().drafts.toSet()) }
    }
}
