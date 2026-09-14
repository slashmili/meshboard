import { afterEach, expect, it, vi } from 'vitest'
import { BoardDocument } from './BoardDocument'
import { MeshSession } from './MeshSession'

afterEach(() => vi.useRealTimers())

it.each(['open', 'connecting'])('sends exactly one snapshot when attaching a %s channel', async initialState => {
  vi.useFakeTimers()
  const document = new BoardDocument()
  document.put({ id: 'existing', type: 'pen', color: '#293b36', width: 3, points: [{ x: 12, y: 34 }] })
  const session = new MeshSession('room', document, () => {}, () => {})
  const send = vi.fn()
  const channel = {
    label: 'meshboard.v1', ordered: true, maxRetransmits: null, maxPacketLifeTime: null,
    readyState: initialState, bufferedAmount: 0, send, close: vi.fn(), onopen: undefined as (() => void) | undefined,
  }
  const peer = { pc: { getStats: async () => new Map(), close: vi.fn() }, queue: [], queuedBytes: 0, route: 'direct' }
  // Exercise the channel lifecycle without signaling or network timing.
  const internal = session as unknown as {
    peers: Map<string, unknown>; attachChannel(id: string, peer: unknown, channel: unknown): void
  }
  internal.peers.set('peer', peer)
  internal.attachChannel('peer', peer, channel)
  // Never send inside the channel-opening event itself.
  expect(send).not.toHaveBeenCalled()
  channel.readyState = 'open'
  channel.onopen?.()
  await vi.runAllTimersAsync()
  channel.onopen?.()
  expect(send).toHaveBeenCalledTimes(1)
  const frame = JSON.parse(send.mock.calls[0][0])
  expect(JSON.parse(frame.data)).toEqual(document.snapshot())
  await Promise.resolve()
  session.dispose()
})
