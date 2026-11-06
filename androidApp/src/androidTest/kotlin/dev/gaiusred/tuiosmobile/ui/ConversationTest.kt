package dev.gaiusred.tuiosmobile.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import dev.gaiusred.tuiosmobile.connection.ConnectionState
import dev.gaiusred.tuiosmobile.identity.HostId
import dev.gaiusred.tuiosmobile.sessions.SyncProblem
import dev.gaiusred.tuiosmobile.ui.design.MessagesTheme
import dev.gaiusred.tuiosmobile.ui.design.displayTime
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ConversationTest {
    @get:Rule val compose = createComposeRule()
    private fun launch(fixture: SessionFixtures) {
        compose.setContent { MessagesTheme { AppRoot(fixture, listOf(fixture.host), ConnectionState.Disconnected, {}) } }
    }
    @Test fun unsupportedTranscriptStillLinksToMail() {
        launch(SessionFixtures(unsupported = true))
        compose.onNodeWithTag("agent-Build").performClick()
        compose.onNodeWithText("Transcript unavailable").assertIsDisplayed()
        compose.onNodeWithText("Open Mail").performClick()
        compose.onNodeWithTag("tab-Mail").assertIsSelected()
    }
    @Test fun savedRunRemainsReadOnlyAndKeepsItsIdentity() {
        val fixture = SessionFixtures(); launch(fixture)
        compose.onNodeWithTag("sessions-list").performScrollToNode(hasTestTag("saved-run-1"))
        compose.onNodeWithTag("saved-run-1").performClick()
        compose.onNodeWithText("Saved conversation · Read only").assertIsDisplayed()
        compose.onNodeWithText("Earlier saved message from this run.").assertExists()
        compose.onNodeWithText("Load older messages").assertDoesNotExist()
        assertEquals(0, fixture.olderRequests)
    }
    @Test fun unknownEntriesAndToolDetailsRemainReadable() {
        launch(SessionFixtures())
        compose.onNodeWithTag("agent-Build").performClick()
        for (label in listOf("Sessions", "Mail", "Terminal", "Settings")) compose.onNodeWithTag("tab-$label").assertIsDisplayed()
        compose.onNodeWithTag("conversation-list").performScrollToNode(hasText("Other activity"))
        compose.onNodeWithText("Other activity").assertIsDisplayed()
        compose.onNodeWithTag("conversation-list").performScrollToNode(hasText("Layout review · Complete"))
        compose.onNodeWithText("Layout review · Complete").performClick()
        compose.onNodeWithText("Checked layout and navigation.").assertIsDisplayed()
    }
    @Test fun localSearchFiltersListedAgentsAndSavedConversations() {
        launch(SessionFixtures())
        compose.onNodeWithTag("session-search").performTextInput("Research")
        compose.onNodeWithTag("agent-Research").assertIsDisplayed()
        compose.onNodeWithTag("agent-Build").assertDoesNotExist()
        compose.onNodeWithTag("saved-run-1").assertDoesNotExist()
    }

    @Test fun savedRunRestoresTheExactGenerationAfterRecreation() {
        val fixture = SessionFixtures()
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MessagesTheme { AppRoot(fixture, listOf(fixture.host), ConnectionState.Disconnected, {}) } }
        compose.onNodeWithTag("sessions-list").performScrollToNode(hasTestTag("saved-run-1"))
        compose.onNodeWithTag("saved-run-1").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Earlier saved message from this run.").assertExists()
        compose.onNodeWithText("Saved conversation · Read only").assertExists()
    }

    @Test fun preservedConversationDoesNotShowAnotherHostsSyncStatus() {
        val fixture = SessionFixtures()
        val other = fixture.host.copy(id = HostId("other-host"), machine = "Other host")
        val otherTime = Instant.parse("2026-11-05T12:30:00Z")
        compose.setContent { MessagesTheme {
            AppRoot(fixture, listOf(fixture.host, other), ConnectionState.Disconnected, { id ->
                fixture.state.value = fixture.state.value.copy(hostId = id, sessions = emptyList(),
                    lastSyncedAt = otherTime, problem = SyncProblem.SubscriptionUnavailable)
            })
        } }
        compose.onNodeWithTag("agent-Build").performClick()
        compose.onNodeWithTag("tab-Settings").performClick()
        compose.onNodeWithText("Other host").performClick()
        compose.onNodeWithTag("tab-Sessions").performClick()
        compose.onNodeWithText("Studio Mac · Mobile client").assertExists()
        compose.onNodeWithText("Last sync time unavailable").assertExists()
        compose.onNodeWithText("Last synced ${displayTime(otherTime)}").assertDoesNotExist()
        compose.onNodeWithText("Updates paused").assertDoesNotExist()
        compose.onNodeWithText("Offline").assertExists()
    }
}
