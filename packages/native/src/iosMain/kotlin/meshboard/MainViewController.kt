package meshboard

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.remember
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlin.io.encoding.Base64
import org.jetbrains.skia.Image
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeUIViewController

fun MainViewController(network: AppleNetwork, origin: String) = ComposeUIViewController {
    val controller = remember { AppleBoardController(network) }
    DisposableEffect(controller) { onDispose { controller.close() } }
    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        MobileBoardApp(controller, origin, boardTitle = if (appleCrdtPreviewEnabled()) "Meshboard · CRDT preview · local only" else "Meshboard") {
            Image.makeFromEncoded(Base64.decode(network.qrPng(it))).toComposeImageBitmap()
        }
    }
}
