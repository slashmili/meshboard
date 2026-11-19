package meshboard

import kotlinx.serialization.json.*

fun JsonObject.exact(vararg keys: String) { require(this.keys == keys.toSet()) { "Unexpected protocol fields" } }
fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.let { require(it.isString); it.content }
fun JsonObject.number(key: String): Double = getValue(key).jsonPrimitive.let { require(!it.isString); it.double.also { n -> require(n.isFinite()) } }
val uuidPattern = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")

object Wire {
    const val MAX_REMOVED = 10_000
    const val MAX_ELEMENTS = 2_000
    const val MAX_POINTS = 12_000
    const val MAX_MESSAGE = 4 * 1024 * 1024
    private val idPattern = Regex("^[a-zA-Z0-9-]{1,80}$")

    fun element(value: JsonElement): BoardElement {
        val raw = value.jsonObject
        val o = JsonObject(raw.filterKeys { it != "startAttachment" && it != "endAttachment" })
        if (o["type"]?.jsonPrimitive?.content in listOf("text", "sticky")) o.exact("id", "type", "color", "width", "points", "text")
        else if (o["type"]?.jsonPrimitive?.content == "image") o.exact("id", "type", "color", "width", "points", "image")
        else o.exact("id", "type", "color", "width", "points")
        val id = o.text("id"); require(idPattern.matches(id))
        val type = o.text("type"); require(type in listOf("pen", "rectangle", "ellipse", "line", "arrow", "text", "sticky", "image"))
        val color = o.text("color"); require(Regex("^#[0-9a-fA-F]{6}$").matches(color))
        val width = o.number("width"); require(width in 1.0..32.0)
        val data = o.getValue("points").jsonArray; require(data.size in 1..MAX_POINTS && (type == "pen" || data.size == 2))
        val points = data.map { item ->
            val p = item.jsonObject
            val pressure = if ("pressure" in p) {
                require(type == "pen"); p.exact("x", "y", "pressure")
                p.number("pressure").also { require(it in 0.0..1.0) }
            } else { p.exact("x", "y"); null }
            val x = p.number("x"); val y = p.number("y")
            require(x in -1e7..1e7 && y in -1e7..1e7)
            Point(x, y, pressure)
        }
        val text = if (type in listOf("text", "sticky")) o.text("text").also { require(validText(it)) } else null
        val image = if (type == "image") o.text("image").also { imageBytes(it) } else null
        fun attachment(key: String): Attachment? = raw[key]?.let {
            require(type == "arrow")
            val a = it.jsonObject; a.exact("id","x","y")
            val target = a.text("id"); require(idPattern.matches(target) && target != id)
            val x = a.number("x"); val y = a.number("y"); require(x in 0.0..1.0 && y in 0.0..1.0)
            Attachment(target,x,y)
        }
        return BoardElement(id, type, color, width, points, text, image, attachment("startAttachment"), attachment("endAttachment"))
    }
    fun elementJson(e: BoardElement) = buildJsonObject {
        fun anchor(key: String, a: Attachment?) { a?.let { put(key,buildJsonObject { put("id",it.id); put("x",it.x); put("y",it.y) }) } }
        anchor("startAttachment",e.startAttachment); anchor("endAttachment",e.endAttachment)
        e.image?.let { put("image", it) }
        e.text?.let { put("text", it) }
        put("id", e.id); put("type", e.type); put("color", e.color); put("width", e.width)
        putJsonArray("points") { e.points.forEach { p -> add(buildJsonObject { put("x", p.x); put("y", p.y); p.pressure?.let { put("pressure", it) } }) } }
    }
}
