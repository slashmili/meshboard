package dev.meshboard.android

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class CanvasTest {
    // Queue UI work: the default unconfined dispatcher can run a remeasure on the
    // session executor when its StateFlow emits while Android is laying out.
    @get:Rule val rule = createAndroidComposeRule<MainActivity>(effectContext = StandardTestDispatcher())
    private fun canvas() = rule.onNodeWithTag("mobile-canvas")
    private fun count(value: Int) {
        rule.waitUntil(5000) { canvas().fetchSemanticsNode().config[SemanticsProperties.StateDescription] == "$value objects" }
    }
    private fun draw() = canvas().performTouchInput { swipe(Offset(width * .25f, height * .3f), Offset(width * .7f, height * .65f), 350) }

    @Test fun drawShapesEraseAndConfirmClear() {
        draw(); count(1)
        rule.onNodeWithTag("mobile-tool-rectangle").performClick(); draw(); count(2)
        rule.onNodeWithTag("mobile-tool-eraser").performClick()
        canvas().performTouchInput { click(Offset(width * .25f, height * .3f)) }; count(0)
        rule.onNodeWithTag("mobile-tool-ellipse").performClick(); draw(); count(1)
        rule.onNodeWithTag("mobile-more").performClick()
        rule.onNodeWithTag("mobile-clear").performClick()
        rule.onNodeWithText("Cancel").performClick(); count(1)
        rule.onNodeWithTag("mobile-more").performClick()
        rule.onNodeWithTag("mobile-clear").performClick()
        rule.onNodeWithTag("mobile-confirm-clear").performClick(); count(0)
    }

    @Test fun pinchCancelsDraftAndDoesNotDrawWhenOneFingerRemains() {
        canvas().performTouchInput {
            down(0, Offset(width * .4f, height * .4f))
            moveBy(0, Offset(10f, 10f))
            down(1, Offset(width * .6f, height * .6f))
            moveBy(0, Offset(-45f, -45f))
            moveBy(1, Offset(45f, 45f))
            up(1)
            moveBy(0, Offset(30f, 30f)); up(0)
        }
        count(0)
        rule.onNodeWithTag("mobile-zoom").assert(!hasText("100%"))
        rule.onNodeWithText("Reset view").performClick()
        rule.onNodeWithTag("mobile-zoom").assertTextEquals("100%")
    }

    @Test fun rotationRetainsDrawingAndViewControlsWork() {
        draw(); count(1)
        rule.activityRule.scenario.recreate()
        count(1)
        rule.onNodeWithTag("mobile-zoom-in").performClick()
        rule.onNodeWithTag("mobile-zoom").assertTextEquals("120%")
        rule.onNodeWithText("Reset view").performClick()
        rule.onNodeWithTag("mobile-zoom").assertTextEquals("100%")
        rule.onNodeWithTag("mobile-more").performClick()
        rule.onNodeWithTag("mobile-help").performClick()
        rule.onNodeWithText("Back to the board").performClick()
        count(1)
    }

    @Test fun sharingControlsValidateInvitesWithoutDiscardingDrawing() {
        draw(); count(1)
        rule.onNodeWithTag("mobile-share").performClick()
        rule.onNodeWithTag("mobile-app-address").assertTextContains(if (BuildConfig.CRDT_PREVIEW) "http://127.0.0.1:5174" else "http://127.0.0.1:5173")
        rule.onNodeWithText("Done").performClick()
        rule.onNodeWithTag("mobile-join").performClick()
        val key = if (BuildConfig.CRDT_PREVIEW) "crdt" else "room"
        rule.onNodeWithTag("mobile-invite-input").performTextInput("https://example.com/#$key=invalid")
        rule.onNodeWithTag("mobile-confirm-join").performClick()
        count(1)
        rule.waitUntil(5000) { rule.onAllNodesWithText("This invite has an invalid room ID.").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("mobile-connection-status").assertTextEquals("Local only")
    }

    @Test fun mismatchedProtocolDoesNotDiscardDrawing() {
        draw(); count(1)
        val other = if (BuildConfig.CRDT_PREVIEW) "room" else "crdt"
        rule.onNodeWithTag("mobile-join").performClick()
        rule.onNodeWithTag("mobile-invite-input").performTextInput("http://127.0.0.1:5174/#$other=12345678-1234-4234-8234-123456789abc")
        rule.onNodeWithTag("mobile-confirm-join").performClick()
        rule.waitUntil(5000) { rule.onAllNodesWithText("This invite belongs to another protocol. Use the matching app mode.").fetchSemanticsNodes().isNotEmpty() }
        count(1)
    }

    @Test fun compactHeaderKeepsSharingVisibleAndSecondaryActionsInMenu() {
        rule.onNodeWithText(if (BuildConfig.CRDT_PREVIEW) "Meshboard · CRDT preview · local only" else "Meshboard").assertIsDisplayed()
        rule.onNodeWithContentDescription("Meshboard logo").assertIsDisplayed()
        rule.onNodeWithTag("mobile-header").assertHeightIsEqualTo(52.dp)
        rule.onNodeWithTag("mobile-join").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        rule.onNodeWithTag("mobile-share").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        rule.onNodeWithTag("mobile-help").assertDoesNotExist()
        rule.onNodeWithTag("mobile-clear").assertDoesNotExist()
        rule.onNodeWithTag("mobile-more").assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithTag("mobile-clear").assertIsDisplayed().assertIsNotEnabled()
        rule.onNodeWithTag("mobile-help").performClick()
        rule.onNodeWithText("Back to the board").performClick()
        canvas().assertIsDisplayed()
    }
}
