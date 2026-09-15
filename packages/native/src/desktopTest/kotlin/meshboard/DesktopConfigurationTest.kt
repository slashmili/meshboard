package meshboard

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopConfigurationTest {
    @Test fun localBuildsDefaultToLoopback() {
        assertEquals("http://127.0.0.1:5173", desktopAppOrigin(null, null))
    }
    @Test fun packagedOriginIsUsedWithoutAnEnvironmentOverride() {
        assertEquals("https://board.example.com", desktopAppOrigin(null, "https://board.example.com"))
    }
    @Test fun selfHostingOverrideTakesPrecedence() {
        assertEquals("https://other.example.com", desktopAppOrigin("https://other.example.com", "https://board.example.com"))
    }
}
