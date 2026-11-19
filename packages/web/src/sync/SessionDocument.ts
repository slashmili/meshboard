import type { BoardElement } from '@meshboard/shared-protocol'

export interface SessionDocument {
  getElements(): BoardElement[]
  subscribe(listener: () => void): () => void
  onError(listener: (error: string) => void): () => void
  put(element: BoardElement): boolean
  remove(ids: string[]): void
  move(ids: string[], delta: { x: number; y: number }): void
  readonly canUndo: boolean
  readonly canRedo: boolean
  undo(): void
  redo(): void
  clear(): void
}
