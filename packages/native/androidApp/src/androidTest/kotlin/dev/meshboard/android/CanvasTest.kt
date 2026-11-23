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
        try { rule.waitUntil(5000) { canvas().fetchSemanticsNode().config[SemanticsProperties.StateDescription] == "$value objects" } }
        catch (error: Throwable) { throw AssertionError("Expected $value objects; actual: ${canvas().fetchSemanticsNode().config[SemanticsProperties.StateDescription]}", error) }
    }
    private fun draw() = canvas().performTouchInput { swipe(Offset(width * .25f, height * .3f), Offset(width * .7f, height * .65f), 350) }

    private fun mobileTool(wire: String) {
        if (rule.onAllNodesWithTag("mobile-tool-$wire").fetchSemanticsNodes().isEmpty()) {
            val opener = when {
                rule.onAllNodesWithTag("mobile-tools").fetchSemanticsNodes().isNotEmpty() -> "mobile-tools"
                wire in listOf("rectangle", "ellipse", "line", "arrow") -> "mobile-shapes"
                else -> "mobile-insert"
            }
            rule.onNodeWithTag(opener).performClick()
        }
        rule.onNodeWithTag("mobile-tool-$wire").performScrollToIfPossible().performClick()
    }
    private fun SemanticsNodeInteraction.performScrollToIfPossible(): SemanticsNodeInteraction {
        // Dock buttons are no longer in a scrolling row; only tool sheets scroll.
        if (rule.onAllNodesWithTag("tablet-scrim").fetchSemanticsNodes().isNotEmpty()) performScrollTo()
        return this
    }
    private fun toolProperties() {
        rule.onNodeWithTag("mobile-more").performClick()
        rule.onNodeWithTag("mobile-tool-properties").performScrollTo().performClick()
    }
    private fun selectionCount(value: Int) {
        toolProperties()
        rule.onNodeWithTag("mobile-selection-count", useUnmergedTree = true).assertTextEquals("$value selected")
        rule.onNodeWithText("Done").performClick()
    }
    @Test fun toolbarBoundaryCancelsCapturedGestureUntilAllPointersLift() {
        for (tool in listOf("pen", "rectangle", "arrow", "text", "sticky")) {
            mobileTool(tool)
            canvas().performTouchInput {
                down(Offset(200f, 100f)); moveTo(Offset(250f, 120f))
                moveTo(Offset(250f, -20f)); moveTo(Offset(200f, 100f)); up()
            }
            count(0)
            rule.onNodeWithTag("text-editor").assertDoesNotExist()
        }
        rule.onNodeWithTag("mobile-tool-pen").performTouchInput {
            down(center); moveTo(Offset(center.x, height + 200f)); up()
        }
        count(0)
        mobileTool("pen")
        draw(); count(1)
    }

    @Test fun connectorEndpointsCanBeMovedWithoutDuplicateArrows() {
        mobileTool("rectangle")
        canvas().performTouchInput { down(Offset(width * .35f,height * .3f)); moveTo(Offset(width * .45f,height * .45f)); up() }; count(1)
        mobileTool("arrow")
        canvas().performTouchInput { down(Offset(width * .45f,height * .375f)); moveTo(Offset(width * .65f,height * .375f)); up() }; count(2)
        canvas().performTouchInput { down(Offset(width * .45f,height * .375f)); moveTo(Offset(width * .55f,height * .6f)); up() }; count(2)
        rule.onNodeWithTag("mobile-undo").performClick(); count(2)
        rule.onNodeWithTag("mobile-redo").performClick(); count(2)
    }

    @Test fun imagePickerImportsThroughProductionController() {
        androidx.test.espresso.intent.Intents.init()
        try {
            val uri = android.net.Uri.parse("android.resource://dev.meshboard.android.test/raw/image")
            androidx.test.espresso.intent.Intents.intending(
                androidx.test.espresso.intent.matcher.IntentMatchers.hasAction(android.content.Intent.ACTION_OPEN_DOCUMENT)
            ).respondWith(android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_OK, android.content.Intent().setData(uri)))
            rule.onNodeWithTag("mobile-more").performClick()
            rule.onNodeWithTag("insert-image").performClick(); count(1)
            rule.onNodeWithTag("mobile-undo").performClick(); count(0)
            rule.onNodeWithTag("mobile-redo").performClick(); count(1)
        } finally { androidx.test.espresso.intent.Intents.release() }
    }

    @Test fun undoRedoAndClearRestoreThroughProductionController() {
        rule.onNodeWithTag("mobile-undo").assertIsNotEnabled()
        draw(); count(1)
        rule.onNodeWithTag("mobile-undo").performClick(); count(0)
        rule.onNodeWithTag("mobile-redo").performClick(); count(1)
        mobileTool("rectangle"); draw(); count(2)
        rule.onNodeWithTag("mobile-more").performClick()
        rule.onNodeWithTag("mobile-clear").performClick()
        rule.onNodeWithTag("mobile-confirm-clear").performClick(); count(0)
        rule.onNodeWithTag("mobile-undo").performClick(); count(2)
        rule.onNodeWithTag("mobile-redo").performClick(); count(0)
    }

    @Test fun drawShapesEraseAndConfirmClear() {
        draw(); count(1)
        mobileTool("rectangle"); draw(); count(2)
        mobileTool("eraser")
        canvas().performTouchInput { click(Offset(width * .25f, height * .3f)) }; count(0)
        mobileTool("ellipse"); draw(); count(1)
        rule.onNodeWithTag("mobile-more").performClick()
        rule.onNodeWithTag("mobile-clear").performClick()
        rule.onNodeWithText("Cancel").performClick(); count(1)
        rule.onNodeWithTag("mobile-more").performClick()
        rule.onNodeWithTag("mobile-clear").performClick()
        rule.onNodeWithTag("mobile-confirm-clear").performClick(); count(0)
    }

    @Test fun pinchCancelsDraftAndDoesNotDrawWhenOneFingerRemains() {
        for (tool in listOf("pen", "text", "sticky", "arrow")) {
            mobileTool(tool)
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
            rule.onNodeWithTag("mobile-zoom").performClick()
            rule.onNodeWithText("Reset view").performClick()
            rule.onNodeWithTag("mobile-zoom").assertTextEquals("100%")
            rule.onNodeWithTag("text-editor").assertDoesNotExist()
        }
    }

    @Test fun rotationRetainsDrawingAndViewControlsWork() {
        draw(); count(1)
        rule.onNodeWithTag("mobile-zoom-in").performClick()
        rule.onNodeWithTag("mobile-zoom").assertTextEquals("120%")
        rule.activityRule.scenario.recreate()
        count(1)
        rule.onNodeWithTag("mobile-zoom").assertTextEquals("120%")
        rule.onNodeWithTag("mobile-zoom").performClick()
            rule.onNodeWithText("Reset view").performClick()
        rule.onNodeWithTag("mobile-zoom").assertTextEquals("100%")
        rule.onNodeWithTag("mobile-more").performClick()
        rule.onNodeWithTag("mobile-help").performClick()
        rule.onNodeWithText("Back to the board").performClick()
        count(1)
    }

    @Test fun everyToolHasNoFloatingSummary() {
        for (tool in listOf("select","pen","eraser","rectangle","ellipse","arrow","line","text","sticky","hand")) {
            mobileTool(tool)
            rule.onNodeWithTag("mobile-width").assertDoesNotExist()
        }
    }

    @Test fun ellipsePropertiesCanBeChangedAndDismissed() {
        mobileTool("ellipse")
        toolProperties()
        rule.onNodeWithText("Ellipse", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithContentDescription("Fern stroke").performScrollTo().performClick()
        rule.onNodeWithText("Done").performClick()
        rule.onNodeWithTag("tablet-panel-properties").assertDoesNotExist()
        rule.onNodeWithTag("mobile-width").assertDoesNotExist()
        draw(); count(1)
    }

    @Test fun androidBackDismissesPanelsThenClearsSelectionWithoutLeavingTheBoard() {
        mobileTool("rectangle"); draw(); count(1)
        mobileTool("select")
        canvas().performTouchInput { click(Offset(width * .475f, height * .475f)) }
        selectionCount(1)
        rule.activityRule.scenario.recreate()
        selectionCount(1)
        toolProperties()
        androidx.test.espresso.Espresso.pressBack()
        rule.onNodeWithTag("tablet-panel-properties").assertDoesNotExist()
        selectionCount(1)
        androidx.test.espresso.Espresso.pressBack()
        selectionCount(0)
        canvas().assertIsDisplayed(); count(1)
    }

    @Test fun systemShareRequiresExplicitActionAndOpensTheAndroidChooser() {
        androidx.test.espresso.intent.Intents.init()
        try {
            val chooser = androidx.test.espresso.intent.matcher.IntentMatchers.hasAction(android.content.Intent.ACTION_CHOOSER)
            androidx.test.espresso.intent.Intents.intending(chooser).respondWith(android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED, null))
            rule.onNodeWithTag("mobile-share").performClick()
            rule.onNodeWithTag("mobile-create-invite").performClick()
            rule.waitUntil(5000) { rule.onAllNodesWithTag("mobile-system-share").fetchSemanticsNodes().isNotEmpty() }
            org.junit.Assert.assertTrue(androidx.test.espresso.intent.Intents.getIntents().none { it.action == android.content.Intent.ACTION_CHOOSER })
            rule.onNodeWithTag("mobile-system-share").performScrollTo().performClick()
            androidx.test.espresso.intent.Intents.intended(chooser)
        } finally { androidx.test.espresso.intent.Intents.release() }
    }

    @Test fun sharingControlsValidateInvitesWithoutDiscardingDrawing() {
        draw(); count(1)
        rule.onNodeWithTag("mobile-share").performClick()
        rule.onNodeWithTag("mobile-app-address").assertTextContains("http://127.0.0.1:5173")
        rule.onNodeWithText("Done").performClick()
        rule.onNodeWithTag("mobile-more").performClick()
        rule.onNodeWithTag("mobile-join").performClick()
        val key = "crdt"
        rule.onNodeWithTag("mobile-invite-input").performTextInput("https://example.com/#$key=invalid")
        rule.onNodeWithTag("mobile-confirm-join").performClick()
        count(1)
        rule.waitUntil(5000) { rule.onAllNodesWithText("Paste the full encrypted invite, including its key.").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("mobile-connection-status", useUnmergedTree = true).assertTextEquals("Local only")
    }

    @Test fun mismatchedProtocolDoesNotDiscardDrawing() {
        draw(); count(1)
        val other = "room"
        rule.onNodeWithTag("mobile-more").performClick()
        rule.onNodeWithTag("mobile-join").performClick()
        rule.onNodeWithTag("mobile-invite-input").performTextInput("http://127.0.0.1:5174/#$other=12345678-1234-4234-8234-123456789abc")
        rule.onNodeWithTag("mobile-confirm-join").performClick()
        rule.waitUntil(5000) { rule.onAllNodesWithText("Paste the full encrypted invite, including its key.").fetchSemanticsNodes().isNotEmpty() }
        count(1)
    }

    @Test fun compactHeaderKeepsSharingVisibleAndSecondaryActionsInMenu() {
        rule.onNodeWithText("Meshboard").assertIsDisplayed()
        rule.onNodeWithContentDescription("Meshboard logo").assertIsDisplayed()
        rule.onNodeWithTag("mobile-header").assertHeightIsEqualTo(56.dp)
        rule.onNodeWithTag("mobile-join").assertDoesNotExist()
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
        rule.onNodeWithText("Settings").performClick()
        rule.onNodeWithText("Dark").performClick()
        rule.onNodeWithText("Done").performClick()
        rule.activityRule.scenario.recreate()
        count(1)
        rule.onNodeWithTag("mobile-more").performClick()
        rule.onNodeWithText("Settings").performClick()
        rule.onNodeWithText("Dark").assertIsSelected()
        rule.onNodeWithText("System").performClick()
        rule.onNodeWithText("System").assertIsSelected()
    }

    @Test fun touchNotesCanBeEditedAndErased() {
        mobileTool("sticky")
        canvas().performTouchInput { click(Offset(250f, 250f)) }
        rule.onNodeWithTag("text-editor").performTextInput("Android note")
        rule.onNodeWithText("Save text").performClick(); count(1)
        canvas().performTouchInput { click(Offset(270f, 270f)) }
        rule.onNodeWithTag("text-editor").assertTextContains("Android note")
        rule.onNodeWithTag("text-editor").performTextReplacement("Edited Android note")
        rule.onNodeWithText("Save text").performClick(); count(1)
        mobileTool("eraser")
        canvas().performTouchInput { click(Offset(270f, 270f)) }; count(0)
        mobileTool("text")
        canvas().performTouchInput { click(Offset(250f, 250f)) }
        rule.onNodeWithTag("text-editor").performTextInput("Cancel text")
        rule.onNodeWithText("Cancel").performClick(); count(0)
    }

}
