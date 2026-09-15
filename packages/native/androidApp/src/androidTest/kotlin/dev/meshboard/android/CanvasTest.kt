package dev.meshboard.android

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

class CanvasTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private fun canvas() = rule.onNodeWithTag("mobile-canvas")
    private fun count(value: Int) = canvas().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "$value objects"))
    private fun draw() = canvas().performTouchInput { swipe(Offset(width * .25f, height * .3f), Offset(width * .7f, height * .65f), 350) }

    @Test fun drawShapesEraseAndConfirmClear() {
        draw(); count(1)
        rule.onNodeWithTag("mobile-tool-rectangle").performClick(); draw(); count(2)
        rule.onNodeWithTag("mobile-tool-eraser").performClick()
        canvas().performTouchInput { click(Offset(width * .25f, height * .3f)) }; count(0)
        rule.onNodeWithTag("mobile-tool-ellipse").performClick(); draw(); count(1)
        rule.onNodeWithTag("mobile-clear").performClick()
        rule.onNodeWithText("Cancel").performClick(); count(1)
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
        rule.onNodeWithTag("mobile-help").performClick()
        rule.onNodeWithText("Back to the board").performClick()
        count(1)
    }
}
