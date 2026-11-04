package meshboard

import kotlin.test.*
import meshboard.crdt.JvmCrdtBoard

class DefaultCrdtTest {
    @Test fun packagedLibraryLoadsWithoutAnExternalLibraryPath() {
        assertNull(System.getProperty("meshboard.crdt.library"))
        JvmCrdtBoard().use { board ->
            assertTrue(board.elements().isEmpty())
            assertTrue(board.stateVector().isNotEmpty())
        }
    }
    @Test fun normalControllerCreatesCrdtInvites() {
        DesktopController().use { controller ->
            controller.share("https://board.example.com")
            val deadline = System.currentTimeMillis() + 5000
            while (controller.state.value.invite.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
            assertTrue(controller.state.value.invite.startsWith("https://board.example.com/#crdt="))
        }
    }
    @Test fun normalInviteParserRejectsOldProtocol() {
        val id = "12345678-1234-4234-8234-123456789abc"
        assertEquals("https://board.example.com" to id, DesktopController.parseInvite("https://board.example.com/#crdt=$id"))
        assertFailsWith<IllegalArgumentException> { DesktopController.parseInvite("https://board.example.com/#room=$id") }
    }
}
