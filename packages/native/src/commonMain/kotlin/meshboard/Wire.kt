package meshboard

import kotlinx.serialization.json.*

fun JsonObject.exact(vararg keys: String) { require(this.keys == keys.toSet()) { "Unexpected protocol fields" } }
fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.let { require(it.isString); it.content }
fun JsonObject.number(key: String): Double = getValue(key).jsonPrimitive.let { require(!it.isString); it.double.also { n -> require(n.isFinite()) } }
fun JsonObject.integer(key: String): Int = number(key).let { require(it == it.toInt().toDouble()); it.toInt() }
fun JsonObject.version() { require(integer("v") == 1) { "Unsupported protocol version" } }
val uuidPattern = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")

object Wire {
    const val MAX_ELEMENTS = 2_000
    const val MAX_REMOVED = 10_000
    const val MAX_POINTS = 12_000
    const val MAX_MESSAGE = 4 * 1024 * 1024
    const val MAX_FRAME = 16 * 1024
    private val idPattern = Regex("^[a-zA-Z0-9-]{1,80}$")

    fun element(value: JsonElement): BoardElement {
        val o = value.jsonObject
        o.exact("id", "type", "color", "width", "points")
        val id = o.text("id"); require(idPattern.matches(id))
        val type = o.text("type"); require(type in listOf("pen", "rectangle", "ellipse", "line"))
        val color = o.text("color"); require(Regex("^#[0-9a-fA-F]{6}$").matches(color))
        val width = o.number("width"); require(width in 1.0..32.0)
        val data = o.getValue("points").jsonArray; require(data.size in 1..MAX_POINTS && (type == "pen" || data.size == 2))
        val points = data.map { item ->
            val p = item.jsonObject; p.exact("x", "y")
            val x = p.number("x"); val y = p.number("y")
            require(x in -1e7..1e7 && y in -1e7..1e7)
            Point(x, y)
        }
        return BoardElement(id, type, color, width, points)
    }
    fun elementJson(e: BoardElement) = buildJsonObject {
        put("id", e.id); put("type", e.type); put("color", e.color); put("width", e.width)
        putJsonArray("points") { e.points.forEach { p -> add(buildJsonObject { put("x", p.x); put("y", p.y) }) } }
    }
    private fun ids(o: JsonObject, key: String): List<String> {
        val data = o.getValue(key).jsonArray; require(data.size <= MAX_REMOVED)
        return data.map { require(it.jsonPrimitive.isString); it.jsonPrimitive.content.also { id -> require(idPattern.matches(id)) } }
    }
    fun decode(text: String): BoardMessage {
        require(text.encodeToByteArray().size <= MAX_MESSAGE)
        val o = Json.parseToJsonElement(text).jsonObject
        o.version()
        return when (val type = o.text("type")) {
            "put" -> { o.exact("v", "type", "element"); BoardMessage(type, element = element(o.getValue("element"))) }
            "preview" -> { o.exact("v", "type", "element"); BoardMessage(type, element = o.getValue("element").takeUnless { it == JsonNull }?.let(::element)) }
            "remove" -> { o.exact("v", "type", "ids"); BoardMessage(type, ids = ids(o, "ids")) }
            "snapshot" -> {
                o.exact("v", "type", "elements", "removed")
                val data = o.getValue("elements").jsonArray; require(data.size <= MAX_ELEMENTS)
                BoardMessage(type, elements = data.map(::element), removed = ids(o, "removed"))
            }
            else -> error("Unsupported board message")
        }
    }
    fun encode(m: BoardMessage): String = buildJsonObject {
        put("v", 1); put("type", m.type)
        when (m.type) {
            "put", "preview" -> put("element", m.element?.let(::elementJson) ?: JsonNull)
            "remove" -> put("ids", JsonArray(m.ids.map(::JsonPrimitive)))
            "snapshot" -> { put("elements", JsonArray(m.elements.map(::elementJson))); put("removed", JsonArray(m.removed.map(::JsonPrimitive))) }
            else -> error("Unsupported board message")
        }
    }.toString()
    fun frames(message: BoardMessage, id: String): List<String> {
        require(uuidPattern.matches(id))
        val text = encode(message); require(text.encodeToByteArray().size <= MAX_MESSAGE)
        return text.chunked(8_000).let { chunks -> chunks.mapIndexed { index, data ->
            buildJsonObject { put("v", 1); put("id", id); put("index", index); put("total", chunks.size); put("data", data) }.toString()
        } }
    }
}

class FrameReceiver(private val now: () -> Long) {
    private var id: String? = null
    private var total = 0
    private var next = 0
    private var started = 0L
    private var bytes = 0
    private val text = StringBuilder()
    fun accept(raw: String): BoardMessage? {
        require(raw.encodeToByteArray().size <= Wire.MAX_FRAME)
        val frame = Json.parseToJsonElement(raw).jsonObject
        frame.exact("v", "id", "index", "total", "data"); frame.version()
        val frameId = frame.text("id"); require(uuidPattern.matches(frameId))
        val index = frame.integer("index"); val count = frame.integer("total"); val chunk = frame.text("data")
        require(index in 0..1023 && count in 1..1024 && chunk.length <= 8_000)
        if (index == 0) { require(id == null); id = frameId; total = count; next = 0; started = now(); bytes = 0; text.clear() }
        require(id == frameId && total == count && index == next && now() - started <= 30_000) { "Invalid frame sequence" }
        bytes += chunk.encodeToByteArray().size; require(bytes <= Wire.MAX_MESSAGE)
        text.append(chunk); next++
        if (next != total) return null
        val result = Wire.decode(text.toString())
        id = null; text.clear()
        return result
    }
}
