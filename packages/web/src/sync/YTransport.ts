// Negotiated only by this channel label. Other y-webrtc peers use raw messages.
export const FRAGMENT_CHANNEL = 'meshboard.y-webrtc.fragments-v1'
export const FRAGMENT_SDP = 'a=meshboard-fragments:1'
export const MAX_Y_MESSAGE = 2 * 1024 * 1024 + 1024
const CHUNK = 16 * 1024 - 5
export function fragmentYMessage(bytes: Uint8Array): Uint8Array[] {
  if (!bytes.length || bytes.length > MAX_Y_MESSAGE) throw new Error('Invalid message size')
  if (bytes.length <= CHUNK) return [bytes]
  const result: Uint8Array[] = []
  for (let offset = 0; offset < bytes.length; offset += CHUNK) {
    const chunk = bytes.subarray(offset, offset + CHUNK)
    const frame = new Uint8Array(chunk.length + 5)
    frame[0] = 127
    new DataView(frame.buffer).setUint32(1, bytes.length)
    frame.set(chunk, 5); result.push(frame)
  }
  return result
}
export class YMessageReceiver {
  private pending?: Uint8Array
  private offset = 0
  private started = 0
  constructor(private now = Date.now) {}
  accept(bytes: Uint8Array): Uint8Array | null {
    if (!bytes.length || bytes.length > MAX_Y_MESSAGE) throw new Error('Invalid message size')
    if (bytes[0] !== 127) {
      if (this.pending) throw new Error('Interleaved fragments')
      return bytes
    }
    if (bytes.length <= 5 || bytes.length > CHUNK + 5) throw new Error('Invalid fragment')
    const total = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength).getUint32(1)
    if (total <= CHUNK || total > MAX_Y_MESSAGE) throw new Error('Invalid fragment size')
    if (!this.pending) { this.pending = new Uint8Array(total); this.offset = 0; this.started = this.now() }
    if (total !== this.pending.length || this.now() - this.started > 30_000 || this.offset + bytes.length - 5 > total) throw new Error('Invalid fragment sequence')
    this.pending.set(bytes.subarray(5), this.offset); this.offset += bytes.length - 5
    if (this.offset < total) return null
    const value = this.pending; this.pending = undefined
    if (value[0] === 127) throw new Error('Nested fragments')
    return value
  }
}
