import { hitsElement, outline, type BoardElement, type Point } from './board'

export function selectionBounds(element: BoardElement) {
  const points = outline(element)
  const xs = points.map(p => p.x), ys = points.map(p => p.y)
  return { x: Math.min(...xs), y: Math.min(...ys), width: Math.max(...xs) - Math.min(...xs), height: Math.max(...ys) - Math.min(...ys) }
}
export function translated(elements: BoardElement[], ids: readonly string[], delta: Point) {
  if (!Number.isFinite(delta.x) || !Number.isFinite(delta.y)) throw new Error('Invalid move')
  const selected = new Set(ids)
  return elements.filter(e => selected.has(e.id) && !(e.startAttachment && e.endAttachment)).map(e => ({ ...e, points: e.points.map((p,index) => {
    if ((index===0 && e.startAttachment) || (index===1 && e.endAttachment)) return p
    const next = { ...p, x: p.x + delta.x, y: p.y + delta.y }
    if (Math.abs(next.x) > 1e7 || Math.abs(next.y) > 1e7) throw new Error('Move exceeds board bounds')
    return next
  }) }))
}
export type Selection = { ids: string[]; start?: Point; end?: Point; moving?: boolean }
export function beginSelection(selection: Selection, elements: BoardElement[], at: Point, radius: number, additive = false): Selection {
  const hit = [...elements].reverse().find(e => {
    const b = selectionBounds(e)
    return ['rectangle', 'ellipse', 'text', 'sticky'].includes(e.type)
      ? at.x >= b.x - radius && at.x <= b.x + b.width + radius && at.y >= b.y - radius && at.y <= b.y + b.height + radius
      : hitsElement(e, at, at, radius)
  })
  if (hit) {
    const ids = additive ? (selection.ids.includes(hit.id) ? selection.ids.filter(id => id !== hit.id) : [...selection.ids, hit.id])
      : selection.ids.includes(hit.id) ? selection.ids : [hit.id]
    return ids.includes(hit.id) ? { ids, start: at, end: at, moving: true } : { ids }
  }
  return { ids: additive ? selection.ids : [], start: at, end: at, moving: false }
}
export function marquee(selection: Selection) {
  if (!selection.start || !selection.end || selection.moving) return null
  const a = selection.start, b = selection.end
  return { x: Math.min(a.x,b.x), y: Math.min(a.y,b.y), width: Math.abs(b.x-a.x), height: Math.abs(b.y-a.y) }
}
export function finishSelection(selection: Selection, elements: BoardElement[]): Selection {
  const box = marquee(selection)
  const ids = new Set(selection.ids)
  if (box) for (const e of elements) {
    const b = selectionBounds(e)
    if (b.x >= box.x && b.y >= box.y && b.x+b.width <= box.x+box.width && b.y+b.height <= box.y+box.height) ids.add(e.id)
  }
  return { ids: [...ids].filter(id => elements.some(e => e.id === id)) }
}
export function selectionDelta(selection: Selection): Point {
  return selection.moving && selection.start && selection.end ? { x: selection.end.x-selection.start.x, y: selection.end.y-selection.start.y } : { x: 0, y: 0 }
}
