package meshboard

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color

/** Appearance is a local view preference; it changes rendering, never shared drawing data. */
enum class Appearance { System, Light, Dark;
    fun isDark(systemDark: Boolean) = this == Dark || (this == System && systemDark)
    companion object {
        fun parse(name: String?): Appearance = entries.firstOrNull { it.name == name } ?: System
    }
}

/** Platform hosts persist the explicit choice locally (Preferences, SharedPreferences, NSUserDefaults). */
interface AppearanceStore {
    fun load(): Appearance
    fun save(appearance: Appearance)
}

class MemoryAppearanceStore(private var value: Appearance = Appearance.System) : AppearanceStore {
    override fun load() = value
    override fun save(appearance: Appearance) { value = appearance }
}

/** Key/value adapter shared by the platform stores. */
class KeyValueAppearanceStore(private val read: (String) -> String?, private val write: (String, String) -> Unit) : AppearanceStore {
    override fun load() = Appearance.parse(read(KEY))
    override fun save(appearance: Appearance) = write(KEY, appearance.name)
    companion object { const val KEY = "meshboard.appearance" }
}

@Composable
fun rememberAppearance(store: AppearanceStore): MutableState<Appearance> {
    val state = remember(store) { mutableStateOf(store.load()) }
    LaunchedEffect(store, state.value) { if (store.load() != state.value) store.save(state.value) }
    return state
}

@Composable
fun Appearance.resolveDark(): Boolean = isDark(isSystemInDarkTheme())

fun appearanceColors(dark: Boolean): ColorScheme =
    if (dark) darkColorScheme(
        primary = Color(0xffb5d6ac), onPrimary = Color(0xff193824),
        surface = Color(0xff202922), onSurface = Color(0xffe4ede1),
        onSurfaceVariant = Color(0xffbac6b4), outline = Color(0xff667260),
        primaryContainer = Color(0xff354a30), onPrimaryContainer = Color(0xffd8edc9),
        surfaceContainerHigh = Color(0xff26302a), background = Color(0xff161b17), onBackground = Color(0xffe4ede1),
        errorContainer = Color(0xff3a3020), onErrorContainer = Color(0xfff1dfbd),
    ) else lightColorScheme(
        primary = Color(0xff254d3c), onPrimary = Color.White,
        surface = Color.White, onSurface = Color(0xff293b36),
        onSurfaceVariant = Color(0xff617059), outline = Color(0xffe0e5dc),
        primaryContainer = Color(0xffe5efd8), onPrimaryContainer = Color(0xff254d3c),
        background = Color(0xfff8f9f6), onBackground = Color(0xff293b36),
        errorContainer = Color(0xfffff2d9), onErrorContainer = Color(0xff293b36),
    )

@Composable
fun AppearanceOptions(value: Appearance, onChange: (Appearance) -> Unit) {
    Row {
        Appearance.entries.forEach { mode ->
            FilterChip(selected = mode == value, onClick = { onChange(mode) }, label = { Text(mode.name) })
        }
    }
}

@Composable
fun BoardTheme(colors: ColorScheme, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors) {
        CompositionLocalProvider(LocalContentColor provides colors.onSurface, content = content)
    }
}
