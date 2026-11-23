package meshboard

import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test fun systemIsTheDefaultAndUnknownValuesFallBackToIt() {
        assertEquals(Appearance.System, MemoryAppearanceStore().load())
        assertEquals(Appearance.System, Appearance.parse(null))
        assertEquals(Appearance.System, Appearance.parse("Sepia"))
        assertEquals(Appearance.Dark, Appearance.parse("Dark"))
    }

    @Test fun keyValueStoreRoundTripsExplicitChoices() {
        val values = mutableMapOf<String, String>()
        val store = KeyValueAppearanceStore(read = { values[it] }, write = { key, value -> values[key] = value })
        assertEquals(Appearance.System, store.load())
        Appearance.entries.forEach { store.save(it); assertEquals(it, store.load()) }
        assertEquals(setOf(KeyValueAppearanceStore.KEY), values.keys)
        // A new store over the same storage models an app restart.
        store.save(Appearance.Light)
        assertEquals(Appearance.Light, KeyValueAppearanceStore(read = { values[it] }, write = { _, _ -> }).load())
    }

    @Test fun darkMappingIsDisplayOnlyAndLeavesLightModeCanonical() {
        assertEquals("#293b36", DisplayColors.display("#293b36", false))
        assertEquals("#c4d6d1", DisplayColors.display("#293b36", true))
        assertEquals("#d76857", DisplayColors.display("#D76857", true))
        assertEquals("bogus", DisplayColors.display("bogus", true))
    }
}
