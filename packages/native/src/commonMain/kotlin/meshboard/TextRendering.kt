package meshboard

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.abs
import kotlin.math.min
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp

fun DrawScope.drawTextElement(element: BoardElement, measurer: TextMeasurer, alpha: Float, dark: Boolean = false) {
    val at = element.points.first()
    val end = element.points.last()
    val top = Offset(min(at.x, end.x).toFloat(), min(at.y, end.y).toFloat())
    if (element.type == "sticky") {
        val box = Size(abs(end.x-at.x).toFloat(), abs(end.y-at.y).toFloat())
        drawRoundRect(Color(0xfffff0a8).copy(alpha = alpha), top, box, CornerRadius(4f))
        drawRoundRect(Color(0xffc49226).copy(alpha = alpha), top, box, CornerRadius(4f), style = Stroke(1f))
    }
    // Notes keep their light paper, so their text keeps its canonical color.
    val color = displayPaint(element.color, dark && element.type != "sticky").copy(alpha = alpha)
    textLines(element.text ?: "").forEachIndexed { index, line ->
        // Canvas coordinates are already density-scaled by the enclosing renderer.
        val layout = measurer.measure(line, style = TextStyle(color = color, fontSize = 18.sp, fontFamily = FontFamily.Monospace), density = Density(1f), softWrap = false)
        drawText(layout, topLeft = Offset(top.x + 12, top.y + 30 + index * 24 - layout.firstBaseline))
    }
}
