import { MAX_ELEMENTS, MAX_MESSAGE_BYTES, MAX_REMOVED, elementSchema, type BoardElement, type BoardMessage, type SessionMessage, type Snapshot } from '@meshboard/shared-protocol'

// Phase 1: immutable object inserts and explicit deletes, held only in memory.
// Yjs replaces this small store in Phase 2; it is not a Yjs-compatible document.
export class BoardDocument {
  private objects = new Map<string, BoardElement>()
  private removed = new Set<string>()
  private visible: BoardElement[] = []
  private listeners = new Set<() => void>()
  private outgoing = new Set<(message: BoardMessage) => void>()
  private errorListeners = new Set<(message: string) => void>()

  getElements = () => this.visible
  subscribe = (listener: () => void) => { this.listeners.add(listener); return () => { this.listeners.delete(listener) } }
  onLocalMessage = (listener: (message: BoardMessage) => void) => { this.outgoing.add(listener); return () => { this.outgoing.delete(listener) } }
  onError = (listener: (message: string) => void) => { this.errorListeners.add(listener); return () => { this.errorListeners.delete(listener) } }

  snapshot(): Snapshot { return { v: 1, type: 'snapshot', elements: this.visible, removed: [...this.removed] } }

  receive(message: SessionMessage) {
    if (message.type === 'crdt') throw new Error('CRDT preview requires a separate session.')
    if (message.type === 'preview') return
    const objects = new Map(this.objects)
    const removed = new Set(this.removed)
    const deletes = message.type === 'remove' ? message.ids : message.type === 'snapshot' ? message.removed : []
    for (const id of deletes) { removed.add(id); objects.delete(id) }
    const additions = message.type === 'put' ? [message.element] : message.type === 'snapshot' ? message.elements : []
    for (const element of additions) if (!removed.has(element.id) && !objects.has(element.id)) objects.set(element.id, element)
    const elements = [...objects.values()].sort((a, b) => a.id < b.id ? -1 : a.id > b.id ? 1 : 0)
    // All document strings are restricted to ASCII by the wire schema.
    if (objects.size > MAX_ELEMENTS || removed.size > MAX_REMOVED || JSON.stringify({ v: 1, type: 'snapshot', elements, removed: [...removed] }).length > MAX_MESSAGE_BYTES) {
      throw new Error('This prototype board is full. Start a new board to keep drawing.')
    }
    this.objects = objects
    this.removed = removed
    this.visible = elements
    for (const listener of this.listeners) listener()
  }

  private local(message: BoardMessage) {
    try { this.receive(message) }
    catch (error) { for (const listener of this.errorListeners) listener((error as Error).message); return false }
    for (const listener of this.outgoing) listener(message)
    return true
  }

  put(element: BoardElement) {
    const parsed = elementSchema.safeParse(element)
    if (!parsed.success) {
      for (const listener of this.errorListeners) listener('That stroke exceeded the prototype limits. Try a shorter stroke.')
      return false
    }
    return this.local({ v: 1, type: 'put', element: parsed.data })
  }
  remove(ids: string[]) { if (ids.length) this.local({ v: 1, type: 'remove', ids }) }
  clear() { this.remove(this.visible.map(element => element.id)) }
}
