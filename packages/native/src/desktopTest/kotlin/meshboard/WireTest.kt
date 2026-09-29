package meshboard

import java.io.File
import kotlinx.serialization.json.*
import kotlin.test.*

class WireTest {
    @Test fun sharedTypeScriptKotlinFixtures() {
        val fixtures = Json.parseToJsonElement(File(System.getProperty("meshboard.fixtures"), "board-messages.json").readText()).jsonObject
        fixtures.getValue("valid").jsonArray.forEach { fixture ->
            val decoded = Wire.decode(fixture.toString())
            assertEquals(decoded, Wire.decode(Wire.encode(decoded)))
        }
        fixtures.getValue("invalid").jsonArray.forEach { fixture -> assertFails(fixture.toString()) { Wire.decode(fixture.toString()) } }
    }
    @Test fun invitesRequireSupportedOriginAndRoom() {
        val room = "123e4567-e89b-42d3-a456-426614174000"
        assertEquals("http://127.0.0.1:5173" to room, DesktopController.parseInvite("http://127.0.0.1:5173/#room=$room"))
        for (url in listOf("http://192.168.1.2/#room=$room", "https://example.org/", "https://example.org/#room=bad", "https://user:pass@example.org/#room=$room", "file:///tmp/#room=$room")) {
            assertFails(url) { DesktopController.parseInvite(url) }
        }
    }
    @Test fun crdtPreviewInvitesCannotJoinLegacySessions() {
        val room = "123e4567-e89b-42d3-a456-426614174000"
        val preview = "http://127.0.0.1:5174/?crdt=1#crdt=$room"
        assertEquals("http://127.0.0.1:5174" to room, DesktopController.parseInvite(preview, crdt = true))
        assertFails { DesktopController.parseInvite(preview) }
        assertFails { DesktopController.parseInvite("http://127.0.0.1:5174/#room=$room", crdt = true) }
    }
}
