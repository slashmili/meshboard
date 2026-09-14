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
        private val doc = BoardDocument()
        private var next = 0
        override fun newId() = "test-${++next}"
        override fun put(element: BoardElement) { doc.apply(BoardMessage("put", element = element)); state.value = state.value.copy(elements = doc.elements) }
        override fun remove(ids: List<String>) { doc.apply(BoardMessage("remove", ids = ids)); state.value = state.value.copy(elements = doc.elements) }
        override fun preview(element: BoardElement?) {}
        override fun share(origin: String) { state.value = state.value.copy(invite = "$origin/#room=123e4567-e89b-42d3-a456-426614174000", signaling = true) }
        override fun join(invite: String) { state.value = BoardState(invite = invite, signaling = true) }
        override fun leave() { doc.reset(); state.value = BoardState() }
        override fun retry() {}
    }
    private fun render(controller: BoardController) {
        rule.setContent { Box(Modifier.requiredSize(1024.dp, 768.dp)) { MeshboardApp(controller, qrImage = ::qrImage) } }
    }
    private fun drag() = rule.onNodeWithTag("drawing-canvas").performMouseInput {
        moveTo(Offset(350f, 250f)); press(); moveTo(Offset(460f, 330f)); release()
    }
    @Test fun drawingToolsAndEraserWorkThroughPointerInput() {
        val controller = LocalController(); render(controller)
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
}
