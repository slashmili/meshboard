package meshboard

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertFalse

class DesktopAppearanceTest {
    @get:Rule val rule = createComposeRule()

    /** Opt in only on a Linux desktop: temporarily changes and restores its OS preference. */
    @Test fun linuxOsAppearanceChangesReachTheRunningCanvas() {
        assumeTrue(System.getenv("MESHBOARD_TEST_LINUX_APPEARANCE") == "1" && System.getProperty("os.name").startsWith("Linux"))
        fun settings(vararg args: String): String {
            val process = ProcessBuilder("gsettings", *args).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText().trim()
            check(process.waitFor() == 0) { output }
            return output
        }
        val original = settings("get", "org.gnome.desktop.interface", "color-scheme")
        val controller = DesktopController()
        try {
            controller.put(BoardElement("os-theme-stroke", "pen", "#293b36", 3.0, listOf(Point(350.0, 250.0), Point(460.0, 330.0))))
            rule.waitUntil(5_000) { controller.state.value.elements.size == 1 }
            val before = controller.state.value
            val store = MemoryAppearanceStore()
            rule.setContent { MeshboardApp(controller, appearanceStore = store) }
            fun shows(dark: Boolean) {
                val expected = if (dark) 0x161b17 else 0xf8f9f6
                rule.waitUntil(10_000) {
                    val image = rule.onNodeWithTag("drawing-canvas").captureToImage().toAwtImage()
                    image.getRGB(image.width - 20, image.height / 2) and 0xffffff == expected
                }
            }
            settings("set", "org.gnome.desktop.interface", "color-scheme", "prefer-light"); shows(false)
            settings("set", "org.gnome.desktop.interface", "color-scheme", "prefer-dark"); shows(true)
            settings("set", "org.gnome.desktop.interface", "color-scheme", "prefer-light"); shows(false)
            rule.runOnIdle { kotlin.test.assertEquals(before, controller.state.value) }
        } finally {
            try { settings("set", "org.gnome.desktop.interface", "color-scheme", original) }
            finally { controller.close() }
        }
    }

    @Test fun systemChangesRefreshWithoutRestartAndExplicitChoicesOverrideThem() {
        val detected = AtomicBoolean(false)
        val mode = mutableStateOf(Appearance.System)
        val onUiThread = AtomicBoolean(false)
        rule.setContent {
            val systemDark = rememberDesktopSystemDark(read = {
                if (javax.swing.SwingUtilities.isEventDispatchThread()) onUiThread.set(true)
                detected.get()
            }, refreshMillis = 20)
            Text(if (mode.value.isDark(systemDark)) "Dark canvas" else "Light canvas")
        }
        fun shows(dark: Boolean) {
            rule.waitUntil(5_000) {
                rule.onAllNodesWithText(if (dark) "Dark canvas" else "Light canvas").fetchSemanticsNodes().size == 1
            }
        }
        shows(false)
        detected.set(true); shows(true)
        rule.runOnIdle { mode.value = Appearance.Light }; shows(false)
        detected.set(false)
        rule.runOnIdle { mode.value = Appearance.Dark }; shows(true)
        rule.runOnIdle { mode.value = Appearance.System }; shows(false)
        detected.set(true); shows(true)
        assertFalse(onUiThread.get(), "Refresh queries must not block the UI thread")
    }
}
