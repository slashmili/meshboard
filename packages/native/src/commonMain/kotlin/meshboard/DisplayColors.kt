package meshboard

import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Local, display-only canvas colors. Mirrors packages/web/src/appearance.ts and
 * is checked against shared-protocol/fixtures/appearance.json. Canonical
 * element colors in CRDT state are never rewritten.
 */
data class CanvasColors(val paper: String, val grid: String, val selection: String, val handle: String)

object DisplayColors {
    val light = CanvasColors(paper = "#f8f9f6", grid = "#cbd3ca", selection = "#4878c8", handle = "#387c59")
    val dark = CanvasColors(paper = "#161b17", grid = "#3a463d", selection = "#7ea6ea", handle = "#6fbf94")
    const val MIN_DARK_CONTRAST = 4.5

    fun canvas(dark: Boolean) = if (dark) this.dark else light

    private val hex = Regex("^#[0-9a-f]{6}$")
    private fun rgb(color: String) = listOf(1, 3, 5).map { color.substring(it, it + 2).toInt(16) }
    private fun channel(value: Int): Double { val c = value / 255.0; return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4) }
    fun luminance(color: String): Double { val (r, g, b) = rgb(color).map(::channel); return 0.2126 * r + 0.7152 * g + 0.0722 * b }
    fun contrast(a: String, b: String): Double {
        val x = luminance(a); val y = luminance(b)
        return (max(x, y) + 0.05) / (min(x, y) + 0.05)
    }

    private fun toHsl(color: String): Triple<Double, Double, Double> {
        val (r, g, b) = rgb(color).map { it / 255.0 }
        val high = max(r, max(g, b)); val low = min(r, min(g, b)); val l = (high + low) / 2
        if (high == low) return Triple(0.0, 0.0, l)
        val d = high - low
        val s = if (l > 0.5) d / (2 - high - low) else d / (high + low)
        val h = when (high) { r -> (g - b) / d + (if (g < b) 6 else 0); g -> (b - r) / d + 2; else -> (r - g) / d + 4 }
        return Triple(h / 6, s, l)
    }
    private fun hue(p: Double, q: Double, value: Double): Double {
        var t = value
        if (t < 0) t += 1.0
        if (t > 1) t -= 1.0
        return when {
            t < 1.0 / 6 -> p + (q - p) * 6 * t
            t < 1.0 / 2 -> q
            t < 2.0 / 3 -> p + (q - p) * (2.0 / 3 - t) * 6
            else -> p
        }
    }
    private fun fromHsl(h: Double, s: Double, l: Double): String {
        val q = if (l < 0.5) l * (1 + s) else l + s - l * s; val p = 2 * l - q
        val values = if (s == 0.0) listOf(l, l, l) else listOf(hue(p, q, h + 1.0 / 3), hue(p, q, h), hue(p, q, h - 1.0 / 3))
        return "#" + values.joinToString("") { (it * 255).roundToInt().toString(16).padStart(2, '0') }
    }

    /**
     * Deterministic dark-canvas rendering of a canonical color: legible colors are kept;
     * others keep hue/saturation, mirror lightness and rise in 1% steps until 4.5:1.
     */
    fun display(color: String, dark: Boolean): String {
        val value = color.lowercase()
        if (!dark || !hex.matches(value) || contrast(value, this.dark.paper) >= MIN_DARK_CONTRAST) return value
        val (h, s, l) = toHsl(value)
        val start = max(l, 1 - l)
        for (step in 0..100) {
            val candidate = fromHsl(h, s, min(1.0, start + step * 0.01))
            if (contrast(candidate, this.dark.paper) >= MIN_DARK_CONTRAST) return candidate
        }
        return "#ffffff"
    }
}

fun hexColor(hex: String): Color = Color(0xff000000 or hex.drop(1).toLong(16))
fun displayPaint(hex: String, dark: Boolean): Color = hexColor(DisplayColors.display(hex, dark))
