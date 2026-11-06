package dev.gaiusred.tuiosmobile.ui

import android.util.Log
import android.app.Activity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import dev.gaiusred.tuiosmobile.connection.ConnectionState
import dev.gaiusred.tuiosmobile.ui.design.MessagesTheme
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Opt-in display harness for adb screencap. Never installed or seeded into the production source set. */
class VisualCaptureTest {
    @get:Rule val compose = createComposeRule()
    @Test fun capturePopulatedScreens() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("captureScreenshots") == "true")
        val fixture = SessionFixtures()
        val compact = InstrumentationRegistry.getArguments().getString("compactScreenshot") == "true"
        var dark by mutableStateOf(compact)
        var scale by mutableFloatStateOf(if (compact) 2f else 1f)
        compose.setContent {
            val density = LocalDensity.current
            val view = LocalView.current
            SideEffect {
                WindowCompat.getInsetsController((view.context as Activity).window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                MessagesTheme(dark) { AppRoot(fixture, listOf(fixture.host), ConnectionState.Disconnected, {}) }
            }
        }
        if (compact) { capture("sessions-compact-200"); return }
        capture("sessions-light")
        compose.onNodeWithTag("agent-Build").performClick()
        capture("conversation-light")
        compose.onNodeWithTag("conversation-back").performClick()
        compose.runOnIdle { dark = true }
        capture("sessions-dark")
        compose.onNodeWithTag("agent-Build").performClick()
        capture("conversation-dark")
        compose.onNodeWithTag("conversation-back").performClick()
        compose.runOnIdle { scale = 2f }
        capture("sessions-dark-200")
        compose.onNodeWithTag("sessions-list").performScrollToNode(hasTestTag("agent-Build"))
        compose.onNodeWithTag("agent-Build").performClick()
        capture("conversation-dark-200")
        compose.onNodeWithTag("conversation-back").performClick()
        compose.runOnIdle { scale = 1f; dark = false }
        compose.onNodeWithTag("sessions-list").performScrollToIndex(0)
        compose.onNodeWithTag("session-search").performClick()
        capture("sessions-keyboard")
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        for (label in listOf("Sessions", "Mail", "Terminal", "Settings")) compose.onNodeWithTag("tab-$label").assertIsDisplayed()
        Thread.sleep(750)
        Log.i("Task6Capture", "READY $name")
        Thread.sleep(7_000)
    }
}
