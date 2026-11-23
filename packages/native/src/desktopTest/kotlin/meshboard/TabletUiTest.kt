@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package meshboard

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Rule
import org.junit.Test
import kotlin.test.*

class TabletUiTest {
    @get:Rule val rule = createComposeRule()
    private var next = 0
    private val board = LocalBoardController { "tablet-${++next}" }
    @After fun close() = board.close()
    private fun canvas() = rule.onNodeWithTag("mobile-canvas")
    private fun draw() = canvas().performTouchInput { down(Offset(350f,250f)); moveTo(Offset(450f,300f)); up() }
    private fun settings() { rule.onNodeWithTag("mobile-more").performClick(); rule.onNodeWithText("Settings").performClick() }
    private fun toolProperties() {
        rule.onNodeWithTag("mobile-more").performClick()
        rule.onNodeWithTag("mobile-tool-properties").performScrollTo().performClick()
    }
    private fun selectionCount(value: Int) {
        toolProperties()
        rule.onNodeWithTag("mobile-selection-count", useUnmergedTree = true).assertTextEquals("$value selected")
        rule.onNodeWithText("Done").performClick()
    }
    @Test fun wideDockCapturesInputAndModalDismissalNeverDraws() {
        rule.setContent { Box(Modifier.requiredSize(1024.dp,768.dp)) { MobileBoardApp(board) } }
        rule.onNodeWithTag("tablet-side-dock").assertIsDisplayed().assertWidthIsEqualTo(64.dp).assertHeightIsEqualTo(456.dp)
        val canvasBounds = canvas().fetchSemanticsNode().boundsInRoot
        val dock = rule.onNodeWithTag("tablet-side-dock").fetchSemanticsNode().boundsInRoot
        val inside = Offset(dock.center.x - canvasBounds.left, dock.center.y - canvasBounds.top)
        canvas().performTouchInput { down(Offset(350f,250f)); moveTo(inside); moveTo(Offset(450f,300f)); up() }
        rule.runOnIdle { assertTrue(board.state.value.elements.isEmpty()) }
        rule.onNodeWithTag("tablet-side-dock").performTouchInput { down(Offset(2f,2f)); moveTo(Offset(350f,250f)); up() }
        rule.runOnIdle { assertTrue(board.state.value.elements.isEmpty()) }
        rule.onNodeWithTag("mobile-shapes").performClick()
        rule.onNodeWithTag("mobile-tool-ellipse").performScrollTo().performClick()
        draw()
        rule.runOnIdle { assertEquals("ellipse",board.state.value.elements.single().type) }
        toolProperties()
        canvas().performTouchInput { down(Offset(850f,300f)); moveTo(Offset(900f,350f)); up() }
        rule.runOnIdle { assertEquals(1,board.state.value.elements.size) }
        rule.onNodeWithTag("tablet-panel-properties").assertDoesNotExist()
        rule.onNodeWithTag("mobile-tool-pen").performClick(); draw()
        rule.runOnIdle { assertEquals(2,board.state.value.elements.size) }
    }

    @Test fun resizedTabletGroupsToolsAndReflowPreservesBoardAndViewportCenter() {
        var width by mutableStateOf(1024)
        var height by mutableStateOf(768)
        board.put(BoardElement("center","rectangle","#293b36",3.0,listOf(Point(462.0,306.0), Point(562.0,406.0))))
        rule.setContent { Box(Modifier.requiredSize(width.dp,height.dp)) { MobileBoardApp(board) } }
        rule.onNodeWithTag("mobile-zoom-in").performClick()
        rule.onNodeWithTag("mobile-tool-select").performClick()
        canvas().performTouchInput { click(Offset(512f,356f)) }
        selectionCount(1)
        val before = board.state.value.elements
        rule.runOnIdle { width = 540; height = 720 }
        rule.onNodeWithTag("tablet-side-dock").assertDoesNotExist()
        rule.onNodeWithTag("tablet-bottom-dock").assertIsDisplayed()
        rule.onNodeWithTag("mobile-zoom").assertTextEquals("120%")
        selectionCount(1)
        rule.onNodeWithTag("mobile-insert").performClick()
        rule.onNodeWithTag("mobile-tool-sticky").performClick()
        rule.onNodeWithTag("mobile-insert").assertIsSelected()
        rule.runOnIdle { assertEquals(before,board.state.value.elements) }
        // The rectangle originally centered in the canvas remains centered after resize.
        val image = canvas().captureToImage().toAwtImage()
        val centerX = image.width / 2
        val expectedEdge = image.height / 2 - 60
        assertTrue((image.getRGB(centerX,expectedEdge) and 0xffffff) != 0xf8f9f6)
        rule.runOnIdle { width = 1024; height = 768 }
        rule.onNodeWithTag("tablet-side-dock").assertIsDisplayed()
        rule.onNodeWithTag("mobile-tool-sticky").assertIsSelected()
    }

