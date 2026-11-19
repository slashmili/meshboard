export type Point = { x: number; y: number; pressure?: number }
export type View = Point & { zoom: number }
export type DrawingTool = 'pen' | 'rectangle' | 'ellipse' | 'line' | 'arrow' | 'text' | 'sticky'
export type Tool = DrawingTool | 'eraser' | 'hand' | 'select'

import type { BoardElement } from '@meshboard/shared-protocol'
export type { BoardElement } from '@meshboard/shared-protocol'

export const MIN_ZOOM = 0.25
export const MAX_ZOOM = 4
export const INITIAL_VIEW: View = { x: 0, y: 0, zoom: 1 }

export function toWorld(point: Point, view: View): Point {
  return { x: (point.x - view.x) / view.zoom, y: (point.y - view.y) / view.zoom }
}

export function zoomAt(view: View, anchor: Point, zoom: number): View {
  const nextZoom = Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, zoom))
  const world = toWorld(anchor, view)
  return { x: anchor.x - world.x * nextZoom, y: anchor.y - world.y * nextZoom, zoom: nextZoom }
}

export function constrainEnd(start: Point, end: Point, type: BoardElement['type']): Point {
  const dx = end.x - start.x
  const dy = end.y - start.y
  if (type === 'line' || type === 'arrow') {
    const angle = Math.round(Math.atan2(dy, dx) / (Math.PI / 4)) * (Math.PI / 4)
    const length = Math.hypot(dx, dy)
    return { x: start.x + Math.cos(angle) * length, y: start.y + Math.sin(angle) * length }
  }
  const side = Math.max(Math.abs(dx), Math.abs(dy))
  return { x: start.x + (dx < 0 ? -side : side), y: start.y + (dy < 0 ? -side : side) }
}

export function bounds(element: BoardElement) {
  const [start, end = start] = element.points
  return { x: Math.min(start.x, end.x), y: Math.min(start.y, end.y), width: Math.abs(end.x - start.x), height: Math.abs(end.y - start.y) }
}

export function outline(element: BoardElement): Point[] {
  if (element.type === 'pen' || element.type === 'line') return element.points
  if (element.type === 'arrow') {
    const [start, end] = element.points
    const dx = end.x - start.x, dy = end.y - start.y
    const distance = Math.hypot(dx, dy)
    if (distance === 0) return [start, end]
    const head = Math.min(distance / 2, Math.max(12, element.width * 3))
    const ux = dx / distance, uy = dy / distance
    return [start, end,
      { x: end.x - ux * head - uy * head / 2, y: end.y - uy * head + ux * head / 2 }, end,
      { x: end.x - ux * head + uy * head / 2, y: end.y - uy * head - ux * head / 2 }]
  }
  const { x, y, width, height } = bounds(element)
  if (['rectangle', 'text', 'sticky', 'image'].includes(element.type)) {
    return [{ x, y }, { x: x + width, y }, { x: x + width, y: y + height }, { x, y: y + height }, { x, y }]
  }
  return Array.from({ length: 97 }, (_, i) => {
    const angle = (i / 96) * Math.PI * 2
    return { x: x + width / 2 + Math.cos(angle) * width / 2, y: y + height / 2 + Math.sin(angle) * height / 2 }
  })
}

function pointSegmentDistance(p: Point, a: Point, b: Point) {
  const dx = b.x - a.x
  const dy = b.y - a.y
  const lengthSquared = dx * dx + dy * dy
  const t = lengthSquared === 0 ? 0 : Math.max(0, Math.min(1, ((p.x - a.x) * dx + (p.y - a.y) * dy) / lengthSquared))
  return Math.hypot(p.x - a.x - t * dx, p.y - a.y - t * dy)
}

function cross(a: Point, b: Point, c: Point) {
  return (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)
}

function segmentDistance(a: Point, b: Point, c: Point, d: Point) {
  // Strict crossing; collinear and endpoint cases are covered by distances.
  if (cross(a, b, c) * cross(a, b, d) < 0 && cross(c, d, a) * cross(c, d, b) < 0) return 0
  return Math.min(pointSegmentDistance(a, c, d), pointSegmentDistance(b, c, d), pointSegmentDistance(c, a, b), pointSegmentDistance(d, a, b))
}

export function hitsElement(element: BoardElement, from: Point, to: Point, radius: number) {
  if (element.text !== undefined || element.image !== undefined) {
    const box = bounds(element)
    const inside = (p: Point) => p.x >= box.x && p.x <= box.x + box.width && p.y >= box.y && p.y <= box.y + box.height
    if (inside(from) || inside(to)) return true
  }
  const points = outline(element)
  if (points.length === 1) return pointSegmentDistance(points[0], from, to) <= radius + penWidth(element.width, points[0].pressure) / 2
  return points.some((point, i) => {
    if (i === 0) return false
    const width = element.type === 'pen' ? (penWidth(element.width, points[i - 1].pressure) + penWidth(element.width, point.pressure)) / 2 : element.width
    return segmentDistance(from, to, points[i - 1], point) <= radius + width / 2
  })
}

/** Missing pressure preserves mouse/touch width; contact samples use 25–100%. */
export function penWidth(width: number, pressure?: number) {
  return pressure === undefined ? width : width * (0.25 + 0.75 * pressure)
}
export function penSample(point: Point, pointerType: string, pressure: number): Point {
  return pointerType === 'pen' && Number.isFinite(pressure)
    ? { ...point, pressure: Math.min(1, Math.max(0, pressure)) } : point
}
