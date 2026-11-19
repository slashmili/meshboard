package meshboard

import java.io.File
import kotlinx.serialization.json.*
import kotlin.test.*

class TextElementsTest {
    @Test fun sharedTextFixturesValidateAndWrap() {
        val fixtures = Json.parseToJsonElement(File(System.getProperty("meshboard.fixtures"), "text-elements.json").readText()).jsonObject
        fixtures.getValue("valid").jsonArray.forEach {
            val element = Wire.element(it)
            assertEquals(element, Wire.element(Wire.elementJson(element)))
        }
        fixtures.getValue("invalid").jsonArray.forEach { assertFails { Wire.element(it) } }
        assertFalse(validText("\ud800"))
        fixtures.getValue("layout").jsonArray.forEach {
            val value = it.jsonObject
            assertEquals(value.getValue("lines").jsonArray.map { line -> line.jsonPrimitive.content }, textLines(value.text("text")))
        }
    }
    @Test fun noteSizeAndInteriorEraseMatchWeb() {
        val element = textElement("note", "sticky", Point(10.0,20.0), "#293b36", "a\nb\nc")
        assertEquals(Point(298.0,116.0), element.points.last())
        assertTrue(hits(element, Point(50.0,50.0), Point(50.0,50.0), 0.0))
        assertTrue(hits(element, Point(-20.0,50.0), Point(320.0,50.0), 0.0))
        assertFalse(hits(element, Point(-20.0,-20.0), Point(-10.0,-10.0), 0.0))
    }
}
