import { boardMessageSchema, previewSessionSchema, frameSchema, MAX_FRAME_BYTES, MAX_MESSAGE_BYTES, type SessionMessage } from '@meshboard/shared-protocol'

export function encodeFrames(message: SessionMessage): string[] {
  const text = JSON.stringify(message)
  if (new TextEncoder().encode(text).length > MAX_MESSAGE_BYTES) throw new Error('Board message is too large.')
  const id = crypto.randomUUID()
  // Leave room for the JSON envelope and worst-case quote escaping.
  const total = Math.ceil(text.length / 8_000)
  return Array.from({ length: total }, (_, index) => JSON.stringify({ v: 1, id, total, index, data: text.slice(index * 8_000, (index + 1) * 8_000) }))
}

// One ordered, reliable channel per peer. Frames of a message stay contiguous.
export class FrameReceiver {
  constructor(private crdt = false) {}
  private pending: { id: string; total: number; next: number; text: string; bytes: number; started: number } | null = null
  accept(raw: unknown): SessionMessage | null {
    if (typeof raw !== 'string' || new TextEncoder().encode(raw).length > MAX_FRAME_BYTES) throw new Error('Invalid board frame.')
    const frame = frameSchema.parse(JSON.parse(raw))
    if (frame.index === 0) {
      if (this.pending) throw new Error('Interrupted board message.')
      this.pending = { id: frame.id, total: frame.total, next: 0, text: '', bytes: 0, started: Date.now() }
    }
    const pending = this.pending
    if (!pending || frame.id !== pending.id || frame.total !== pending.total || frame.index !== pending.next || Date.now() - pending.started > 30_000) {
      throw new Error('Invalid board frame sequence.')
    }
    pending.text += frame.data
    pending.bytes += new TextEncoder().encode(frame.data).length
    pending.next++
    if (pending.bytes > MAX_MESSAGE_BYTES) throw new Error('Board message is too large.')
    if (pending.next !== pending.total) return null
    this.pending = null
    return (this.crdt ? previewSessionSchema : boardMessageSchema).parse(JSON.parse(pending.text))
  }
}
