import type { BoardElement, SessionMessage } from '@meshboard/shared-protocol'

export interface SessionDocument {
  readonly crdt?: boolean
  getElements(): BoardElement[]
  subscribe(listener: () => void): () => void
  onError(listener: (error: string) => void): () => void
  onLocalMessage(listener: (message: SessionMessage) => void): () => void
  snapshot(): SessionMessage
  receive(message: SessionMessage): SessionMessage | void
  put(element: BoardElement): boolean
  remove(ids: string[]): void
  move(ids: string[], delta: { x: number; y: number }): void
  clear(): void
}
