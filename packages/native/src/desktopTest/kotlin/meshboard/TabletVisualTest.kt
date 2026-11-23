@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package meshboard

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.*
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

/** Each scene has the actual tablet-window dimensions, including modal windows. */
class TabletVisualTest {
    @Test fun approvedLightAndDarkLayouts() {
        for ((name,size) in listOf("landscape" to (1024 to 768),"portrait" to (768 to 1024),"resized" to (540 to 720))) {
            runDesktopComposeUiTest(width = size.first, height = size.second) {
                LocalBoardController { "unused" }.use { board ->
                    board.put(textElement("note","sticky",Point(180.0,180.0),"#293b36","A space for ideas\nSketch together."))
                    board.put(BoardElement("shape","rectangle","#4878c8",3.0,listOf(Point(460.0,190.0),Point(580.0,290.0))))
                    board.put(BoardElement("ink","pen","#9563be",4.0,listOf(Point(200.0,350.0),Point(280.0,310.0),Point(340.0,352.0),Point(500.0,347.0))))
                    val controller = object : BoardController, DrawingController by board {
                        override fun share(origin: String) {}
                        override fun join(invite: String) {}
                        override fun retry() {}
                        override fun leave() {}
                    }
                    setContent { Box(Modifier.fillMaxSize()) { MobileBoardApp(controller, qrImage = { ImageBitmap(1,1) }) } }
                    fun settings() { onNodeWithTag("mobile-more").performClick(); onNodeWithText("Settings").performClick() }
                    fun save(suffix: String) {
                        val file = File("build/test-artifacts/tablet-$name-$suffix.png"); file.parentFile.mkdirs()
                        ImageIO.write(onRoot().captureToImage().toAwtImage(),"png",file)
                    }
                    settings(); onNodeWithText("Light").performClick(); onNodeWithText("Done").performClick()
                    save("light")
                    settings(); onNodeWithText("Dark").performClick(); onNodeWithText("Done").performClick()
                    save("dark")
                    val root = onRoot().captureToImage().toAwtImage()
                    assertEquals(0x202922,root.getRGB(5,5) and 0xffffff)
                    assertEquals(0xf8f9f6,root.getRGB(root.width / 2,65) and 0xffffff)
                    if (name == "landscape") {
                        settings(); onNodeWithTag("mobile-dock-right").performScrollTo().performClick(); onNodeWithText("Done").performClick()
                        val bounds = onNodeWithTag("tablet-side-dock").fetchSemanticsNode().boundsInRoot
                        assertTrue(bounds.left > 900)
                        save("right-dark")
                    }
                }
            }
        }
    }
    @Test fun everyToolHasNoFloatingSummaryInEveryTabletWindowClass() {
        val shortcuts = listOf(Key.V,Key.P,Key.E,Key.R,Key.O,Key.A,Key.L,Key.T,Key.N,Key.H)
        for ((width,height) in listOf(1024 to 768,768 to 1024,540 to 720,360 to 520)) {
            runDesktopComposeUiTest(width = width,height = height) {
                LocalBoardController { "unused" }.use { board ->
                    setContent { MobileBoardApp(board) }
                    for ((tool,key) in Tool.entries.zip(shortcuts)) {
                        onNodeWithTag("mobile-canvas").performKeyInput { pressKey(key) }
                        onNodeWithTag("mobile-width").assertDoesNotExist()
                        onNodeWithTag("mobile-more").performClick()
                        onNodeWithTag("mobile-tool-properties").performScrollTo().performClick()
                        onNodeWithText(tool.label, useUnmergedTree = true).assertIsDisplayed()
                        onNodeWithText("Done").performClick()
                        onNodeWithTag("mobile-width").assertDoesNotExist()
                    }
                }
            }
        }
    }

}
