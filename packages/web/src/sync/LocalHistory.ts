import type { BoardElement } from '@meshboard/shared-protocol'
export type Change = { id: string; before?: BoardElement; after?: BoardElement }
export type Direction = 'undo' | 'redo'
const equal = (a?: BoardElement, b?: BoardElement) => JSON.stringify(a) === JSON.stringify(b)

/** Local inverse changes, not board snapshots. Remote writes remove affected IDs. */
export class LocalHistory {
  undo: Change[][] = []
  redo: Change[][] = []
  record(before: BoardElement[], after: BoardElement[]) {
    const a = new Map(before.map(e => [e.id,e])), b = new Map(after.map(e => [e.id,e]))
    const action = [...new Set([...a.keys(),...b.keys()])].filter(id => !equal(a.get(id),b.get(id))).map(id => ({ id, before:a.get(id), after:b.get(id) }))
    if (!action.length) return
    this.redo = []; this.undo.push(action)
    while (this.undo.length > 100 || new TextEncoder().encode(JSON.stringify(this.undo)).length > 8 * 1024 * 1024) this.undo.shift()
  }
  invalidate(ids: Set<string>) {
    for (const direction of ['undo','redo'] as const) this[direction] = this[direction].map(action => action.filter(c => !ids.has(c.id))).filter(action => action.length)
  }
  applicable(direction: Direction, elements: BoardElement[]) {
    const current = new Map(elements.map(e => [e.id,e]))
    return (this[direction].at(-1) ?? []).filter(c => equal(current.get(c.id), direction === 'undo' ? c.after : c.before))
  }
}
