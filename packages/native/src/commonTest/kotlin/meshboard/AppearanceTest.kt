package meshboard

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppearanceTest {
    @Test fun systemTracksDeviceAndExplicitModesOverrideIt() {
        assertFalse(Appearance.System.isDark(false))
        assertTrue(Appearance.System.isDark(true))
        assertFalse(Appearance.Light.isDark(false))
        assertFalse(Appearance.Light.isDark(true))
        assertTrue(Appearance.Dark.isDark(false))
        assertTrue(Appearance.Dark.isDark(true))
    }
}
