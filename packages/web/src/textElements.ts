import type { BoardElement, Point } from './board'

/** Keep line breaks and box dimensions identical across native and SVG rendering. */
export function textLines(text: string): string[] {
  const lines: string[] = []
  for (const paragraph of text.replace(/\r\n?/g, '\n').split('\n')) {
    let line = '', units = 0
    for (const char of paragraph) {
      const size = char.codePointAt(0)! <= 255 ? 1 : 2
      if (units + size > 24) { lines.push(line); line = ''; units = 0 }
      line += char; units += size
    }
    lines.push(line)
  }
  return lines
}
export function textElement(id: string, type: 'text' | 'sticky', at: Point, color: string, text: string): BoardElement {
  const height = Math.max(72, textLines(text).length * 24 + 24)
  return { id, type, color, width: 1, points: [at, { x: at.x + 288, y: at.y + height }], text }
}
