import { describe, expect, it } from 'vitest'
import { constrainEnd, hitsElement, toWorld, zoomAt, type BoardElement } from './board'

describe('viewport', () => {
  it('keeps the world point under the pointer fixed while zooming', () => {
    const view = { x: -340, y: 190, zoom: 0.8 }
    const anchor = { x: 720, y: 460 }
    expect(toWorld(anchor, zoomAt(view, anchor, 2.5))).toEqual(toWorld(anchor, view))
  })

  it('clamps zoom and still preserves the anchor', () => {
    const view = { x: 10, y: 20, zoom: 1 }
    const anchor = { x: 100, y: 100 }
    for (const [requested, expected] of [[0.01, 0.25], [99, 4]]) {
      const next = zoomAt(view, anchor, requested)
      expect(next.zoom).toBe(expected)
      expect(toWorld(anchor, next)).toEqual(toWorld(anchor, view))
    }
  })
})

describe('whole-object eraser', () => {
  const line: BoardElement = { id: 'line', type: 'line', color: '#000', width: 2, points: [{ x: 0, y: 0 }, { x: 100, y: 0 }] }

  it('erases a crossed line even when neither pointer sample touches it', () => {
    expect(hitsElement(line, { x: 50, y: -100 }, { x: 50, y: 100 }, 3)).toBe(true)
  })

  it('does not erase an object beyond the eraser radius', () => {
    expect(hitsElement(line, { x: 0, y: 5 }, { x: 100, y: 5 }, 3)).toBe(false)
    expect(hitsElement(line, { x: 0, y: 4 }, { x: 100, y: 4 }, 3)).toBe(true)
  })

  it('does not erase an unfilled shape when its empty interior is touched', () => {
    for (const type of ['rectangle', 'ellipse'] as const) {
      const shape = { ...line, type, points: [{ x: 0, y: 0 }, { x: 100, y: 100 }] }
      expect(hitsElement(shape, { x: 50, y: 50 }, { x: 55, y: 50 }, 3)).toBe(false)
      expect(hitsElement(shape, { x: 100, y: 50 }, { x: 100, y: 50 }, 3)).toBe(true)
    }
  })

  it('can erase a single pen dot', () => {
    const dot = { ...line, type: 'pen' as const, points: [{ x: 50, y: 50 }] }
    expect(hitsElement(dot, { x: 50, y: 20 }, { x: 50, y: 80 }, 3)).toBe(true)
  })
})

describe('constrained shapes', () => {
  it('draws squares and circles in all directions', () => {
    for (const type of ['rectangle', 'ellipse'] as const) {
      expect(constrainEnd({ x: 50, y: 50 }, { x: 10, y: 70 }, type)).toEqual({ x: 10, y: 90 })
      expect(constrainEnd({ x: 50, y: 50 }, { x: 90, y: 10 }, type)).toEqual({ x: 90, y: 10 })
    }
  })

  it('snaps line angles to 45-degree increments', () => {
    const end = constrainEnd({ x: 0, y: 0 }, { x: 100, y: 90 }, 'line')
    expect(end.x).toBeCloseTo(end.y)
    expect(Math.hypot(end.x, end.y)).toBeCloseTo(Math.hypot(100, 90))
  })
})
