package meshboard

import kotlinx.serialization.json.*

/** Debug simulator harness; this source directory is excluded from Release builds. */
class AppleInterop(network: AppleNetwork) {
    private var trace: InteropCrdtTrace? = null
    private val controller = AppleBoardController(network) {
        InteropCrdtTrace(meshboard.crdt.AppleCrdtBoard()).also { trace = it }
    }
    private val snapshots = InteropSnapshot(awaitReceipt = true)
    private var previousProgress: JsonObject? = null
    fun progress(): String? {
        val current = checkNotNull(trace).snapshot()
        if (current === previousProgress) return null
        previousProgress = current
        return buildJsonObject { put("crdt", current) }.toString()
    }
    fun command(raw: String) {
        val value = Json.parseToJsonElement(raw).jsonObject
        when (value.text("type")) {
            "snapshot-received" -> snapshots.received()
            "share" -> controller.share(value.text("origin"))
            "join" -> controller.join(value.text("invite"))
            "put" -> controller.put(Wire.element(value.getValue("element")))
            "undo" -> controller.undo()
            "redo" -> controller.redo()
            "move" -> controller.move(value.getValue("ids").jsonArray.map { it.jsonPrimitive.content }, meshboard.Point(value.getValue("dx").jsonPrimitive.double, value.getValue("dy").jsonPrimitive.double))
            "remove" -> controller.remove(value.getValue("ids").jsonArray.map { it.jsonPrimitive.content })
            "preview" -> controller.preview(value["element"]?.takeUnless { it == JsonNull }?.let(Wire::element))
            "leave" -> controller.leave()
            "retry" -> controller.retry()
            "close" -> controller.close()
        }
    }
    fun snapshot(): String? = snapshots.changed(controller.state.value)?.let { raw ->
        JsonObject(Json.parseToJsonElement(raw).jsonObject + ("crdt" to checkNotNull(trace).snapshot())).toString()
    }
}
