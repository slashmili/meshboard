package meshboard

import kotlin.math.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

data class SelectionBox(val x: Double, val y: Double, val width: Double, val height: Double)
fun selectionBounds(element: BoardElement): SelectionBox {
    val points = outline(element)
    val x = points.minOf { it.x }; val y = points.minOf { it.y }
    return SelectionBox(x, y, points.maxOf { it.x } - x, points.maxOf { it.y } - y)
}
fun translated(elements: List<BoardElement>, ids: List<String>, delta: Point): List<BoardElement> {
    require(delta.x.isFinite() && delta.y.isFinite())
    val selected = ids.toSet()
    return elements.filter { it.id in selected }.map { e -> e.copy(points = e.points.map { p ->
        p.copy(x = p.x + delta.x, y = p.y + delta.y).also { require(it.x in -1e7..1e7 && it.y in -1e7..1e7) }
    }) }
}
data class Selection(val ids: List<String> = emptyList(), val start: Point? = null, val end: Point? = null, val moving: Boolean = false) {
    fun begin(elements: List<BoardElement>, at: Point, radius: Double, additive: Boolean = false): Selection {
        val hit = elements.lastOrNull { e ->
            val b = selectionBounds(e)
            if (e.type in listOf("rectangle", "ellipse", "text", "sticky")) at.x in b.x-radius..b.x+b.width+radius && at.y in b.y-radius..b.y+b.height+radius
            else hits(e, at, at, radius)
        }
        if (hit != null) {
            val next = if (additive) { if (hit.id in ids) ids - hit.id else ids + hit.id } else if (hit.id in ids) ids else listOf(hit.id)
            return if (hit.id in next) Selection(next, at, at, true) else Selection(next)
        }
        return Selection(if (additive) ids else emptyList(), at, at)
    }
    fun box(): SelectionBox? {
        val a = start ?: return null; val b = end ?: return null
        return if (moving) null else SelectionBox(min(a.x,b.x), min(a.y,b.y), abs(b.x-a.x), abs(b.y-a.y))
    }
    fun delta(): Point = if (moving && start != null && end != null) Point(end.x-start.x, end.y-start.y) else Point(0.0,0.0)
    fun finish(elements: List<BoardElement>): Selection {
        val box = box()
        val enclosed = if (box == null) emptyList() else elements.filter { e ->
            val b = selectionBounds(e)
            b.x >= box.x && b.y >= box.y && b.x+b.width <= box.x+box.width && b.y+b.height <= box.y+box.height
        }.map { it.id }
        return Selection((ids + enclosed).distinct().filter { id -> elements.any { it.id == id } })
    }
    fun display(elements: List<BoardElement>): List<BoardElement> {
        if (!moving) return elements
        val moved = try { translated(elements, ids, delta()).associateBy { it.id } } catch (_: IllegalArgumentException) { emptyMap() }
        return elements.map { moved[it.id] ?: it }
    }
}
fun DrawScope.drawSelection(selection: Selection, elements: List<BoardElement>, zoom: Double) {
    val boxes = elements.filter { it.id in selection.ids }.map(::selectionBounds) + listOfNotNull(selection.box())
    val padding = 4 / zoom
    boxes.forEach { b -> drawRect(Color(0xff4878c8), Offset((b.x-padding).toFloat(), (b.y-padding).toFloat()),
        Size((b.width+2*padding).toFloat(), (b.height+2*padding).toFloat()),
        style = Stroke((1.5/zoom).toFloat(), pathEffect = PathEffect.dashPathEffect(floatArrayOf((5/zoom).toFloat(),(3/zoom).toFloat())))) }
}
