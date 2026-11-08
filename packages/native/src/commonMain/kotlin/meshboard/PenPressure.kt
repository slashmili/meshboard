package meshboard

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerType

/** Missing pressure preserves mouse/touch width; stylus contact uses 25–100%. */
fun penWidth(width: Double, pressure: Double?) = if (pressure == null) width else width * (0.25 + 0.75 * pressure)
fun penSample(point: Point, type: PointerType, pressure: Float): Point =
    if (type == PointerType.Stylus && pressure.isFinite()) point.copy(pressure = pressure.toDouble().coerceIn(0.0, 1.0)) else point

fun DrawScope.drawPressurePen(element: BoardElement, color: Color) {
    val points = element.points
    if (points.size == 1) {
        val p = points.first()
        drawCircle(color, (penWidth(element.width, p.pressure) / 2).toFloat(), Offset(p.x.toFloat(), p.y.toFloat()))
    } else points.zipWithNext().forEach { (a, b) ->
        val width = (penWidth(element.width, a.pressure) + penWidth(element.width, b.pressure)) / 2
        drawLine(color, Offset(a.x.toFloat(), a.y.toFloat()), Offset(b.x.toFloat(), b.y.toFloat()), width.toFloat(), StrokeCap.Round)
    }
}
