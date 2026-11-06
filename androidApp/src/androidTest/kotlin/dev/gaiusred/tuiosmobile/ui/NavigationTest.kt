package dev.gaiusred.tuiosmobile.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.gaiusred.tuiosmobile.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class NavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun coldLaunchSelectsSessions() {
        compose.onNodeWithTag("tab-Sessions").assertIsSelected()
        compose.onNodeWithTag("sessions-title").assertTextEquals("Sessions")
    }

    @Test fun bottomOrderIsSessionsMailTerminalSettings() {
        val labels = listOf("Sessions", "Mail", "Terminal", "Settings")
        val positions = labels.map { compose.onNodeWithTag("tab-$it").fetchSemanticsNode().boundsInRoot.left }
        assertTrue(positions.zipWithNext().all { (left, right) -> left < right })
    }

    @Test fun terminalIsDisabledForTouchAndAccessibility() {
        compose.onNodeWithTag("tab-Terminal").assertIsNotEnabled()
        compose.onNodeWithTag("tab-Terminal").performTouchInput { click() }
        compose.onNodeWithTag("tab-Sessions").assertIsSelected()
        compose.onNodeWithContentDescription("Terminal, coming later").assertIsNotEnabled()
    }

    @Test fun temporaryBackgroundPreservesRoute() {
        compose.onNodeWithTag("tab-Mail").performClick()
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        compose.onNodeWithTag("tab-Mail").assertIsSelected()
    }

    @Test fun recreationPreservesSelectedDestination() {
        compose.onNodeWithTag("tab-Settings").performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("tab-Settings").assertIsSelected()
    }

    @Test fun systemBackReturnsToSessions() {
        compose.onNodeWithTag("tab-Mail").performClick()
        compose.onNodeWithTag("tab-Mail").assertIsSelected()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("tab-Sessions").assertIsSelected()
    }
}
