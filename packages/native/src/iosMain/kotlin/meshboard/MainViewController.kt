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
import platform.Foundation.NSUserDefaults
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIViewController
import platform.UIKit.popoverPresentationController
import platform.CoreGraphics.CGRectMake
import kotlinx.cinterop.ExperimentalForeignApi

@OptIn(ExperimentalForeignApi::class)
fun MainViewController(network: AppleNetwork, origin: String): UIViewController {
    lateinit var host: UIViewController
    host = ComposeUIViewController {
        val controller = remember { AppleBoardController(network) }
        // Only the local appearance choice is persisted; board data stays in memory.
        val appearance = remember {
            val defaults = NSUserDefaults.standardUserDefaults
            KeyValueAppearanceStore(read = { defaults.stringForKey(it) }, write = { key, value -> defaults.setObject(value, key) })
        }
        DisposableEffect(controller) { onDispose { controller.close() } }
        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            MobileBoardApp(controller, origin, boardTitle = "Meshboard", appearanceStore = appearance, contactPressure = ::appleContactPressure, shareInvite = { invite ->
                var presenter = host
                while (presenter.presentedViewController != null) presenter = presenter.presentedViewController!!
                val activity = UIActivityViewController(activityItems = listOf(invite), applicationActivities = null)
                // iPad sharing requires an explicit popover anchor, including in resized windows.
                activity.popoverPresentationController?.let { popover ->
                    popover.sourceView = presenter.view
                    popover.sourceRect = CGRectMake(0.0, 0.0, 1.0, 1.0)
                }
                presenter.presentViewController(activity, animated = true, completion = null)
            }) {
                Image.makeFromEncoded(Base64.decode(network.qrPng(it))).toComposeImageBitmap()
            }
        }
    }
    return host
}
