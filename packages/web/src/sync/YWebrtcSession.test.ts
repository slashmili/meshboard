import { EventEmitter } from 'node:events'
import { afterEach, expect, it, vi } from 'vitest'
import * as encoding from 'lib0/encoding'
import * as decoding from 'lib0/decoding'
import * as sync from 'y-protocols/sync'
import * as Y from 'yjs'
import { CrdtDocument } from './CrdtDocument'
import { FRAGMENT_CHANNEL, MAX_Y_MESSAGE, YMessageReceiver } from './YTransport'
import { YWebrtcSession } from './YWebrtcSession'

class Peer extends EventEmitter {
  connected = false
  destroyed = false
  _channel: { label: string; bufferedAmount: number } | null = null
  send = vi.fn((_bytes: Uint8Array) => {})
  destroy = vi.fn(() => { this.destroyed = true; this.emit('close') })
}
const cleanups: (() => void)[] = []
afterEach(() => { for (const cleanup of cleanups.splice(0)) cleanup() })

function setup() {
  const document = new CrdtDocument()
  const remote = new Y.Doc()
  const peer = new Peer(), send = peer.send
  // Model upstream's real sync handler: reply to the native state-vector request
  // immediately, even if simple-peer is still finishing connection readiness.
  peer.on('data', (bytes: Uint8Array) => {
    const decoder = decoding.createDecoder(bytes)
    expect(decoding.readVarUint(decoder)).toBe(0)
    const encoder = encoding.createEncoder(); encoding.writeVarUint(encoder, 0)
    sync.readSyncMessage(decoder, encoder, document.ydoc, null)
    if (encoding.length(encoder) > 1) peer.send(encoding.toUint8Array(encoder))
  })
  const conn = { peer, connected: false, closed: false }
  const session = new YWebrtcSession('room', document, () => {}, () => {})
  const internal = session as unknown as {
    provider: unknown; attachPeers(): void; flush(): void
    peers: Map<unknown, { fragments: boolean }>
  }
  internal.provider = {
    room: { webrtcConns: new Map([['peer', conn]]) }, signalingConns: [],
    disconnect() {}, destroy() {}, awareness: { destroy() {} },
  }
  internal.attachPeers()
  cleanups.push(() => { session.dispose(); document.ydoc.destroy(); remote.destroy() })
  function request() {
    // The native channel is usable while simple-peer still awaits ICE stats.
    peer._channel = { label: FRAGMENT_CHANNEL, bufferedAmount: 0 }
    const encoder = encoding.createEncoder(); encoding.writeVarUint(encoder, 0)
    sync.writeSyncStep1(encoder, remote)
    peer.emit('data', encoding.toUint8Array(encoder))
  }
  function connect(fragments = false) {
    peer._channel = { label: fragments ? FRAGMENT_CHANNEL : 'upstream', bufferedAmount: 0 }
    internal.peers.get(conn)!.fragments = fragments
    peer.connected = true; conn.connected = true; peer.emit('connect')
    internal.flush()
  }
  return { document, remote, peer, send, internal, request, connect }
}
const stroke = { id: 'stroke', type: 'pen' as const, color: '#123456', width: 3, points: [{ x: 1, y: 2 }] }

it.each([false, true])('retains an early sync reply until connected (fragmentation=%s)', fragments => {
  const { document, remote, peer, send, request, connect, internal } = setup()
  document.put(fragments ? { ...stroke, points: Array.from({ length: 12000 }, (_, x) => ({ x, y: -x })) } : stroke)
  request()
  expect(send).not.toHaveBeenCalled()
  expect(peer.destroy).not.toHaveBeenCalled()
  connect(fragments)
  expect(send.mock.calls.length).toBeGreaterThan(0)
  const assembler = new YMessageReceiver()
  const messages = send.mock.calls.map(([frame]) => fragments ? assembler.accept(frame) : frame).filter((bytes): bytes is Uint8Array => bytes !== null)
  expect(messages).toHaveLength(1)
  const decoder = decoding.createDecoder(messages[0])
  expect(decoding.readVarUint(decoder)).toBe(0)
  expect(decoding.readVarUint(decoder)).toBe(1) // sync step 2, the missing snapshot
  Y.applyUpdate(remote, decoding.readVarUint8Array(decoder))
  expect(remote.getMap('elements').toJSON()).toEqual(document.ydoc.getMap('elements').toJSON())
  const count = send.mock.calls.length
  internal.flush(); expect(send).toHaveBeenCalledTimes(count)
})

it('keeps queued messages ordered across backpressure without nesting fragments', () => {
  const { peer, send, connect, internal } = setup()
  const large = new Uint8Array(50000).fill(42); large[0] = 0
  peer.send(large); peer.send(new Uint8Array([3]))
  send.mockImplementation(() => { peer._channel!.bufferedAmount = 256 * 1024 })
  connect(true)
  expect(send).toHaveBeenCalledTimes(1)
  for (let i = 0; i < 8; i++) { peer._channel!.bufferedAmount = 0; internal.flush() }
  const receiver = new YMessageReceiver()
  expect(send.mock.calls.map(([frame]) => receiver.accept(frame)).filter(Boolean)).toEqual([large, new Uint8Array([3])])
})

it('bounds pending traffic even before a data channel exists', () => {
  const { peer, send } = setup()
  const bytes = new Uint8Array(MAX_Y_MESSAGE)
  for (let i = 0; i < 5; i++) peer.send(bytes)
  expect(peer.destroy).toHaveBeenCalledTimes(1)
  expect(send).not.toHaveBeenCalled()
})

it('discards pending traffic on close instead of sending to a destroyed peer', () => {
  const { peer, send, connect, internal } = setup()
  peer.send(new Uint8Array([3])); peer.destroy()
  connect(); internal.flush()
  expect(send).not.toHaveBeenCalled()
})
