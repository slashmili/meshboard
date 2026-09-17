package meshboard.crdt

import kotlinx.serialization.json.*
import meshboard.Wire

/** Simulator-only stdin/stdout harness. No socket, app entry point, or shipped test hook. */
fun main() {
    val request = Json.parseToJsonElement(readln()).jsonObject
    fun JsonElement.bytes() = jsonArray.map { value ->
        value.jsonPrimitive.int.also { require(it in 0..255) }.toByte()
    }.toByteArray()
    fun ByteArray.json() = JsonArray(map { JsonPrimitive(it.toInt() and 255) })
    val board = AppleCrdtBoard(request.getValue("clientId").jsonPrimitive.long)
    try {
        request["updates"]?.jsonArray?.forEach { board.apply(it.bytes()) }
        request["operations"]?.jsonArray?.forEach { raw ->
            val operation = raw.jsonObject
            val id = operation.getValue("id").jsonPrimitive.content
            when (operation.getValue("type").jsonPrimitive.content) {
                "put" -> board.put(Wire.element(operation.getValue("element")).also { require(it.id == id) })
                "remove" -> board.remove(id)
                else -> error("Unknown operation")
            }
        }
        val target = request["targetStateVector"]?.bytes() ?: byteArrayOf(0)
        println(buildJsonObject {
            put("elements", JsonObject(board.elements().associate { it.id to Wire.elementJson(it) }))
            put("stateVector", board.stateVector().json())
            put("update", board.update(target).json())
        })
    } finally { board.close() }
}
