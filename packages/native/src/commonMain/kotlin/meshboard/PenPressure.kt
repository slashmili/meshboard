package meshboard

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerType

/** Missing pressure preserves base width; measured contact uses 25–100%. */
fun penWidth(width: Double, pressure: Double?) = if (pressure == null) width else width * (0.25 + 0.75 * pressure)

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

/** Per-contact capability detection; constant touch placeholders stay constant width. */
class StrokePressure(private val type: PointerType) {
    private var samples = mutableListOf<Point>()
    private var first: Double? = null
    private var enabled = false

    fun add(point: Point, pressure: Float, contact: Boolean = true): List<Point> {
        val value = if (contact) pressure.takeIf { it.isFinite() }?.toDouble()?.coerceIn(0.0, 1.0) else samples.lastOrNull()?.pressure
        if (contact && value != null && type != PointerType.Mouse) {
            if (first?.let { kotlin.math.abs(value - it) > 0.001 } == true) enabled = true
            if (first == null) first = value
            if (type == PointerType.Stylus && value > 0 && value != 0.5 && value != 1.0) enabled = true
        }
        val sample = point.copy(pressure = value)
        if (samples.lastOrNull() != sample) samples.add(sample)
        return samples.map { if (enabled && type != PointerType.Mouse) it else it.copy(pressure = null) }
    }

    fun retainLast(count: Int = 1) { samples = samples.takeLast(count).toMutableList() }
}

/** Missing capability or an invalid divisor must never turn into full pressure. */
fun normalizedForce(force: Double, maximum: Double): Float =
    if (force.isFinite() && maximum.isFinite() && maximum > 0) (force / maximum).coerceIn(0.0, 1.0).toFloat() else Float.NaN
