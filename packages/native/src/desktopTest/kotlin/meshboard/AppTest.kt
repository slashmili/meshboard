@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package meshboard

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import javax.imageio.ImageIO
import org.junit.Rule
import org.junit.Test
import kotlin.test.*

class AppTest {
    @get:Rule val rule = createComposeRule()
    private class LocalController : BoardController {
        override val state = MutableStateFlow(BoardState())
        private val local = LocalBoardController { "unused" }
        private var next = 0
        override fun newId() = "test-${++next}"
        private fun sync() { val next = local.state.value; state.value = state.value.copy(elements = next.elements, canUndo = next.canUndo, canRedo = next.canRedo) }
        override fun put(element: BoardElement) { local.put(element); sync() }
        override fun remove(ids: List<String>) { local.remove(ids); sync() }
        override fun move(ids: List<String>, delta: Point) { local.move(ids, delta); sync() }
        override fun undo() { local.undo(); sync() }
        override fun redo() { local.redo(); sync() }
        override fun preview(element: BoardElement?) {}
        override fun share(origin: String) { state.value = state.value.copy(invite = "$origin/#room=123e4567-e89b-42d3-a456-426614174000", signaling = true) }
        override fun join(invite: String) { state.value = BoardState(invite = invite, signaling = true) }
        override fun leave() { local.discard(); state.value = BoardState() }
        override fun retry() {}
    }
    private fun render(controller: BoardController, height: Int = 768) {
        rule.setContent { Box(Modifier.requiredSize(1024.dp, height.dp)) { MeshboardApp(controller, qrImage = ::qrImage) } }
    }
    private fun drag() = rule.onNodeWithTag("drawing-canvas").performMouseInput {
        moveTo(Offset(350f, 250f)); press(); moveTo(Offset(460f, 330f)); release()
    }
    @Test fun desktopHistoryControlsRestoreDrawingAndClearRedoOnNewEdit() {
        val controller = LocalController(); render(controller)
        rule.onNodeWithTag("undo").assertIsNotEnabled()
        rule.onNodeWithTag("redo").assertIsNotEnabled()
        drag()
        rule.onNodeWithTag("undo").performClick()
        rule.runOnIdle { assertTrue(controller.state.value.elements.isEmpty()) }
        rule.onNodeWithTag("redo").performClick()
        rule.runOnIdle { assertEquals(1, controller.state.value.elements.size) }
        rule.onNodeWithTag("undo").performClick(); drag()
        rule.onNodeWithTag("redo").assertIsNotEnabled()
    }
    @Test fun mobileHistoryControlsUndoAndRedoTouchDrawing() {
        var next = 0
        val controller = LocalBoardController { "history-${++next}" }
        rule.setContent { Box(Modifier.requiredSize(1024.dp, 768.dp)) { MobileBoardApp(controller) } }
        rule.onNodeWithTag("mobile-undo").assertIsNotEnabled()
        rule.onNodeWithTag("mobile-canvas").performTouchInput {
            down(Offset(200f, 200f)); moveTo(Offset(300f, 280f)); up()
        }
        rule.onNodeWithTag("mobile-undo").performClick()
        rule.runOnIdle { assertTrue(controller.state.value.elements.isEmpty()) }
        rule.onNodeWithTag("mobile-redo").performClick()
        rule.runOnIdle { assertEquals(1, controller.state.value.elements.size) }
    }
    @Test fun drawingToolsAndEraserWorkThroughPointerInput() {
        val controller = LocalController(); render(controller)
        rule.onNodeWithText("Meshboard", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithContentDescription("Meshboard logo").assertIsDisplayed()
        drag()
        rule.runOnIdle { assertEquals("pen", controller.state.value.elements.single().type) }
        rule.onNodeWithTag("tool-rectangle").performClick(); drag()
        rule.runOnIdle { assertEquals(listOf("pen", "rectangle"), controller.state.value.elements.map { it.type }) }
        rule.onNodeWithTag("tool-eraser").performClick()
        rule.onNodeWithTag("drawing-canvas").performMouseInput { click(Offset(350f, 250f)) }
        rule.runOnIdle { assertTrue(controller.state.value.elements.isEmpty()) }
        rule.onNodeWithText("+").performClick()
        rule.onNodeWithTag("zoom").assertTextEquals("120%")
        rule.onNodeWithText("Reset view").performClick()
        rule.onNodeWithTag("zoom").assertTextEquals("100%")
        rule.onNodeWithTag("tool-pen").performClick(); drag()
        val screenshot = rule.onRoot().captureToImage().toAwtImage()
        val file = File("build/test-artifacts/desktop-board.png"); file.parentFile.mkdirs(); ImageIO.write(screenshot, "png", file)
    }
    @Test fun arrowsCanBeDrawnAndErasedAtMinimumWindowHeight() {
        val controller = LocalController(); render(controller, 650)
        rule.onNodeWithTag("tool-arrow").assertIsDisplayed().performClick(); drag()
        rule.runOnIdle { assertEquals("arrow", controller.state.value.elements.single().type) }
        rule.onNodeWithTag("tool-eraser").performClick()
        rule.onNodeWithTag("drawing-canvas").performMouseInput { click(Offset(460f, 330f)) }
        rule.runOnIdle { assertTrue(controller.state.value.elements.isEmpty()) }
    }
    @Test fun nativeCanCreateAndJoinInvites() {
        val controller = LocalController(); render(controller)
        rule.onNodeWithText("Share", useUnmergedTree = true).performClick()
        rule.onNodeWithText("Create invite").performClick()
        rule.onNodeWithTag("invite").assertExists()
        rule.onNodeWithContentDescription("QR code for this board").assertExists()
        rule.onNodeWithText("Done").performClick()
        rule.onNodeWithText("Join", useUnmergedTree = true).performClick()
        val invite = "http://127.0.0.1:5173/#room=123e4567-e89b-42d3-a456-426614174001"
        rule.onNodeWithTag("join-link").performTextInput(invite)
        rule.onNodeWithText("Join board", useUnmergedTree = true).performClick()
        rule.runOnIdle { assertEquals(invite, controller.state.value.invite) }
    }

    @Test fun localMobileCanvasDrawsAndConfirmsClearWithoutSharing() {
        var next = 0
        val controller = LocalBoardController { "local-${++next}" }
        rule.setContent { Box(Modifier.requiredSize(1024.dp, 768.dp)) { MobileBoardApp(controller) } }
        rule.onNodeWithTag("mobile-connection-status").assertTextEquals("Local only")
        rule.onNodeWithTag("mobile-join").assertDoesNotExist()
        rule.onNodeWithTag("mobile-share").assertDoesNotExist()
        rule.onNodeWithTag("mobile-canvas").performTouchInput {
            down(Offset(200f, 200f)); moveTo(Offset(300f, 280f)); up()
        }
        rule.runOnIdle { assertEquals(1, controller.state.value.elements.size) }
        rule.onNodeWithTag("mobile-more").performClick()
        rule.onNodeWithTag("mobile-clear").performClick()
        rule.onNodeWithText("Cancel").performClick()
        rule.runOnIdle { assertEquals(1, controller.state.value.elements.size) }
        rule.onNodeWithTag("mobile-more").performClick()
        rule.onNodeWithTag("mobile-clear").performClick()
        rule.onNodeWithTag("mobile-confirm-clear").performClick()
        rule.runOnIdle { assertTrue(controller.state.value.elements.isEmpty()) }
    }
    @Test fun desktopAppearanceChangesWithoutDiscardingDrawing() {
        val controller = LocalController(); render(controller); drag()
        val before = controller.state.value.elements
        rule.onNodeWithText("Appearance", useUnmergedTree = true).performClick()
        rule.onNodeWithText("Dark").performClick()
        rule.onNodeWithText("Dark").assertIsSelected()
        rule.onNodeWithText("Done").performClick()
        val dark = rule.onRoot().captureToImage().toAwtImage()
        assertEquals(0x202922, dark.getRGB(5, 5) and 0xffffff)
        val file = File("build/test-artifacts/desktop-dark.png"); file.parentFile.mkdirs(); ImageIO.write(dark, "png", file)
        rule.onNodeWithText("Appearance", useUnmergedTree = true).performClick()
        rule.onNodeWithText("Light").performClick()
        rule.onNodeWithText("Light").assertIsSelected()
        rule.onNodeWithText("System").performClick()
        rule.onNodeWithText("System").assertIsSelected()
        rule.onNodeWithText("Done").performClick()
        rule.runOnIdle { assertEquals(before, controller.state.value.elements) }
    }

    @Test fun desktopSelectionMovesGroupsAndEscapeCancelsDraftMove() {
        val controller = LocalController()
        for ((id,x) in listOf("one" to 450.0, "two" to 650.0)) controller.put(BoardElement(id,"rectangle","#293b36",3.0,listOf(Point(x,200.0),Point(x+60,260.0))))
        render(controller, 650)
        rule.onNodeWithTag("tool-select").assertIsDisplayed().performClick()
        rule.onNodeWithTag("drawing-canvas").performMouseInput { moveTo(Offset(400f,150f)); press(); moveTo(Offset(750f,300f)); release() }
        rule.onNodeWithTag("drawing-canvas").performMouseInput { moveTo(Offset(480f,230f)); press(); moveTo(Offset(580f,330f)); release() }
        rule.runOnIdle { assertEquals(listOf(550.0,750.0), controller.state.value.elements.map { it.points.first().x }) }
        val before = controller.state.value.elements
        rule.onNodeWithTag("drawing-canvas").performMouseInput { moveTo(Offset(580f,330f)); press(); moveTo(Offset(680f,430f)) }
        rule.onNodeWithTag("drawing-canvas").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Escape) }
        rule.onNodeWithTag("drawing-canvas").performMouseInput { release() }
        rule.runOnIdle { assertEquals(before, controller.state.value.elements) }
    }
    @Test fun mobileSelectionMovesGroupsAndPinchCancelsMovement() {
        val controller = LocalBoardController { "unused" }
        for ((id,x) in listOf("one" to 100.0, "two" to 240.0)) controller.put(BoardElement(id,"rectangle","#293b36",3.0,listOf(Point(x,100.0),Point(x+40,140.0))))
        rule.setContent { Box(Modifier.requiredSize(390.dp,844.dp)) { MobileBoardApp(controller) } }
        rule.onNodeWithTag("mobile-tool-select").performScrollTo().performClick()
        rule.onNodeWithTag("mobile-canvas").performTouchInput { down(Offset(80f,80f)); moveTo(Offset(300f,160f)); up() }
        rule.onNodeWithTag("mobile-canvas").performTouchInput { down(Offset(120f,120f)); moveTo(Offset(140f,150f)); up() }
        rule.runOnIdle { assertEquals(listOf(120.0,260.0), controller.state.value.elements.map { it.points.first().x }) }
        val before = controller.state.value.elements
        rule.onNodeWithTag("mobile-canvas").performTouchInput {
            down(0,Offset(140f,150f)); moveBy(0,Offset(10f,10f))
            down(1,Offset(250f,250f)); moveBy(1,Offset(20f,20f)); up(1); up(0)
        }
        rule.runOnIdle { assertEquals(before, controller.state.value.elements) }
    }
    @Test fun mobileArrowEndpointsAttachAndDetachWithoutCreatingDuplicates() {
        val controller = LocalBoardController { "arrow" }
        controller.put(BoardElement("box","rectangle","#123456",3.0,listOf(Point(100.0,100.0),Point(200.0,200.0))))
        rule.setContent { Box(Modifier.requiredSize(1024.dp,768.dp)) { MobileBoardApp(controller) } }
        rule.onNodeWithTag("mobile-tool-arrow").performClick()
        rule.onNodeWithTag("mobile-canvas").performTouchInput { down(Offset(200f,150f)); moveTo(Offset(400f,150f)); up() }
        rule.runOnIdle { assertEquals("box",controller.state.value.elements.first { it.id=="arrow" }.startAttachment?.id) }
        rule.onNodeWithTag("mobile-canvas").performTouchInput { down(Offset(200f,150f)); moveTo(Offset(300f,300f)); up() }
        rule.runOnIdle { assertEquals(2,controller.state.value.elements.size); assertNull(controller.state.value.elements.first { it.id=="arrow" }.startAttachment) }
        rule.onNodeWithTag("mobile-undo").performClick()
        rule.runOnIdle { assertEquals("box",controller.state.value.elements.first { it.id=="arrow" }.startAttachment?.id) }
    }

    @Test fun mobileImagesPaintPixelsAndEraseThroughTheirInterior() {
        val controller = LocalBoardController { "unused" }
        val bytes = File(System.getProperty("meshboard.fixtures"),"image.png").readBytes()
        controller.put(imageElement("image",bytes,Point(200.0,200.0)))
        rule.setContent { Box(Modifier.requiredSize(1024.dp,768.dp)) { MobileBoardApp(controller) } }
        rule.onNodeWithTag("insert-image").assertIsDisplayed()
        val image = rule.onNodeWithTag("mobile-canvas").captureToImage().toAwtImage()
        val scale = image.width / 1024.0
        val pixel = java.awt.Color(image.getRGB((200*scale).toInt(),(200*scale).toInt()))
        assertTrue(pixel.red > 180 && pixel.green < 80, "Embedded image must paint its pixels")
        rule.onNodeWithTag("mobile-tool-eraser").performClick()
        rule.onNodeWithTag("mobile-canvas").performTouchInput { click(Offset(200f,200f)) }
        rule.runOnIdle { assertTrue(controller.state.value.elements.isEmpty()) }
        rule.onNodeWithTag("mobile-undo").performClick()
        rule.runOnIdle { assertEquals(1,controller.state.value.elements.size) }
    }

    @Test fun mobilePressureStrokePaintsDifferentWidths() {
        val controller = LocalBoardController { "pressure" }
        controller.put(BoardElement("pressure", "pen", "#293b36", 8.0, listOf(
            Point(100.0,100.0,0.0), Point(200.0,100.0,0.0), Point(300.0,100.0,1.0), Point(400.0,100.0,1.0))))
        rule.setContent { Box(Modifier.requiredSize(1024.dp, 768.dp)) { MobileBoardApp(controller) } }
        val image = rule.onNodeWithTag("mobile-canvas").captureToImage().toAwtImage()
        val scale = image.width / 1024.0
        fun pixel(x: Int, y: Int) = java.awt.Color(image.getRGB((x * scale).toInt(), (y * scale).toInt()))
        assertTrue(pixel(150, 103).red > 200, "Low pressure must remain narrow")
        assertTrue(pixel(350, 103).red < 150, "High pressure must paint a wider stroke")
    }
    @Test fun mobileArrowsDrawAndEraseWithTouch() {
        val controller = LocalBoardController { "mobile-arrow" }
        rule.setContent { Box(Modifier.requiredSize(390.dp, 844.dp)) { MobileBoardApp(controller) } }
        rule.onNodeWithTag("mobile-tool-arrow").performScrollTo().performClick()
        rule.onNodeWithTag("mobile-canvas").performTouchInput {
            down(Offset(100f, 100f)); moveTo(Offset(250f, 100f)); up()
        }
        rule.runOnIdle { assertEquals("arrow", controller.state.value.elements.single().type) }
        val image = rule.onNodeWithTag("mobile-canvas").captureToImage().toAwtImage()
        val scale = image.width / 390.0
        val wing = java.awt.Color(image.getRGB((238 * scale).toInt(), (106 * scale).toInt()))
        assertTrue(wing.red < 150 && wing.green < 150, "The mobile canvas must render the arrowhead, not just the shaft")
        rule.onNodeWithTag("mobile-tool-eraser").performScrollTo().performClick()
        rule.onNodeWithTag("mobile-canvas").performTouchInput { click(Offset(250f, 100f)) }
        rule.runOnIdle { assertTrue(controller.state.value.elements.isEmpty()) }
    }
    @Test fun mobileAppearanceIsAvailableInOverflow() {
        val controller = LocalBoardController { "theme-stroke" }
        rule.setContent { Box(Modifier.requiredSize(1024.dp, 768.dp)) { MobileBoardApp(controller) } }
        rule.onNodeWithTag("mobile-more").performClick()
        rule.onNodeWithText("Appearance").performClick()
        rule.onNodeWithText("Dark").performClick()
        rule.onNodeWithText("Dark").assertIsSelected()
        rule.onNodeWithText("Done").performClick()
        rule.onNodeWithTag("mobile-canvas").performTouchInput {
            down(Offset(200f, 200f)); moveTo(Offset(300f, 280f)); up()
        }
        rule.runOnIdle { assertEquals("#293b36", controller.state.value.elements.single().color) }
    }

    @Test fun nativeTextAndNotesCanBeEditedAndErased() {
        val controller = LocalController(); render(controller, height = 650)
        rule.onNodeWithTag("tool-text").performClick()
        rule.onNodeWithTag("drawing-canvas").performMouseInput { click(Offset(350f, 250f)) }
        rule.onNodeWithTag("text-editor").performTextInput("Hello <team>\n世界 👋")
        rule.onNodeWithText("Save text").performClick()
        rule.runOnIdle { assertEquals("Hello <team>\n世界 👋", controller.state.value.elements.single().text) }
        rule.onNodeWithTag("drawing-canvas").performMouseInput { click(Offset(380f, 280f)) }
        rule.onNodeWithTag("text-editor").performTextReplacement("Edited box")
        rule.onNodeWithText("Save text").performClick()
        rule.runOnIdle { assertEquals("Edited box", controller.state.value.elements.single().text) }
        rule.onNodeWithTag("tool-sticky").performClick()
        rule.onNodeWithTag("drawing-canvas").performMouseInput { click(Offset(680f, 250f)) }
        rule.onNodeWithTag("text-editor").performTextInput("A sticky note")
        rule.onNodeWithText("Save text").performClick()
        val file = File("build/test-artifacts/desktop-text.png"); file.parentFile.mkdirs()
        ImageIO.write(rule.onRoot().captureToImage().toAwtImage(), "png", file)
        rule.runOnIdle { assertEquals(2, controller.state.value.elements.size) }
        rule.onNodeWithTag("tool-eraser").performClick()
        rule.onNodeWithTag("drawing-canvas").performMouseInput { click(Offset(700f, 280f)) }
        rule.runOnIdle { assertEquals(1, controller.state.value.elements.size) }
    }

    @Test fun deletingAnOpenTextObjectDisablesSave() {
        val controller = LocalController()
        controller.put(textElement("remote-text", "text", Point(350.0, 250.0), "#293b36", "Delete while editing"))
        render(controller)
        rule.onNodeWithTag("tool-text").performClick()
        rule.onNodeWithTag("drawing-canvas").performMouseInput { click(Offset(380f, 280f)) }
        rule.onNodeWithTag("text-editor").assertTextContains("Delete while editing")
        rule.runOnIdle { controller.remove(listOf("remote-text")) }
        rule.onNodeWithText("Save text").assertIsNotEnabled()
        rule.onNodeWithText("Cancel").performClick()
        rule.runOnIdle { assertTrue(controller.state.value.elements.isEmpty()) }
    }

}
