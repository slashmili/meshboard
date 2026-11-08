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

    @Test fun undoRedoAndClearRestoreThroughProductionController() {
        rule.onNodeWithTag("mobile-undo").assertIsNotEnabled()
        draw(); count(1)
        rule.onNodeWithTag("mobile-undo").performClick(); count(0)
        rule.onNodeWithTag("mobile-redo").performClick(); count(1)
        rule.onNodeWithTag("mobile-tool-rectangle").performClick(); draw(); count(2)
        rule.onNodeWithTag("mobile-more").performClick()
        rule.onNodeWithTag("mobile-clear").performClick()
        rule.onNodeWithTag("mobile-confirm-clear").performClick(); count(0)
        rule.onNodeWithTag("mobile-undo").performClick(); count(2)
        rule.onNodeWithTag("mobile-redo").performClick(); count(0)
    }

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
        for (tool in listOf("pen", "text", "sticky", "arrow")) {
            rule.onNodeWithTag("mobile-tool-$tool").performClick()
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
            rule.onNodeWithTag("text-editor").assertDoesNotExist()
        }
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
        rule.onNodeWithTag("mobile-app-address").assertTextContains("http://127.0.0.1:5173")
        rule.onNodeWithText("Done").performClick()
        rule.onNodeWithTag("mobile-join").performClick()
        val key = "crdt"
        rule.onNodeWithTag("mobile-invite-input").performTextInput("https://example.com/#$key=invalid")
        rule.onNodeWithTag("mobile-confirm-join").performClick()
        count(1)
        rule.waitUntil(5000) { rule.onAllNodesWithText("Paste the full encrypted invite, including its key.").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("mobile-connection-status").assertTextEquals("Local only")
    }

    @Test fun mismatchedProtocolDoesNotDiscardDrawing() {
        draw(); count(1)
        val other = "room"
        rule.onNodeWithTag("mobile-join").performClick()
        rule.onNodeWithTag("mobile-invite-input").performTextInput("http://127.0.0.1:5174/#$other=12345678-1234-4234-8234-123456789abc")
        rule.onNodeWithTag("mobile-confirm-join").performClick()
        rule.waitUntil(5000) { rule.onAllNodesWithText("Paste the full encrypted invite, including its key.").fetchSemanticsNodes().isNotEmpty() }
        count(1)
    }

    @Test fun compactHeaderKeepsSharingVisibleAndSecondaryActionsInMenu() {
        rule.onNodeWithText("Meshboard").assertIsDisplayed()
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
    @Test fun appearanceOverrideSurvivesActivityRecreation() {
        draw(); count(1)
        rule.onNodeWithTag("mobile-more").performClick()
        rule.onNodeWithText("Appearance").performClick()
        rule.onNodeWithText("Dark").performClick()
        rule.onNodeWithText("Done").performClick()
        rule.activityRule.scenario.recreate()
        count(1)
        rule.onNodeWithTag("mobile-more").performClick()
        rule.onNodeWithText("Appearance").performClick()
        rule.onNodeWithText("Dark").assertIsSelected()
        rule.onNodeWithText("System").performClick()
        rule.onNodeWithText("System").assertIsSelected()
    }

    @Test fun touchNotesCanBeEditedAndErased() {
        rule.onNodeWithTag("mobile-tool-sticky").performClick()
        canvas().performTouchInput { click(Offset(250f, 250f)) }
        rule.onNodeWithTag("text-editor").performTextInput("Android note")
        rule.onNodeWithText("Save text").performClick(); count(1)
        canvas().performTouchInput { click(Offset(270f, 270f)) }
        rule.onNodeWithTag("text-editor").assertTextContains("Android note")
        rule.onNodeWithTag("text-editor").performTextReplacement("Edited Android note")
        rule.onNodeWithText("Save text").performClick(); count(1)
        rule.onNodeWithTag("mobile-tool-eraser").performClick()
        canvas().performTouchInput { click(Offset(270f, 270f)) }; count(0)
        rule.onNodeWithTag("mobile-tool-text").performClick()
        canvas().performTouchInput { click(Offset(250f, 250f)) }
        rule.onNodeWithTag("text-editor").performTextInput("Cancel text")
        rule.onNodeWithText("Cancel").performClick(); count(0)
    }

}
