package meshboard

import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Test-only stdin/stdout adapter; all board and network work uses the real controller. */
fun main(args: Array<String>) = runBlocking {
    val controller = DesktopController(forceRelay = "--relay" in args)
    val collector = launch(Dispatchers.Default) {
        controller.state.collect { state ->
            val value = buildJsonObject {
                put("connected", state.connected); put("relayed", state.relayed)
                put("invite", state.invite); put("signaling", state.signaling)
                put("error", state.error?.let(::JsonPrimitive) ?: JsonNull)
                put("elements", JsonArray(state.elements.map(Wire::elementJson)))
                put("previews", JsonArray(state.previews.map(Wire::elementJson)))
            }
            println("MESHBOARD $value")
        }
    }
    try {
        while (true) {
            val line = readlnOrNull() ?: break
            val command = Json.parseToJsonElement(line).jsonObject
            when (command.text("type")) {
                "share" -> controller.share(command.text("origin"))
                "join" -> controller.join(command.text("invite"))
                "put" -> controller.put(Wire.element(command.getValue("element")))
                "remove" -> controller.remove(command.getValue("ids").jsonArray.map { it.jsonPrimitive.content })
                "preview" -> controller.preview(command["element"]?.takeUnless { it == JsonNull }?.let(Wire::element))
                "leave" -> controller.leave()
                "retry" -> controller.retry()
                "close" -> break
                else -> error("Unknown harness command")
            }
        }
    } finally { controller.close(); collector.cancelAndJoin() }
}
