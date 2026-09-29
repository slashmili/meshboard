package meshboard

import kotlinx.serialization.json.*

/** Debug simulator harness; this source directory is excluded from Release builds. */
class AppleInterop(network: AppleNetwork) {
    private val controller = AppleBoardController(network)
    private val snapshots = InteropSnapshot()
    fun command(raw: String) {
        val value = Json.parseToJsonElement(raw).jsonObject
        when (value.text("type")) {
            "share" -> controller.share(value.text("origin"))
            "join" -> controller.join(value.text("invite"))
            "put" -> controller.put(Wire.element(value.getValue("element")))
            "remove" -> controller.remove(value.getValue("ids").jsonArray.map { it.jsonPrimitive.content })
            "preview" -> controller.preview(value["element"]?.takeUnless { it == JsonNull }?.let(Wire::element))
            "leave" -> controller.leave()
            "retry" -> controller.retry()
            "close" -> controller.close()
        }
    }
    fun snapshot(): String? = snapshots.changed(controller.state.value)
}
