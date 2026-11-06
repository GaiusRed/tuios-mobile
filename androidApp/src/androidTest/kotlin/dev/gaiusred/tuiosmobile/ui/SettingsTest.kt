package dev.gaiusred.tuiosmobile.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import dev.gaiusred.tuiosmobile.connection.*
import dev.gaiusred.tuiosmobile.identity.HostId
import dev.gaiusred.tuiosmobile.storage.RetentionPolicy
import dev.gaiusred.tuiosmobile.ui.settings.*
import dev.gaiusred.tuiosmobile.ui.design.MessagesTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.gaiusred.tuiosmobile.identity.RecordKey
import dev.gaiusred.tuiosmobile.storage.*
import dev.gaiusred.tuiosmobile.mail.MailRepository
import kotlin.time.Instant

class SettingsTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val actions = FakeSettings()
    private val mounted = mutableStateOf(true)
    @After fun cleanup() { compose.runOnIdle { mounted.value = false }; compose.waitForIdle(); runBlocking { scope.coroutineContext.job.cancelAndJoin() } }
    private fun open(fontScale: Float = 1f) {
        val fixture = SessionFixtures()
        val model = SettingsViewModel(actions, scope)
        compose.setContent { if (mounted.value) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MessagesTheme { AppRoot(fixture, emptyList(), ConnectionState.Disconnected, {},
                    settingsContent = { SettingsScreen(model) }) }
            }
        } }
        compose.onNodeWithTag("tab-Settings").performClick()
    }
    @Test fun retentionChoicesAndClearRequireDeliberateConfirmation() {
        open()
        capture("settings")
        compose.onNodeWithTag("retention-ThirtyDays").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("retention-SevenDays").performScrollTo().performClick()
        compose.waitUntil { actions.retention.value == RetentionPolicy.SevenDays }
        compose.onNodeWithTag("clear-A").performScrollTo().performClick()
        Assert.assertTrue(actions.cleared.isEmpty())
        compose.onNodeWithTag("clear-warning").assertTextContains("Available remote history can be downloaded again.", substring = true)
        compose.onNodeWithTag("confirm-clear").performClick()
        compose.waitUntil { actions.cleared == listOf(HostId("A")) }
    }
    @Test fun removalExplainsRemoteKeyAndHonorsBothChoices() {
        open()
        compose.onNodeWithTag("remove-A").performScrollTo().performClick()
        capture("removal")
        compose.onNodeWithText("Removing this profile does not revoke its authorized key on your computer.", substring = true).assertExists()
        compose.onNodeWithTag("delete-credentials").performClick()
        compose.onNodeWithTag("delete-cache").performClick()
        compose.onNodeWithTag("confirm-remove").performClick()
        compose.waitUntil { actions.removed != null }
        Assert.assertEquals(Triple(HostId("A"), false, false), actions.removed)
    }
    @Test fun pairingComparisonAndSafeHostRecoveryAreVisible() {
        actions.hostLoadProblem.value = HostLoadProblem.Unavailable
        actions.pairingProgress.value = PairingProgress.AwaitingComparison("565911", "SHA256:phone-example", "SHA256:host-example")
        open()
        compose.onNodeWithTag("retry-hosts").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("pair-host").performScrollTo().performClick()
        compose.onNodeWithText("565911", substring = true).assertExists()
        compose.onNodeWithText("SHA256:phone-example", substring = true).assertExists()
        compose.onNodeWithText("SHA256:host-example", substring = true).assertExists()
        compose.onNodeWithText("Approve manually on your computer only after all values match.", substring = true).assertExists()
        capture("pairing")
    }
    @Test fun largeTextKeepsRetentionAndDestructiveConfirmationReachable() {
        open(fontScale = 2f)
        compose.onNodeWithTag("retention-Forever").performScrollTo().performClick()
        compose.waitUntil { actions.retention.value == RetentionPolicy.Forever }
        compose.onNodeWithTag("remove-all-local").performScrollTo().performClick()
        compose.onNodeWithTag("confirm-bulk").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        listOf("Sessions", "Mail", "Terminal", "Settings").forEach { compose.onNodeWithTag("tab-$it").assertIsDisplayed() }
        compose.onNodeWithTag("tab-Terminal").assertIsNotEnabled()
    }
    @Test fun orphanHistoryCanBeClearedAndResetNeedsConfirmation() {
        actions.hostLoadProblem.value = HostLoadProblem.Unavailable
        open()
        compose.onNodeWithTag("clear-removed").performScrollTo().performClick()
        compose.onNodeWithTag("confirm-clear").performClick()
        compose.waitUntil { actions.cleared == listOf(HostId("removed")) }
        compose.onNodeWithTag("reset-connections").performScrollTo().performClick()
        compose.onNodeWithTag("bulk-warning").assertTextContains("Downloaded history and local credentials are kept.", substring = true)
        Assert.assertFalse(actions.reset)
        compose.onNodeWithTag("confirm-bulk").performClick()
        compose.waitUntil { actions.reset }
    }
    @Test fun removedHostHistoryRemainsReachableWithoutAnSshProfile() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "removed-host-settings-test.db"
        context.deleteDatabase(name)
        val history = RoomHistoryStore(context, name)
        val fixture = SessionFixtures()
        val connection = ForegroundHostConnection(ProxyTransport { error("Retained data cannot connect") }, scope)
        val mail = MailRepository(connection, history, fixture, MutableStateFlow(null), scope) { Instant.parse("2026-11-05T12:00:00Z") }
        try {
            runBlocking {
                history.apply(HistoryBatch(RecordKey(fixture.host.id, "studio", null, "old-boot", null),
                    listOf(CachedEntry("1", CachedContent.Mail("message", "agent", "human", "Retained Mail body", "1",
                        sessionName = "Old session", fromLabel = "Builder"), Instant.parse("2026-11-05T12:00:00Z")))))
            }
            compose.setContent { if (mounted.value) MessagesTheme {
                AppRoot(fixture, emptyList(), ConnectionState.Disconnected, {}, mailRepository = mail,
                    cachedHostIds = listOf(fixture.host.id))
            } }
            compose.onNodeWithTag("sessions-list").performScrollToNode(hasTestTag("saved-run-1"))
            compose.onNodeWithTag("saved-run-1").performClick()
            compose.onNodeWithText("Earlier saved message from this run.").assertExists()
            compose.onNodeWithTag("tab-Mail").performClick()
            compose.waitUntil { compose.onAllNodesWithText("Retained Mail body").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Saved host", substring = true).assertExists()
            compose.onNodeWithText("New message").assertDoesNotExist()
            compose.onNodeWithTag("mail-thread-1").performClick()
            compose.onNodeWithText("Retained Mail body").assertExists()
        } finally {
            compose.runOnIdle { mounted.value = false }; compose.waitForIdle()
            runBlocking { scope.coroutineContext.job.cancelAndJoin() }
            history.close(); context.deleteDatabase(name)
        }
    }
    private class FakeSettings : SettingsActions {
        override val hosts = MutableStateFlow(listOf(HostProfile(HostId("A"), "Studio Mac", "fixture", "Phone", emptyList(), "public", "fake")))
        override val selectedHostId = MutableStateFlow<HostId?>(HostId("A"))
        override val hostLoadProblem = MutableStateFlow<HostLoadProblem?>(null)
        override val connection = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
        override val retention = MutableStateFlow(RetentionPolicy.ThirtyDays)
        override val pairingProgress = MutableStateFlow<PairingProgress>(PairingProgress.Idle)
        override val pairingUncertain = MutableStateFlow(false)
        override val capabilities = MutableStateFlow<CapabilitySummary?>(null)
        override val cachedHostIds = MutableStateFlow(listOf(HostId("A"), HostId("removed")))
        val cleared = mutableListOf<HostId>()
        var removed: Triple<HostId, Boolean, Boolean>? = null
        var reset = false
        override fun selectHost(id: HostId) { selectedHostId.value = id }
        override suspend fun reload() { hostLoadProblem.value = null }
        override suspend fun changeRetention(policy: RetentionPolicy): SettingsResult { retention.value = policy; return SettingsResult.Saved }
        override suspend fun clearHistory(id: HostId): SettingsResult { cleared += id; return SettingsResult.Cleared }
        override suspend fun removeHost(id: HostId, credentials: Boolean, cache: Boolean): SettingsResult { removed = Triple(id, credentials, cache); return SettingsResult.Removed }
        override suspend fun pair(invitation: PairInvitation, repair: HostId?) = SettingsResult.Uncertain
        override suspend fun checkCapabilities() = SettingsResult.Failed
        override suspend fun clearAllHistory() = SettingsResult.Cleared
        override suspend fun resetConnections(): SettingsResult { reset = true; return SettingsResult.Removed }
        override suspend fun removeAllLocalData() = SettingsResult.Removed
    }

    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureSettingsScreenshots") != "true") return
        compose.waitForIdle()
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "screencap -p /sdcard/Pictures/task9-$name.png")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
    }
}
