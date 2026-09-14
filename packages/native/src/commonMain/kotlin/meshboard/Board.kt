package meshboard

import kotlin.math.*

data class Point(val x: Double, val y: Double)
data class View(val x: Double = 0.0, val y: Double = 0.0, val zoom: Double = 1.0) {
    fun world(p: Point) = Point((p.x - x) / zoom, (p.y - y) / zoom)
    fun zoomAt(anchor: Point, requested: Double): View {
        val z = requested.coerceIn(.25, 4.0)
        val p = world(anchor)
        return View(anchor.x - p.x * z, anchor.y - p.y * z, z)
    }
}
enum class Tool(val wire: String, val label: String, val shortcut: String) {
    Pen("pen", "Pen", "P"), Eraser("eraser", "Eraser", "E"),
    Rectangle("rectangle", "Rectangle", "R"), Ellipse("ellipse", "Ellipse", "O"),
    Line("line", "Line", "L"), Hand("hand", "Pan", "H")
}
data class BoardElement(val id: String, val type: String, val color: String, val width: Double, val points: List<Point>)
data class BoardMessage(
    val type: String, val element: BoardElement? = null, val ids: List<String> = emptyList(),
    val elements: List<BoardElement> = emptyList(), val removed: List<String> = emptyList(),
)

class BoardDocument {
    private var objects = mapOf<String, BoardElement>()
    private var removed = setOf<String>()
    val elements get() = objects.values.sortedBy { it.id }
    fun snapshot() = BoardMessage("snapshot", elements = elements, removed = removed.sorted())
    fun reset() { objects = emptyMap(); removed = emptySet() }
    fun apply(message: BoardMessage) {
        if (message.type == "preview") return
        val nextObjects = objects.toMutableMap()
        val nextRemoved = removed.toMutableSet()
        val deletes = when (message.type) { "remove" -> message.ids; "snapshot" -> message.removed; else -> emptyList() }
        deletes.forEach { nextRemoved.add(it); nextObjects.remove(it) }
        val adds = when (message.type) { "put" -> listOf(requireNotNull(message.element)); "snapshot" -> message.elements; else -> emptyList() }
        adds.forEach { if (it.id !in nextRemoved && it.id !in nextObjects) nextObjects[it.id] = it }
        require(nextObjects.size <= Wire.MAX_ELEMENTS && nextRemoved.size <= Wire.MAX_REMOVED) { "This prototype board is full. Start a new board." }
        val snapshot = BoardMessage("snapshot", elements = nextObjects.values.toList(), removed = nextRemoved.toList())
        require(Wire.encode(snapshot).encodeToByteArray().size <= Wire.MAX_MESSAGE) { "This prototype board is full. Start a new board." }
        objects = nextObjects
        removed = nextRemoved
    }
}

fun constrained(start: Point, end: Point, type: String): Point {
    val dx = end.x - start.x; val dy = end.y - start.y
    if (type == "line") {
        val angle = round(atan2(dy, dx) / (PI / 4)) * (PI / 4)
        return Point(start.x + cos(angle) * hypot(dx, dy), start.y + sin(angle) * hypot(dx, dy))
    }
    val side = max(abs(dx), abs(dy))
    return Point(start.x + (if (dx < 0) -side else side), start.y + (if (dy < 0) -side else side))
}

fun outline(element: BoardElement): List<Point> {
    if (element.type == "pen" || element.type == "line") return element.points
    val a = element.points.first(); val b = element.points.last()
    val x = min(a.x, b.x); val y = min(a.y, b.y); val w = abs(b.x - a.x); val h = abs(b.y - a.y)
    if (element.type == "rectangle") return listOf(Point(x, y), Point(x + w, y), Point(x + w, y + h), Point(x, y + h), Point(x, y))
    return (0..96).map { i -> val angle = i / 96.0 * PI * 2; Point(x + w / 2 + cos(angle) * w / 2, y + h / 2 + sin(angle) * h / 2) }
}

private fun distance(p: Point, a: Point, b: Point): Double {
    val dx = b.x - a.x; val dy = b.y - a.y; val length = dx * dx + dy * dy
    val t = if (length == 0.0) 0.0 else (((p.x - a.x) * dx + (p.y - a.y) * dy) / length).coerceIn(0.0, 1.0)
    return hypot(p.x - a.x - t * dx, p.y - a.y - t * dy)
}
private fun cross(a: Point, b: Point, c: Point) = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)
fun hits(element: BoardElement, from: Point, to: Point, radius: Double): Boolean {
    val points = outline(element); val threshold = radius + element.width / 2
    if (points.size == 1) return distance(points[0], from, to) <= threshold
    return points.zipWithNext().any { (a, b) ->
        val crossing = cross(from, to, a) * cross(from, to, b) < 0 && cross(a, b, from) * cross(a, b, to) < 0
        crossing || minOf(distance(a, from, to), distance(b, from, to), distance(from, a, b), distance(to, a, b)) <= threshold
    }
}