    @Test fun objectListSelectsMovesAndDeletesWithoutCanvasGestures() {
        var undoCalls = 0
        val controls = object : DrawingController by board { override fun undo() { undoCalls++; board.undo() } }
        board.put(textElement("note","sticky",Point(350.0,250.0),"#293b36","Review this"))
        rule.setContent { Box(Modifier.requiredSize(1024.dp,768.dp)) { MobileBoardApp(controls) } }
        rule.onNodeWithTag("mobile-tool-select").performClick()
        rule.onNodeWithTag("mobile-more").performClick()
        rule.onNodeWithTag("mobile-objects").performScrollTo().performClick()
        rule.onNodeWithText("1. sticky: Review this").performClick().assertIsSelected()
        rule.onNodeWithText("Move right").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(360.0,board.state.value.elements.single().points.first().x) }
        rule.onNodeWithTag("mobile-delete-selection").performScrollTo().performClick()
        rule.runOnIdle { assertTrue(board.state.value.elements.isEmpty()) }
        rule.onNodeWithText("Done").performClick()
        rule.onNodeWithTag("tablet-panel-objects").assertDoesNotExist()
        rule.onNodeWithTag("mobile-undo").assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals(1,undoCalls,"Undo must activate once"); assertEquals("Review this",board.state.value.elements.single().text) }
    }

    @Test fun fingerNavigationAndKeyboardCommandsKeepDrawingIntentExplicit() {
        rule.setContent { Box(Modifier.requiredSize(1024.dp,768.dp)) { MobileBoardApp(board) } }
        settings()
        rule.onNodeWithTag("mobile-finger-drawing").performScrollTo().performClick()
        rule.onNodeWithText("Done").performClick()
        draw()
        rule.runOnIdle { assertTrue(board.state.value.elements.isEmpty()) }
        canvas().performMouseInput { moveTo(Offset(350f,250f)); press(); moveTo(Offset(450f,300f)); release() }
        rule.runOnIdle { assertEquals("pen",board.state.value.elements.single().type) }
        canvas().performKeyInput { keyDown(Key.CtrlLeft); pressKey(Key.Z); keyUp(Key.CtrlLeft) }
        rule.runOnIdle { assertTrue(board.state.value.elements.isEmpty()) }
        canvas().performKeyInput { keyDown(Key.CtrlLeft); keyDown(Key.ShiftLeft); pressKey(Key.Z); keyUp(Key.ShiftLeft); keyUp(Key.CtrlLeft) }
        rule.runOnIdle { assertEquals(1,board.state.value.elements.size) }
        canvas().performKeyInput { pressKey(Key.T) }
        canvas().performMouseInput { click(Offset(550f,400f)) }
        rule.onNodeWithTag("text-editor").performTextInput("PEN text")
        rule.onNodeWithText("Save text").performClick()
        rule.runOnIdle { assertEquals("PEN text",board.state.value.elements.last().text) }
        rule.onNodeWithTag("mobile-tool-text").assertIsSelected()
    }

    @Test fun fitContentRevealsOffscreenObjectsWithoutChangingTheDocument() {
        board.put(BoardElement("far","rectangle","#293b36",3.0,listOf(Point(2400.0,1800.0),Point(2600.0,2000.0))))
        rule.setContent { Box(Modifier.requiredSize(1024.dp,768.dp)) { MobileBoardApp(board) } }
        val before = board.state.value.elements
        rule.onNodeWithTag("mobile-zoom").performClick()
        rule.onNodeWithText("Fit content").performClick()
        rule.onNodeWithTag("tablet-panel-view").assertDoesNotExist()
        rule.onNodeWithTag("mobile-zoom").assert(!hasText("100%"))
        rule.onNodeWithTag("mobile-tool-select").performClick()
        canvas().performTouchInput { click(Offset(512f,308f)) }
        selectionCount(1)
        rule.runOnIdle { assertEquals(before,board.state.value.elements) }
    }

    @Test fun systemShareIsOnlyInvokedByAnExplicitShareAction() {
        val invitation = "https://example.com/#crdt=123e4567-e89b-42d3-a456-426614174000&key=synthetic-test-value"
        val state = MutableStateFlow(BoardState(invite = invitation, signaling = true))
        val controller = object : BoardController, DrawingController by board {
            override val state = state
            override fun share(origin: String) {}
            override fun join(invite: String) {}
            override fun retry() {}
            override fun leave() {}
        }
        var shares = 0
        var shared = ""
        rule.setContent { Box(Modifier.requiredSize(1024.dp,768.dp)) { MobileBoardApp(controller, shareInvite = { shares++; shared = it }, qrImage = { ImageBitmap(1,1) }) } }
        rule.runOnIdle { assertEquals(0,shares) }
        rule.onNodeWithTag("mobile-share").performClick()
        rule.runOnIdle { assertEquals(0,shares) }
        rule.onNodeWithTag("mobile-system-share").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(1,shares); assertEquals(invitation,shared) }
    }

    @Test fun incomingObjectsDoNotCancelAnActiveSelectionMove() {
        board.put(BoardElement("local","rectangle","#293b36",3.0,listOf(Point(350.0,200.0),Point(450.0,300.0))))
        rule.setContent { Box(Modifier.requiredSize(1024.dp,768.dp)) { MobileBoardApp(board) } }
        rule.onNodeWithTag("mobile-tool-select").performClick()
        canvas().performTouchInput { down(Offset(400f,230f)); moveTo(Offset(450f,250f)) }
        val incoming = BoardElement("incoming","rectangle","#4878c8",3.0,listOf(Point(650.0,200.0),Point(750.0,300.0)))
        rule.runOnIdle { board.put(incoming) }
        canvas().performTouchInput { moveTo(Offset(500f,280f)); up() }
        rule.runOnIdle {
            assertEquals(Point(450.0,250.0),board.state.value.elements.single { it.id == "local" }.points.first())
            assertEquals(incoming,board.state.value.elements.single { it.id == "incoming" })
        }
    }

    @Test fun selectedToolsKeepHighlightWithoutTheAdjacentSummaryPill() {
        rule.setContent { Box(Modifier.requiredSize(1024.dp,768.dp)) { MobileBoardApp(board) } }
        val pen = rule.onNodeWithTag("mobile-tool-pen").assertIsSelected().assertContentDescriptionEquals("Pen")
        rule.onNodeWithTag("mobile-width").assertDoesNotExist()
        val selected = pen.captureToImage().toAwtImage()
        val unselected = rule.onNodeWithTag("mobile-tool-eraser").captureToImage().toAwtImage()
        val fill = selected.getRGB(8,8)
        assertNotEquals(unselected.getRGB(8,8),fill,"The selected tool must retain its highlight")
        for (x in 14..33) assertEquals(fill,selected.getRGB(x,42),"The highlight must have no selected-tool underline")
        toolProperties()
        rule.onNodeWithContentDescription("Fern stroke").performScrollTo().performClick()
        rule.onNodeWithText("Done").performClick()
        rule.onNodeWithTag("mobile-width").assertDoesNotExist()
        draw()
        rule.runOnIdle { assertEquals("#387c59",board.state.value.elements.single().color) }
        rule.onNodeWithTag("mobile-tool-text").performClick().assertIsSelected().assertContentDescriptionEquals("Text")
        toolProperties()
        rule.onNodeWithContentDescription("Fern stroke").assertIsSelected()
    }

}
