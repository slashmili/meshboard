package meshboard

import java.util.prefs.Preferences
import androidx.compose.runtime.*
import androidx.compose.foundation.isSystemInDarkTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.skiko.SystemTheme
import org.jetbrains.skiko.currentSystemTheme

/** Persists only the local appearance choice; board data is never stored here. */
fun desktopAppearanceStore(node: Preferences = Preferences.userRoot().node("dev/meshboard/desktop")): AppearanceStore =
    KeyValueAppearanceStore(read = { node.get(it, null) }, write = { key, value -> node.put(key, value); runCatching { node.flush() } })

/**
 * Compose 1.9.3's desktop system-theme default is read once, without an OS
 * listener. Refresh Skiko's native query while this view is mounted. Keep the
 * potentially blocking Linux portal query off the UI thread.
 */
@Composable
internal fun rememberDesktopSystemDark(
    read: () -> Boolean = { currentSystemTheme == SystemTheme.DARK },
    refreshMillis: Long = 1_000,
): Boolean {
    val currentRead by rememberUpdatedState(read)
    val initialDark = isSystemInDarkTheme()
    val dark by produceState(initialDark) {
        while (true) {
            val detected = withContext(Dispatchers.IO) { currentRead() }
            value = detected
            delay(refreshMillis)
        }
    }
    return dark
}
