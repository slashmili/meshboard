package meshboard

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import org.jetbrains.compose.resources.decodeToImageBitmap
import org.jetbrains.compose.resources.ExperimentalResourceApi
import kotlin.math.*

@OptIn(ExperimentalResourceApi::class)
fun decodeBoardImage(bytes: ByteArray): ImageBitmap {
    imageInfo(bytes)
    return bytes.decodeToImageBitmap().also { require(it.width in 1..1024 && it.height in 1..1024) { IMAGE_HELP } }
}
@Composable
fun rememberBoardImages(elements: List<BoardElement>): Map<String, ImageBitmap?> {
    val cache = remember { mutableMapOf<String, ImageBitmap?>() }
    return remember(elements) {
        val data = elements.mapNotNull { it.image }.distinct().take(8).toSet()
        cache.keys.retainAll(data)
        data.forEach { if (!cache.containsKey(it)) cache[it] = runCatching { decodeBoardImage(imageBytes(it)) }.getOrNull() }
        cache.toMap()
    }
}
fun DrawScope.drawImageElement(element: BoardElement, images: Map<String, ImageBitmap?>, alpha: Float) {
    val a = element.points.first(); val b = element.points.last()
    val x = min(a.x,b.x).toFloat(); val y = min(a.y,b.y).toFloat()
    val w = abs(b.x-a.x).toFloat(); val h = abs(b.y-a.y).toFloat()
    val bitmap = images[element.image]
    if (bitmap == null) {
        drawRect(Color.Gray.copy(alpha = alpha),Offset(x,y),Size(w,h),style=Stroke(1f))
        drawLine(Color.Gray.copy(alpha=alpha),Offset(x,y),Offset(x+w,y+h))
    } else {
        val scale = min(w / bitmap.width,h / bitmap.height)
        val width = bitmap.width * scale; val height = bitmap.height * scale
        drawImage(bitmap,dstOffset=IntOffset((x+(w-width)/2).roundToInt(),(y+(h-height)/2).roundToInt()),dstSize=IntSize(width.roundToInt().coerceAtLeast(1),height.roundToInt().coerceAtLeast(1)),alpha=alpha)
    }
}
