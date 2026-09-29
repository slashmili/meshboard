import { expect, it } from 'vitest'
import * as encoding from 'lib0/encoding'
import * as sync from 'y-protocols/sync'
import * as awareness from 'y-protocols/awareness'
import * as Y from 'yjs'
import { fragmentYMessage, MAX_Y_MESSAGE, YMessageReceiver } from './YTransport'

it('matches the native fixtures with upstream sync and awareness encoders', () => {
  const doc = new Y.Doc(); doc.clientID = 42
  const encoder = encoding.createEncoder(); encoding.writeVarUint(encoder, 0); sync.writeSyncStep1(encoder, doc)
  expect([...encoding.toUint8Array(encoder)]).toEqual([0, 0, 1, 0])
  const state = new awareness.Awareness(doc)
  state.setLocalState({}) // clock 1
  expect([...awareness.encodeAwarenessUpdate(state, [42])]).toEqual([1, 42, 1, 2, 123, 125])
  state.destroy(); doc.destroy()
})
it('passes standard messages unchanged and reassembles a bounded large update', () => {
  const small = new Uint8Array([0, 0, 1, 0])
  expect(fragmentYMessage(small)).toEqual([small])
  const bytes = new Uint8Array(500_000).fill(42); bytes[0] = 0
  const receiver = new YMessageReceiver()
  const results = fragmentYMessage(bytes).map(frame => receiver.accept(frame)).filter(Boolean)
  expect(results).toEqual([bytes])
  expect(() => fragmentYMessage(new Uint8Array(MAX_Y_MESSAGE + 1))).toThrow()
})
it('rejects truncated, interleaved, oversized, nested and expired fragments', () => {
  const bytes = new Uint8Array(30_000)
  const frames = fragmentYMessage(bytes)
  const receiver = new YMessageReceiver()
  expect(receiver.accept(frames[0])).toBeNull()
  expect(() => receiver.accept(new Uint8Array([3]))).toThrow()
  expect(() => new YMessageReceiver().accept(new Uint8Array([127]))).toThrow()
  expect(() => new YMessageReceiver().accept(new Uint8Array([127, 127, 255, 255, 255, 0]))).toThrow()
  let time = 0
  const expired = new YMessageReceiver(() => time)
  expired.accept(frames[0]); time = 30_001
  expect(() => expired.accept(frames[1])).toThrow()
  const nested = new YMessageReceiver(); bytes[0] = 127
  expect(() => fragmentYMessage(bytes).forEach(frame => nested.accept(frame))).toThrow()
})
