import { describe, expect, it } from 'vitest'
import { StrokePressure } from './StrokePressure'

const at = (x: number) => ({ x, y: 0 })
describe('contact pressure', () => {
  it('keeps constant touch placeholders, mouse input and missing values at base width', () => {
    for (const type of ['mouse', 'touch', 'pen']) for (const p of [0, 0.5, 1, Number.NaN, Infinity]) {
      const input = new StrokePressure(type)
      input.add(at(0), p)
      expect(input.add(at(1), p).every(p => p.pressure === undefined)).toBe(true)
      expect(input.add(at(2), 0, false).every(p => p.pressure === undefined)).toBe(true)
    }
  })
  it('preserves early finger samples after variation and ignores lift-off force', () => {
    const input = new StrokePressure('touch')
    expect(input.add(at(0), 0.2)[0].pressure).toBeUndefined()
    const points = input.add(at(1), 0.8)
    expect(points.map(p => p.pressure)).toEqual([0.2, 0.8])
    expect(input.add(at(2), 0, false).map(p => p.pressure)).toEqual([0.2, 0.8, 0.8])
  })
  it('clamps finite pen pressure, retains missing samples and handles a pressure-changing dot', () => {
    const input = new StrokePressure('pen')
    expect(input.add(at(0), -1)[0].pressure).toBeUndefined()
    expect(input.add(at(0), 0.3).map(p => p.pressure)).toEqual([0, 0.3])
    expect(input.add(at(1), 2).at(-1)?.pressure).toBe(1)
    expect(input.add(at(2), Number.NaN).at(-1)?.pressure).toBeUndefined()
    expect(input.add(at(3), 0, false).at(-1)?.pressure).toBeUndefined()
  })
  it('carries the exact endpoint and capability across bounded segments', () => {
    const input = new StrokePressure('touch')
    input.add(at(0), 0.1)
    input.add(at(1), 0.9)
    input.retainLast()
    expect(input.add(at(2), 0.9)).toEqual([{ ...at(1), pressure: 0.9 }, { ...at(2), pressure: 0.9 }])
    expect(new StrokePressure('touch').add(at(0), 0.9)[0].pressure).toBeUndefined()
  })
})
