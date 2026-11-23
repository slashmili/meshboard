import type { Point } from './board'

/** One contact's samples. Capability evidence never survives into another stroke. */
export class StrokePressure {
  private samples: Point[] = []
  private first?: number
  private enabled = false

  constructor(private readonly pointerType: string) {}

  add(point: Point, pressure: number, contact = true): Point[] {
    const value = contact
      ? (Number.isFinite(pressure) ? Math.max(0, Math.min(1, pressure)) : undefined)
      : this.samples.at(-1)?.pressure
    if (contact && value !== undefined && this.pointerType !== 'mouse') {
      // A changing contact signal is evidence for touch pressure. A pen can
      // also establish support immediately with a non-placeholder reading.
      if (this.first !== undefined && Math.abs(value - this.first) > 0.001) this.enabled = true
      this.first ??= value
      if (this.pointerType === 'pen' && value > 0 && value !== 0.5 && value !== 1) this.enabled = true
    }
    const sample: Point = value === undefined ? { x: point.x, y: point.y } : { x: point.x, y: point.y, pressure: value }
    const last = this.samples.at(-1)
    if (!last || last.x !== sample.x || last.y !== sample.y || last.pressure !== sample.pressure) this.samples.push(sample)
    return this.points()
  }

  private points(): Point[] {
    return this.samples.map(({ x, y, pressure }) => this.enabled && this.pointerType !== 'mouse' && pressure !== undefined ? { x, y, pressure } : { x, y })
  }

  /** Bound storage while retaining the exact endpoint and capability evidence. */
  retainLast(count = 1) { this.samples = this.samples.slice(-count) }
}
