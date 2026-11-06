package dev.gaiusred.tuiosmobile.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.gaiusred.tuiosmobile.connection.ConnectionState
import dev.gaiusred.tuiosmobile.ui.design.MessagesTheme
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class LargeTextTest {
    @get:Rule val compose = createComposeRule()
    @Test fun largeTextKeepsBottomControlsUsable() {
        val fixture = SessionFixtures()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                MessagesTheme { AppRoot(fixture, listOf(fixture.host), ConnectionState.Disconnected, {}) }
            }
        }
        for (label in listOf("Sessions", "Mail", "Terminal", "Settings")) {
            compose.onNodeWithTag("tab-$label").assertIsDisplayed().assertTextContains(label)
        }
        compose.onNodeWithTag("tab-Settings").performClick().assertIsSelected()
        compose.onNodeWithTag("tab-Mail").performClick().assertIsSelected()
        compose.onNodeWithTag("tab-Sessions").performClick().assertIsSelected()
        compose.onNodeWithTag("tab-Terminal").assertIsNotEnabled()
    }

    @Test fun compactLargeTextKeepsEveryLabelVisible() {
        val fixture = SessionFixtures()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                Box(Modifier.width(320.dp)) {
                    MessagesTheme { AppRoot(fixture, listOf(fixture.host), ConnectionState.Disconnected, {}) }
                }
            }
        }
        for (label in listOf("Sessions", "Mail", "Terminal", "Settings")) {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag("tab-$label")), useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            assertFalse("$label must fit vertically", layout.didOverflowHeight)
            assertEquals("Every character of $label must remain visible", label.length,
                layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
            // TextLayoutResult stores integer bounds, while paragraph extents use fractional pixels.
            for (line in 0 until layout.lineCount) assertTrue("$label must fit at 200%: " +
                "lineRight=${layout.getLineRight(line)}, width=${layout.size.width}",
                layout.getLineRight(line) <= layout.size.width + 1f)
        }
    }
}
