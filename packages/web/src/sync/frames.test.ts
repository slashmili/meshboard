import { describe, expect, it } from 'vitest'
import type { BoardMessage } from '@meshboard/shared-protocol'
import { encodeFrames, FrameReceiver } from './frames'

describe('data channel framing', () => {
  const large: BoardMessage = { v: 1, type: 'put', element: { id: 'stroke', type: 'pen', width: 3, color: '#112233', points: Array.from({ length: 10_000 }, (_, i) => ({ x: i, y: i / 2 })) } }
  it('reassembles a stroke larger than one WebRTC frame', () => {
    const frames = encodeFrames(large)
    expect(frames.length).toBeGreaterThan(1)
    expect(frames.every(frame => new TextEncoder().encode(frame).length < 16_384)).toBe(true)
    const receiver = new FrameReceiver()
    const results = frames.map(frame => receiver.accept(frame)).filter(Boolean)
    expect(results).toEqual([large])
  })
  it('rejects out-of-order and interrupted messages', () => {
    const frames = encodeFrames(large)
    expect(() => new FrameReceiver().accept(frames[1])).toThrow()
    const receiver = new FrameReceiver()
    receiver.accept(frames[0])
    expect(() => receiver.accept(frames[0])).toThrow()
  })
  it('keeps quote-heavy messages below the frame limit after JSON escaping', () => {
    const frames = encodeFrames({ v: 1, type: 'remove', ids: Array.from({ length: 10_000 }, () => 'a') })
    expect(frames.every(frame => new TextEncoder().encode(frame).length < 16_384)).toBe(true)
  })
})
