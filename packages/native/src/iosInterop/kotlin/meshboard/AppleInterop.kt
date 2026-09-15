package meshboard

import kotlinx.serialization.json.*

/** Debug simulator harness; this source directory is excluded from Release builds. */
class AppleInterop(network: AppleNetwork) {
    private val controller = AppleBoardController(network)
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
    fun snapshot(): String = controller.state.value.let { state -> buildJsonObject {
        put("connected", state.connected); put("relayed", state.relayed)
        put("invite", state.invite); put("signaling", state.signaling)
        put("error", state.error?.let(::JsonPrimitive) ?: JsonNull)
        put("elements", JsonArray(state.elements.map(Wire::elementJson)))
        put("previews", JsonArray(state.previews.map(Wire::elementJson)))
    }.toString() }
}
