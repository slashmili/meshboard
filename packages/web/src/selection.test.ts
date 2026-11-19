import { it, expect } from 'vitest'
import { beginSelection, finishSelection, translated, selectionBounds } from './selection'
import type { BoardElement } from './board'
const pen: BoardElement = { id: 'pen', type: 'pen', color: '#293b36', width: 3, points: [{ x: 0, y: 0 }, { x: 100, y: 100 }, { x: 20, y: 20 }] }
it('marquee uses every stroke point and selects only fully enclosed objects', () => {
  expect(selectionBounds(pen)).toEqual({ x: 0, y: 0, width: 100, height: 100 })
  expect(finishSelection({ ids: [], start: { x: -1, y: -1 }, end: { x: 30, y: 30 } }, [pen]).ids).toEqual([])
  expect(finishSelection({ ids: [], start: { x: 101, y: 101 }, end: { x: -1, y: -1 } }, [pen]).ids).toEqual(['pen'])
})
it('shift toggles a point out without the zero-size marquee selecting it again', () => {
  const dot = { ...pen, points: [{ x: 0, y: 0 }] }
  const selection = beginSelection({ ids: ['pen'] }, [dot], { x: 0, y: 0 }, 2, true)
  expect(finishSelection(selection, [dot]).ids).toEqual([])
})
it('moving preserves pressure and text, omits missing IDs and rejects invalid groups', () => {
  const label: BoardElement = { ...pen, id: 'label', type: 'text', text: 'hello', points: [{ x: 0, y: 0 }, { x: 200, y: 70 }] }
  const pressure = { ...pen, points: [{ x: 0, y: 0, pressure: 0.5 }] }
  const moved = translated([pressure, label], ['pen', 'label', 'deleted'], { x: 5, y: -2 })
  expect(moved[0].points[0]).toEqual({ x: 5, y: -2, pressure: 0.5 })
  expect(moved[1].text).toBe('hello')
  expect(() => translated([pressure, label], ['pen', 'label'], { x: 1e8, y: 0 })).toThrow()
  expect(pressure.points[0].x).toBe(0)
})
