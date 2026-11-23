/**
 * Local, display-only appearance. Canonical element colors stay unchanged in
 * CRDT state; native mirrors this logic in DisplayColors.kt and both are
 * checked against shared-protocol/fixtures/appearance.json.
 */
export const CANVAS = {
  light: { paper: '#f8f9f6', grid: '#cbd3ca', selection: '#4878c8', handle: '#387c59' },
  dark: { paper: '#161b17', grid: '#3a463d', selection: '#7ea6ea', handle: '#6fbf94' },
} as const

export const MIN_DARK_CONTRAST = 4.5

const channel = (value: number) => { const c = value / 255; return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4) }
const rgb = (hex: string) => [1, 3, 5].map(i => parseInt(hex.slice(i, i + 2), 16))
export const luminance = (hex: string) => { const [r, g, b] = rgb(hex).map(channel); return 0.2126 * r + 0.7152 * g + 0.0722 * b }
export function contrast(a: string, b: string) {
  const x = luminance(a), y = luminance(b)
  return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05)
}

function toHsl(hex: string): [number, number, number] {
  const [r, g, b] = rgb(hex).map(v => v / 255)
  const max = Math.max(r, g, b), min = Math.min(r, g, b), l = (max + min) / 2
  if (max === min) return [0, 0, l]
  const d = max - min, s = l > 0.5 ? d / (2 - max - min) : d / (max + min)
  const h = max === r ? (g - b) / d + (g < b ? 6 : 0) : max === g ? (b - r) / d + 2 : (r - g) / d + 4
  return [h / 6, s, l]
}
function hue(p: number, q: number, t: number) {
  if (t < 0) t += 1
  if (t > 1) t -= 1
  if (t < 1 / 6) return p + (q - p) * 6 * t
  if (t < 1 / 2) return q
  if (t < 2 / 3) return p + (q - p) * (2 / 3 - t) * 6
  return p
}
function fromHsl(h: number, s: number, l: number) {
  const q = l < 0.5 ? l * (1 + s) : l + s - l * s, p = 2 * l - q
  const values = s === 0 ? [l, l, l] : [hue(p, q, h + 1 / 3), hue(p, q, h), hue(p, q, h - 1 / 3)]
  return '#' + values.map(v => Math.round(v * 255).toString(16).padStart(2, '0')).join('')
}

/**
 * Deterministic dark-canvas rendering of a canonical color. Colors that are
 * already legible are kept; others keep hue/saturation and mirror lightness
 * (dark ink becomes light ink), raised in 1% steps until they reach 4.5:1.
 * Light mode always renders the canonical color.
 */
export function displayColor(color: string, dark: boolean): string {
  const hex = color.toLowerCase()
  if (!dark || !/^#[0-9a-f]{6}$/.test(hex) || contrast(hex, CANVAS.dark.paper) >= MIN_DARK_CONTRAST) return hex
  const [h, s, l] = toHsl(hex), start = Math.max(l, 1 - l)
  for (let step = 0; step <= 100; step++) {
    const candidate = fromHsl(h, s, Math.min(1, start + step * 0.01))
    if (contrast(candidate, CANVAS.dark.paper) >= MIN_DARK_CONTRAST) return candidate
  }
  return '#ffffff'
}
