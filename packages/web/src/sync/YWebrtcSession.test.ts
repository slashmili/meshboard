import { EventEmitter } from 'node:events'
import { afterEach, expect, it, vi } from 'vitest'
import { CrdtDocument } from './CrdtDocument'
import { MAX_Y_MESSAGE, YMessageReceiver } from './YTransport'
import { YWebrtcSession } from './YWebrtcSession'
import { SECURE_CHANNEL, SecureChannel } from './SecureChannel'
import { newSessionKey } from './FrameCipher'

const room = '12345678-1234-4234-8234-123456789abc', alice = '12345678-1234-4234-8234-123456789abd', bob = '12345678-1234-4234-8234-123456789abe'
const sdp = (byte: string) => `a=fingerprint:sha-256 ${Array(32).fill(byte).join(':')}\r\nm=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n`
class Peer extends EventEmitter {
  connected = false; destroyed = false
  _channel = { label: SECURE_CHANNEL, bufferedAmount: 0 }
  _pc = { localDescription: { sdp: sdp('11') }, remoteDescription: { sdp: sdp('22') } }
  send = vi.fn((_bytes: Uint8Array) => {})
  destroy = vi.fn(() => { this.destroyed = true; this.emit('close') })
}
const cleanups: (() => void)[] = []
afterEach(() => { for (const cleanup of cleanups.splice(0)) cleanup() })
function setup() {
  const document = new CrdtDocument(), key = newSessionKey(), peer = new Peer(), send = peer.send
  const remote = new SecureChannel(key, room, bob, alice, sdp('22'), sdp('11'))
  const conn = { peer, remotePeerId: bob, connected: false, closed: false }
  const session = new YWebrtcSession(room, key, document, () => {}, () => {})
  const internal = session as unknown as { provider: unknown; attachPeers(): void; flush(): void }
  internal.provider = { room: { peerId: alice, webrtcConns: new Map([[bob, conn]]) }, signalingConns: [], disconnect() {}, destroy() {}, awareness: { destroy() {} } }
  internal.attachPeers()
  cleanups.push(() => { session.dispose(); document.ydoc.destroy(); remote.close() })
  async function connect() {
    peer.connected = true; conn.connected = true; peer.emit('connect')
    const proof = await remote.receive(send.mock.calls[0][0])
    peer.emit('data', remote.hello)
    await vi.waitFor(() => expect(send).toHaveBeenCalledTimes(2))
    await remote.receive(send.mock.calls[1][0])
    peer.emit('data', proof.response!)
  }
  return { peer, send, remote, connect, internal }
}
it('queues drawing until authentication, then encrypts every fragment in order', async () => {
  const { peer, send, remote, connect } = setup()
  const large = new Uint8Array(50000).fill(42); large[0] = 0
  peer.send(large); peer.send(new Uint8Array([3]))
  expect(send).not.toHaveBeenCalled()
  await connect()
  await vi.waitFor(() => expect(send).toHaveBeenCalledTimes(7))
  const receiver = new YMessageReceiver(), messages: Uint8Array[] = []
  for (const [frame] of send.mock.calls.slice(2)) {
    const clear = (await remote.receive(frame)).data!
    const message = receiver.accept(clear); if (message) messages.push(message)
  }
  expect(messages).toEqual([large, new Uint8Array([3])])
})
it('bounds pending traffic before authentication', () => {
  const { peer, send } = setup()
  for (let i = 0; i < 5; i++) peer.send(new Uint8Array(MAX_Y_MESSAGE))
  expect(peer.destroy).toHaveBeenCalledTimes(1); expect(send).not.toHaveBeenCalled()
})
it('rejects plaintext without passing it to upstream Yjs listeners', async () => {
  const { peer, connect } = setup()
  await connect(); peer.emit('data', new Uint8Array([0, 0, 1, 0]))
  await vi.waitFor(() => expect(peer.destroy).toHaveBeenCalled())
})
it('bounds incoming records while connection readiness is pending', () => {
  const { peer } = setup()
  for (let i = 0; i < 513; i++) peer.emit('data', new Uint8Array(36))
  expect(peer.destroy).toHaveBeenCalledTimes(1)
})
