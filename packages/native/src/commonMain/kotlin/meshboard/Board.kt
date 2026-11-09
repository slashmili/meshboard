package meshboard

import kotlin.math.*

data class Point(val x: Double, val y: Double, val pressure: Double? = null)
data class View(val x: Double = 0.0, val y: Double = 0.0, val zoom: Double = 1.0) {
    fun world(p: Point) = Point((p.x - x) / zoom, (p.y - y) / zoom)
    fun zoomAt(anchor: Point, requested: Double): View {
        val z = requested.coerceIn(.25, 4.0)
        val p = world(anchor)
        return View(anchor.x - p.x * z, anchor.y - p.y * z, z)
    }
}
enum class Tool(val wire: String, val label: String, val shortcut: String) {
    Select("select", "Select", "V"), Pen("pen", "Pen", "P"), Eraser("eraser", "Eraser", "E"),
    Rectangle("rectangle", "Rectangle", "R"), Ellipse("ellipse", "Ellipse", "O"),
    Arrow("arrow", "Arrow", "A"), Line("line", "Line", "L"), Text("text", "Text", "T"), Sticky("sticky", "Note", "N"), Hand("hand", "Pan", "H")
}
data class BoardElement(val id: String, val type: String, val color: String, val width: Double, val points: List<Point>, val text: String? = null, val image: String? = null, val startAttachment: Attachment? = null, val endAttachment: Attachment? = null)
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
        adds.forEach { if (it.id !in nextRemoved) nextObjects[it.id] = it }
        require(nextObjects.values.count { it.type == "image" } <= 8) { "A board supports up to eight images." }
        require(nextObjects.size <= Wire.MAX_ELEMENTS && nextRemoved.size <= Wire.MAX_REMOVED) { "This prototype board is full. Start a new board." }
        val snapshot = BoardMessage("snapshot", elements = nextObjects.values.toList(), removed = nextRemoved.toList())
        require(Wire.encode(snapshot).encodeToByteArray().size <= Wire.MAX_MESSAGE) { "This prototype board is full. Start a new board." }
        objects = nextObjects
        removed = nextRemoved
    }
}

fun constrained(start: Point, end: Point, type: String): Point {
    val dx = end.x - start.x; val dy = end.y - start.y
    if (type == "line" || type == "arrow") {
        val angle = round(atan2(dy, dx) / (PI / 4)) * (PI / 4)
        return Point(start.x + cos(angle) * hypot(dx, dy), start.y + sin(angle) * hypot(dx, dy))
    }
    val side = max(abs(dx), abs(dy))
    return Point(start.x + (if (dx < 0) -side else side), start.y + (if (dy < 0) -side else side))
}

fun outline(element: BoardElement): List<Point> {
    if (element.type == "pen" || element.type == "line") return element.points
    val a = element.points.first(); val b = element.points.last()
    if (element.type == "arrow") {
        val dx = b.x - a.x; val dy = b.y - a.y
        val distance = hypot(dx, dy)
        if (distance == 0.0) return listOf(a, b)
        val head = min(distance / 2, max(12.0, element.width * 3))
        val ux = dx / distance; val uy = dy / distance
        return listOf(a, b,
            Point(b.x - ux * head - uy * head / 2, b.y - uy * head + ux * head / 2), b,
            Point(b.x - ux * head + uy * head / 2, b.y - uy * head - ux * head / 2))
    }
    val x = min(a.x, b.x); val y = min(a.y, b.y); val w = abs(b.x - a.x); val h = abs(b.y - a.y)
    if (element.type in listOf("rectangle", "text", "sticky", "image")) return listOf(Point(x, y), Point(x + w, y), Point(x + w, y + h), Point(x, y + h), Point(x, y))
    return (0..96).map { i -> val angle = i / 96.0 * PI * 2; Point(x + w / 2 + cos(angle) * w / 2, y + h / 2 + sin(angle) * h / 2) }
}

private fun distance(p: Point, a: Point, b: Point): Double {
    val dx = b.x - a.x; val dy = b.y - a.y; val length = dx * dx + dy * dy
    val t = if (length == 0.0) 0.0 else (((p.x - a.x) * dx + (p.y - a.y) * dy) / length).coerceIn(0.0, 1.0)
    return hypot(p.x - a.x - t * dx, p.y - a.y - t * dy)
}
private fun cross(a: Point, b: Point, c: Point) = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)
fun hits(element: BoardElement, from: Point, to: Point, radius: Double): Boolean {
    if (element.text != null || element.image != null) {
        val a = element.points.first(); val b = element.points.last()
        fun inside(p: Point) = p.x in min(a.x,b.x)..max(a.x,b.x) && p.y in min(a.y,b.y)..max(a.y,b.y)
        if (inside(from) || inside(to)) return true
    }
    val points = outline(element)
    if (points.size == 1) return distance(points[0], from, to) <= radius + penWidth(element.width, points[0].pressure) / 2
    return points.zipWithNext().any { (a, b) ->
        val width = if (element.type == "pen") (penWidth(element.width, a.pressure) + penWidth(element.width, b.pressure)) / 2 else element.width
        val threshold = radius + width / 2
        val crossing = cross(from, to, a) * cross(from, to, b) < 0 && cross(a, b, from) * cross(a, b, to) < 0
        crossing || minOf(distance(a, from, to), distance(b, from, to), distance(from, a, b), distance(to, a, b)) <= threshold
    }
}
