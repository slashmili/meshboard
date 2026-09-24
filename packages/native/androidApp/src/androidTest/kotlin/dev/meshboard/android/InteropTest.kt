package dev.meshboard.android

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import meshboard.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

/** Test APK only: a loopback command adapter forwarded by adb, never part of the app. */
class InteropTest {
    @Test fun peer() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Run with test:interop:android", args.getString("meshboard.interop") == "true")
        check(BuildConfig.CRDT_PREVIEW == (args.getString("meshboard.crdt") == "true")) { "Install the matching legacy/CRDT Android test build." }
        val controller = AndroidController(InstrumentationRegistry.getInstrumentation().targetContext, args.getString("meshboard.relay") == "true")
        try {
            ServerSocket(18765, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = 30_000
                server.accept().use { socket ->
                    socket.soTimeout = 120_000
                    val output = socket.getOutputStream().bufferedWriter()
                    val collector = launch(Dispatchers.IO) {
                        controller.state.collect { state ->
                            val value = buildJsonObject {
                                put("connected", state.connected); put("relayed", state.relayed)
                                put("invite", state.invite); put("signaling", state.signaling)
                                put("error", state.error?.let(::JsonPrimitive) ?: JsonNull)
                                put("elements", JsonArray(state.elements.map(Wire::elementJson)))
                                put("previews", JsonArray(state.previews.map(Wire::elementJson)))
                            }
                            output.write("MESHBOARD $value\n"); output.flush()
                        }
                    }
                    try {
                        val input = socket.getInputStream().bufferedReader()
                        while (true) {
                            val line = input.readLine() ?: break
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
                    } finally { collector.cancelAndJoin() }
                }
            }
        } finally { controller.close() }
    }
}
