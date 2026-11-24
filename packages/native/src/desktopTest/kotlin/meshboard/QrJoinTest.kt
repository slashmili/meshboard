@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package meshboard

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import kotlin.test.*

class QrJoinTest {
    @get:Rule val rule = createComposeRule()
    private val invite = "https://board.example/#crdt=123e4567-e89b-42d3-a456-426614174000&key=${"A".repeat(43)}"
    private class Controller : BoardController {
        override val state = MutableStateFlow(BoardState(elements = listOf(BoardElement("kept", "line", "#293b36", 3.0, listOf(Point(0.0,0.0), Point(10.0,10.0))))))
        var joins = 0
        override fun validInvite(invite: String) = try { DesktopController.parseInvite(invite); true } catch (_: Exception) { false }
        override fun join(invite: String) { joins++; state.value = BoardState(invite = invite, signaling = true) }
        override fun newId() = "unused"
        override fun put(element: BoardElement) {}
        override fun remove(ids: List<String>) {}
        override fun move(ids: List<String>, delta: Point) {}
        override fun undo() {}
        override fun redo() {}
        override fun preview(element: BoardElement?) {}
        override fun share(origin: String) {}
        override fun retry() {}
        override fun leave() {}
    }
    private val controller = Controller()
    private lateinit var camera: QrScanEvents
    private var opens = 0
    private var stops = 0
    private fun render() {
        rule.setContent {
            Box(Modifier.requiredSize(1024.dp,768.dp)) {
                MobileBoardApp(controller, appearanceStore = lightAppearance(), scanner = { events ->
                    DisposableEffect(events) {
                        opens++; camera = events
                        onDispose { stops++ }
                    }
                })
            }
        }
    }
    private fun scan() {
        rule.onNodeWithTag("mobile-more").performClick()
        rule.onNodeWithTag("mobile-join").performScrollTo().performClick()
        rule.onNodeWithTag("mobile-scan-qr").performScrollTo().performClick()
        rule.waitForIdle()
    }
    @Test fun scannedInviteJoinsExactlyOnceAndStopsCamera() {
        render()
        rule.runOnIdle { assertEquals(0,opens) }
        scan()
        rule.runOnIdle { camera.found(invite); camera.found(invite) }
        rule.runOnIdle { assertEquals(1,controller.joins); assertEquals(invite,controller.state.value.invite); assertEquals(1,stops) }
    }
    @Test fun invalidQrCancelAndCameraFailurePreserveBoardAndPasteFallback() {
        render(); scan()
        rule.runOnIdle { camera.found("javascript:alert(1)") }
        rule.onNodeWithTag("mobile-scan-error").assertIsDisplayed()
        rule.runOnIdle { assertEquals(0,controller.joins); assertEquals("kept",controller.state.value.elements.single().id) }
        rule.onNodeWithTag("mobile-scan-qr").performClick()
        rule.runOnIdle { camera.failed("Camera unavailable. Paste the invite.") }
        rule.onNodeWithTag("mobile-scan-error").assertTextEquals("Camera unavailable. Paste the invite.")
        rule.onNodeWithTag("mobile-scan-qr").performClick()
        rule.runOnIdle { camera.cancelled(); camera.found(invite) }
        rule.onNodeWithTag("mobile-invite-input").performTextInput(invite)
        rule.onNodeWithTag("mobile-confirm-join").performClick()
        rule.runOnIdle { assertEquals(1,controller.joins); assertEquals(3,stops) }
    }
    @Test fun originValidationRejectsNonHttpsRemoteAndCredentials() {
        for (value in listOf("http://remote.example/#crdt=bad", "https://user:pass@board.example/#crdt=bad", "file:///board#crdt=bad", "https://[invalid")) {
            assertFalse(controller.validInvite(value))
        }
        assertEquals("https://board.example", DesktopController.parseInvite(invite).first)
    }
}
