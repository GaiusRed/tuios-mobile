package dev.gaiusred.tuiosmobile.ui

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.platform.LocalView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.gaiusred.tuiosmobile.connection.*
import dev.gaiusred.tuiosmobile.identity.*
import dev.gaiusred.tuiosmobile.mail.*
import dev.gaiusred.tuiosmobile.storage.*
import dev.gaiusred.tuiosmobile.ui.design.MessagesTheme
import dev.gaiusred.tuiosmobile.ui.mail.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import kotlin.time.Instant

class MailTest {
    @get:Rule val compose = createComposeRule()
    private val fixture = SessionFixtures(unsupported = true)
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var history: RoomHistoryStore
    private lateinit var repo: MailRepository
    private val name = "mail-ui-test.db"
    private val time = Instant.parse("2026-11-05T08:24:00Z")
    private val key = RecordKey(fixture.host.id, "studio", null, "boot", null)
    private lateinit var hostView: android.view.View
    private val mounted = mutableStateOf(true)
    @Before fun setup() = runBlocking {
        context.deleteDatabase(name)
        history = RoomHistoryStore(context, name)
        history.saveCheckpoint(SyncCheckpoint(fixture.host.id, "boot", sessions = listOf(CachedSession("studio", "Mobile client", 2, true))))
        history.apply(HistoryBatch(key, listOf(
            CachedEntry("1", CachedContent.Mail("message", "pane", "human", "The Mail list is ready for review.", "1",
                sessionName = "Mobile client", fromLabel = "Build", toLabel = "You"), time, time),
            CachedEntry("2", CachedContent.Mail("message", "human", "pane", "Thanks. I will check it on the phone.", "1", "1", true,
                sessionName = "Mobile client", fromLabel = "You", toLabel = "Build", localRead = true), time, time),
        )))
        val connection = object : HostConnection {
            override val state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
            override suspend fun connect(host: HostProfile) = Unit
            override suspend fun disconnect() = Unit
            override suspend fun openChannel(kind: ChannelKind): ProxyChannel = error("Offline test must never open a channel")
        }
        repo = MailRepository(connection, history, fixture, MutableStateFlow(fixture.host.id), scope) { time }
    }
    @After fun cleanup() {
        compose.runOnIdle { mounted.value = false }; compose.waitForIdle()
        runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        history.close(); context.deleteDatabase(name)
    }
    @Suppress("DEPRECATION") // Match MainActivity's adjustResize on the test Activity as well.
    private fun open() {
        compose.setContent {
            if (!mounted.value) return@setContent
            hostView = LocalView.current
            SideEffect { (hostView.context as android.app.Activity).window.setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE) }
            MessagesTheme { AppRoot(fixture, listOf(fixture.host), ConnectionState.Disconnected, {},
                mailRepository = repo) }
        }
        compose.onNodeWithTag("tab-Mail").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("mail-thread-1").fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun localUnreadThreadAndVerifiedOriginRemainAvailableWithoutTranscript() {
        open()
        compose.onNodeWithTag("mail-thread-1").assertExists().performClick()
        compose.onNodeWithText("The Mail list is ready for review.").assertIsDisplayed()
        compose.onNodeWithText("Verified human").assertIsDisplayed()
        compose.onNodeWithText("Offline · Saved Mail").assertIsDisplayed()
        compose.onNodeWithTag("mail-back").performClick()
        compose.waitUntil(5_000) { runBlocking { history.observe(HistoryQuery(fixture.host.id)).first().records
            .filter { it.entry.content is CachedContent.Mail }.all { (it.entry.content as CachedContent.Mail).localRead } } }
        compose.onNodeWithContentDescription("Unread on this phone").assertDoesNotExist()
    }

    @Test fun conversationMailContextDoesNotOverrideLaterMailNavigation() {
        open()
        compose.onNodeWithTag("tab-Sessions").performClick()
        compose.onNodeWithTag("agent-Build").performClick()
        compose.onNodeWithText("Open Mail").performClick()
        compose.onNodeWithText("To: Build").assertIsDisplayed()
        compose.onNodeWithTag("mail-back").performClick()
        compose.onNodeWithTag("mail-list").assertExists()
        compose.onNodeWithTag("tab-Sessions").performClick()
        compose.onNodeWithTag("tab-Mail").performClick()
        compose.onNodeWithTag("mail-list").assertExists()
    }
    @Test fun offlineComposerSavesDraftAndKeepsBottomNavigationVisible() {
        open()
        compose.onNodeWithTag("mail-new-studio-boot").performClick()
        compose.onNodeWithText("Choose recipient").performClick()
        compose.onNodeWithTag("mail-recipient-pane").performClick()
        compose.onNodeWithTag("mail-input").performTextInput("An offline draft")
        compose.onNodeWithTag("mail-send").assertIsNotEnabled()
        compose.onNodeWithTag("tab-Sessions").assertIsDisplayed()
        compose.onNodeWithTag("tab-Settings").assertIsDisplayed()
        compose.waitUntil(5_000) { runBlocking { history.observe(HistoryQuery(fixture.host.id)).first().drafts.any { it.text == "An offline draft" } } }
        compose.onNodeWithTag("mail-back").performClick()
        compose.onNodeWithText("Draft: An offline draft").assertExists().performClick()
        compose.onNodeWithTag("mail-input").assertTextContains("An offline draft")
    }
    @Test fun uncertainAttemptIsLockedAfterReopen() = runBlocking {
        history.saveDraft(MailDraft(fixture.host.id, "studio", "pane", text = "Possibly sent",
            daemonBootId = "boot", status = MailDraftStatus.Uncertain))
        open()
        compose.onNodeWithText("Possibly sent: Possibly sent").performClick()
        compose.onNodeWithText("May have been sent. Check Mail before starting another message.").assertIsDisplayed()
        compose.onNodeWithTag("mail-input").assertIsNotEnabled()
        compose.onNodeWithTag("mail-send").assertIsNotEnabled()
        compose.onNodeWithTag("tab-Sessions").performClick()
        compose.onNodeWithTag("tab-Mail").performClick()
        compose.onNodeWithText("May have been sent. Check Mail before starting another message.").assertIsDisplayed()
        Unit
    }

    @Test fun captureMailScreens() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("captureMailScreenshots") == "true")
        open()
        compose.onNodeWithTag("mail-thread-1").assertExists()
        capture("mail-list")
        compose.onNodeWithTag("mail-thread-1").performClick()
        compose.onNodeWithText("Verified human").assertIsDisplayed()
        capture("mail-thread")
        compose.onNodeWithTag("mail-back").performClick()
        compose.onNodeWithTag("mail-new-studio-boot").performClick()
        compose.onNodeWithText("Choose recipient").performClick()
        compose.onNodeWithTag("mail-recipient-pane").performClick()
        compose.onNodeWithTag("mail-input").performClick().performTextInput("A draft for the next review")
        compose.waitUntil(8_000) { ViewCompat.getRootWindowInsets(hostView)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
        compose.onNodeWithTag("mail-back").assertIsDisplayed()
        capture("mail-keyboard")
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        listOf("Sessions", "Mail", "Terminal", "Settings").forEach { compose.onNodeWithTag("tab-$it").assertIsDisplayed() }
        val capture = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "screencap -p /sdcard/Pictures/task7-$name.png")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(capture).use { it.readBytes() }
    }
}
