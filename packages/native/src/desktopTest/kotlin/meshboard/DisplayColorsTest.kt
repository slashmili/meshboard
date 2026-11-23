package meshboard

import java.io.File
import java.util.prefs.Preferences
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DisplayColorsTest {
    private val fixture = Json.parseToJsonElement(File(System.getProperty("meshboard.fixtures"), "appearance.json").readText()).jsonObject

    @Test fun displayColorsMatchTheSharedFixture() {
        val canvas = fixture.getValue("canvas").jsonObject
        for ((name, colors) in listOf("light" to DisplayColors.light, "dark" to DisplayColors.dark)) {
            val expected = canvas.getValue(name).jsonObject
            assertEquals(CanvasColors(expected.text("paper"), expected.text("grid"), expected.text("selection"), expected.text("handle")), colors)
        }
        assertEquals(fixture.getValue("minDarkContrast").jsonPrimitive.double, DisplayColors.MIN_DARK_CONTRAST)
        fixture.getValue("display").jsonArray.map { it.jsonObject }.forEach {
            assertEquals(it.text("light"), DisplayColors.display(it.text("color"), false), it.text("color"))
            assertEquals(it.text("dark"), DisplayColors.display(it.text("color"), true), it.text("color"))
            assertTrue(DisplayColors.contrast(DisplayColors.display(it.text("color"), true), DisplayColors.dark.paper) >= DisplayColors.MIN_DARK_CONTRAST)
        }
    }

    @Test fun desktopPreferencesPersistOnlyTheAppearanceChoice() {
        val node = Preferences.userRoot().node("dev/meshboard/test-${System.nanoTime()}")
        try {
            desktopAppearanceStore(node).save(Appearance.Dark)
            assertEquals(Appearance.Dark, desktopAppearanceStore(node).load())
            assertEquals(listOf(KeyValueAppearanceStore.KEY), node.keys().toList())
        } finally { node.removeNode() }
    }

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
}
