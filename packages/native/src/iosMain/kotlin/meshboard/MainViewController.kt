package meshboard

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeUIViewController
import platform.Foundation.NSUUID

fun MainViewController() = ComposeUIViewController {
    val controller = remember { LocalBoardController { NSUUID().UUIDString } }
    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        MobileBoardApp(controller)
    }
}
