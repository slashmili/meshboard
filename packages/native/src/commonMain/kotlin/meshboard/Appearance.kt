package meshboard

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Color

/** Appearance affects controls, never the shared drawing's colors. */
enum class Appearance { System, Light, Dark;
    fun isDark(systemDark: Boolean) = this == Dark || (this == System && systemDark)
}

@Composable
fun rememberAppearance() = rememberSaveable { mutableStateOf(Appearance.System) }

@Composable
fun appearanceColors(appearance: Appearance): ColorScheme =
    if (appearance.isDark(isSystemInDarkTheme())) darkColorScheme(
        primary = Color(0xffb5d6ac), onPrimary = Color(0xff193824),
        surface = Color(0xff202922), onSurface = Color(0xffe4ede1),
        onSurfaceVariant = Color(0xffbac6b4), outline = Color(0xff667260),
        primaryContainer = Color(0xff354a30), onPrimaryContainer = Color(0xffd8edc9),
    ) else lightColorScheme(
        primary = Color(0xff254d3c), onPrimary = Color.White,
        surface = Color.White, onSurface = Color(0xff293b36),
        onSurfaceVariant = Color(0xff617059), outline = Color(0xffe0e5dc),
        primaryContainer = Color(0xffe5efd8), onPrimaryContainer = Color(0xff254d3c),
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
