import { LocalHistory, type Direction } from './LocalHistory'
import { translated } from '../selection'
import * as Y from 'yjs'
import { elementSchema, MAX_ELEMENTS, type BoardElement } from '@meshboard/shared-protocol'
import type { SessionDocument } from './SessionDocument'

const LIMIT = 2 * 1024 * 1024
/** Stage mutations so invalid state cannot replace the board. */
export class CrdtDocument implements SessionDocument {
  readonly ydoc = new Y.Doc()
  private get doc() { return this.ydoc }
  private history = new LocalHistory()
  private localMutation = false
  get canUndo() { return this.history.undo.length > 0 }
  get canRedo() { return this.history.redo.length > 0 }
  private visible: BoardElement[] = []
  private listeners = new Set<() => void>()
  private errors = new Set<(message: string) => void>()
  constructor() {
    this.doc.getMap('elements').observe(event => {
      if (!this.localMutation) this.history.invalidate(event.keysChanged)
    })
    this.doc.on('update', () => this.notify())
  }
  private notify() { this.visible = this.readElements(this.doc); for (const fn of this.listeners) fn() }
  getElements = () => this.visible
  subscribe = (fn: () => void) => { this.listeners.add(fn); return () => { this.listeners.delete(fn) } }
  onError = (fn: (message: string) => void) => { this.errors.add(fn); return () => { this.errors.delete(fn) } }

  private readElements(candidate: Y.Doc) {
    if ([...candidate.share.keys()].some(key => key !== 'elements')) throw new Error('Unknown CRDT root.')
    const map = candidate.getMap('elements')
    if (map.size > MAX_ELEMENTS) throw new Error('Too many board elements.')
    const elements = [...map.entries()].map(([id, value]) => {
      if (value instanceof Y.AbstractType) throw new Error('Expected an atomic board element.')
      const element = elementSchema.parse(value)
      if (id !== element.id) throw new Error('CRDT element ID mismatch.')
      return element
    }).sort((a, b) => a.id < b.id ? -1 : a.id > b.id ? 1 : 0)
    if (elements.filter(e => e.type === 'image').length > 8) throw new Error('A board supports up to eight images.')
    if (Y.encodeStateAsUpdate(candidate).length > LIMIT || new TextEncoder().encode(JSON.stringify(elements)).length > LIMIT) throw new Error('Board exceeds CRDT session limits.')
    return elements
  }
  private validate(change: (candidate: Y.Doc) => void) {
    const candidate = new Y.Doc()
    // Preserve this replica's identity while validating a separate copy.
    candidate.getMap('elements')
    try {
      Y.applyUpdate(candidate, Y.encodeStateAsUpdate(this.doc))
      candidate.clientID = this.doc.clientID
      change(candidate)
      this.readElements(candidate)
    } finally { candidate.destroy() }
  }
  validateUpdate(bytes: Uint8Array) {
    if (bytes.length > LIMIT) throw new Error('CRDT update exceeds session limits.')
    this.validate(candidate => Y.applyUpdate(candidate, bytes))
  }
  private commit(change: (candidate: Y.Doc) => void, local: boolean) {
    this.validate(change)
    this.localMutation = local
    try { change(this.doc) } finally { this.localMutation = false }
  }
  applyUpdate(bytes: Uint8Array) {
    if (bytes.length > LIMIT) throw new Error('CRDT update exceeds session limits.')
    this.commit(candidate => Y.applyUpdate(candidate, bytes), false)
  }
  private local(change: (candidate: Y.Doc) => void, capture = true) {
    try {
      const before = this.readElements(this.doc)
      this.commit(change, true)
      if (capture) this.history.record(before, this.readElements(this.doc))
      this.notify()
      return true
    }
    catch { for (const fn of this.errors) fn('That change exceeds the CRDT session limits or is invalid.'); return false }
  }
  put(element: BoardElement) { return this.local(doc => doc.getMap('elements').set(element.id, elementSchema.parse(element))) }
  remove(ids: string[]) { this.local(doc => doc.transact(() => { for (const id of ids) doc.getMap('elements').delete(id) })) }
  move(ids: string[], delta: { x: number; y: number }) {
    if (delta.x === 0 && delta.y === 0) return
    this.local(doc => {
      const next = translated(this.readElements(doc), ids, delta)
      doc.transact(() => { for (const e of next) doc.getMap('elements').set(e.id, e) })
    })
  }
  undo() { this.applyHistory('undo') }
  redo() { this.applyHistory('redo') }
  private applyHistory(direction: Direction) {
    while (this.history[direction].length) {
      const action = this.history.applicable(direction, this.readElements(this.doc))
      if (action.length && !this.local(doc => doc.transact(() => {
        const map = doc.getMap('elements')
        for (const c of action) {
          const value = direction === 'undo' ? c.before : c.after
          if (value) map.set(c.id, value); else map.delete(c.id)
        }
      }), false)) return
      this.history[direction].pop()
      if (action.length) this.history[direction === 'undo' ? 'redo' : 'undo'].push(action)
      this.notify()
      if (action.length) return
    }
  }
  clear() { this.remove(this.visible.map(e => e.id)) }
}
