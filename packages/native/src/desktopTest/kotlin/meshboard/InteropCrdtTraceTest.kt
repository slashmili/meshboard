package meshboard

import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlinx.serialization.json.*
import meshboard.crdt.JvmCrdtBoard

class InteropCrdtTraceTest {
    @Test fun traceReportsActualDocumentClocksWithoutElementContent() {
        val traced = InteropCrdtTrace(JvmCrdtBoard())
        val remote = JvmCrdtBoard()
        val element = BoardElement("private-id", "text", "#123456", 3.0,
            listOf(Point(10.0, 20.0), Point(100.0, 60.0)), text = "private text")
        try {
            traced.put(element)
            assertEquals("put", traced.snapshot().getValue("operation").jsonPrimitive.content)
            remote.apply(traced.update())
            remote.remove(element.id)
            val update = remote.update(traced.stateVector())
            traced.apply(update)
            val diagnostic = traced.snapshot()
            assertTrue(traced.elements().isEmpty())
            assertEquals("apply", diagnostic.getValue("operation").jsonPrimitive.content)
            assertEquals(2, diagnostic.getValue("operations").jsonPrimitive.int)
            assertEquals(update.size, diagnostic.getValue("inputBytes").jsonPrimitive.int)
            assertEquals(Base64.encode(traced.stateVector()), diagnostic.getValue("stateVector").jsonPrimitive.content)
            assertFalse(diagnostic.toString().contains("private"))
            assertTrue(diagnostic.getValue("durationMs").jsonPrimitive.long >= 0)
        } finally { traced.close(); remote.close() }
    }
}
