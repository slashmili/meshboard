package meshboard

import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.awt.Dimension
import java.awt.image.BufferedImage
import meshboard.resources.Res
import meshboard.resources.meshboard_icon
import org.jetbrains.compose.resources.painterResource

fun main(args: Array<String>) {
    // Also exercises the bundled JVM/launcher in release CI, without a display.
    if (args.contentEquals(arrayOf("--print-build-info"))) {
        println("Meshboard ${System.getProperty("meshboard.app.version", "development")}")
        println("App origin: ${desktopAppOrigin()}")
        return
    }
    val controller = DesktopController(forceRelay = System.getenv("MESHBOARD_RELAY_ONLY") == "true")
    if (args.isNotEmpty()) controller.join(args[0])
    application {
        Window(onCloseRequest = { controller.close(); exitApplication() }, title = if (System.getProperty("meshboard.crdt.preview") == "true") "Meshboard — CRDT preview (local only)" else "Meshboard", icon = painterResource(Res.drawable.meshboard_icon), state = rememberWindowState(width = 1200.dp, height = 820.dp)) {
            window.minimumSize = Dimension(940, 650)
            val origin = remember { desktopAppOrigin() }
            MeshboardApp(controller, origin, ::qrImage)
        }
    }
}

fun qrImage(invite: String): ImageBitmap? = runCatching {
    val matrix = QRCodeWriter().encode(invite, BarcodeFormat.QR_CODE, 232, 232, mapOf(EncodeHintType.MARGIN to 2))
    val image = BufferedImage(232, 232, BufferedImage.TYPE_INT_RGB)
    for (x in 0 until 232) for (y in 0 until 232) image.setRGB(x, y, if (matrix[x, y]) 0x254d3c else 0xffffff)
    image.toComposeImageBitmap()
}.getOrNull()
