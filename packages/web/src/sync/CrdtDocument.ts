import * as Y from 'yjs'
import { elementSchema, MAX_ELEMENTS, type BoardElement, type CrdtMessage, type SessionMessage } from '@meshboard/shared-protocol'
import type { SessionDocument } from './SessionDocument'

const LIMIT = 2 * 1024 * 1024
const EMPTY = new Uint8Array([0])
function decode(data: string) {
  const raw = atob(data)
  if (raw.length > LIMIT) throw new Error('CRDT update exceeds preview limits.')
  return Uint8Array.from(raw, char => char.charCodeAt(0))
}
function message(step: CrdtMessage['step'], bytes: Uint8Array): CrdtMessage {
  if (bytes.length > LIMIT) throw new Error('CRDT state exceeds preview limits.')
  let raw = ''
  for (let i = 0; i < bytes.length; i += 8192) raw += String.fromCharCode(...bytes.subarray(i, i + 8192))
  return { v: 2, type: 'crdt', step, data: btoa(raw) }
}

/** Isolated, local-only preview. Stage mutations so invalid state cannot replace the board. */
export class CrdtDocument implements SessionDocument {
  readonly crdt = true
  readonly ydoc = new Y.Doc()
  private get doc() { return this.ydoc }
  private visible: BoardElement[] = []
  private listeners = new Set<() => void>()
  private outgoing = new Set<(message: SessionMessage) => void>()
  private errors = new Set<(message: string) => void>()
  constructor() {
    this.doc.getMap('elements')
    this.doc.on('update', () => {
      this.visible = this.readElements(this.doc)
      for (const fn of this.listeners) fn()
    })
  }
  getElements = () => this.visible
  subscribe = (fn: () => void) => { this.listeners.add(fn); return () => { this.listeners.delete(fn) } }
  onLocalMessage = (fn: (message: SessionMessage) => void) => { this.outgoing.add(fn); return () => { this.outgoing.delete(fn) } }
  onError = (fn: (message: string) => void) => { this.errors.add(fn); return () => { this.errors.delete(fn) } }
  snapshot() { return message('vector', Y.encodeStateVector(this.doc)) }

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
    if (Y.encodeStateAsUpdate(candidate).length > LIMIT || new TextEncoder().encode(JSON.stringify(elements)).length > LIMIT) throw new Error('Board exceeds CRDT preview limits.')
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
    if (bytes.length > LIMIT) throw new Error('CRDT update exceeds preview limits.')
    this.validate(candidate => Y.applyUpdate(candidate, bytes))
  }
  private commit(change: (candidate: Y.Doc) => void, local: boolean) {
    this.validate(change)
    const before = Y.encodeStateVector(this.doc)
    change(this.doc)
    if (local) for (const fn of this.outgoing) fn(message('update', Y.encodeStateAsUpdate(this.doc, before)))
  }
  receive(value: SessionMessage) {
    if (value.type !== 'crdt') throw new Error('Expected CRDT synchronization.')
    const bytes = decode(value.data)
    if (value.step === 'vector') return message('update', Y.encodeStateAsUpdate(this.doc, bytes))
    this.commit(candidate => Y.applyUpdate(candidate, bytes), false)
  }
  private local(change: (candidate: Y.Doc) => void) {
    try { this.commit(change, true); return true }
    catch { for (const fn of this.errors) fn('That change exceeds the CRDT preview limits or is invalid.'); return false }
  }
  put(element: BoardElement) { return this.local(doc => doc.getMap('elements').set(element.id, elementSchema.parse(element))) }
  remove(ids: string[]) { this.local(doc => doc.transact(() => { for (const id of ids) doc.getMap('elements').delete(id) })) }
  clear() { this.remove(this.visible.map(e => e.id)) }
  // Used by validation tests; joining uses state vectors, including delete-only changes.
  fullState() { return message('update', Y.encodeStateAsUpdate(this.doc, EMPTY)) }
}
