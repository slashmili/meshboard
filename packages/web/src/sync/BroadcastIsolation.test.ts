import { afterEach, expect, it, vi } from 'vitest'
import * as bc from 'lib0/broadcastchannel'
import * as encoding from 'lib0/encoding'
import * as Y from 'yjs'
import { WebrtcProvider } from 'y-webrtc'
import { CrdtDocument } from './CrdtDocument'
import { YWebrtcSession } from './YWebrtcSession'
import { newSessionKey } from './FrameCipher'

// Exercise the actual provider and session startup, without contacting signaling.
vi.mock('y-webrtc', async importOriginal => {
  const actual = await importOriginal<typeof import('y-webrtc')>()
  return { ...actual, WebrtcProvider: class extends actual.WebrtcProvider {
    constructor(room: string, doc: Y.Doc, options: ConstructorParameters<typeof actual.WebrtcProvider>[2]) {
      super(room, doc, { ...options, signaling: [] })
    }
  } }
})
afterEach(() => { vi.unstubAllGlobals() })
const element = (id: string) => ({ id, type: 'pen', color: '#123456', width: 3, points: [{ x: 1, y: 2 }] })
const settle = () => new Promise(resolve => setTimeout(resolve, 0))

it('keeps board and awareness off BroadcastChannel throughout the session lifecycle', async () => {
  const room = crypto.randomUUID(), topic = `meshboard-crdt:${room}`
  const document = new CrdtDocument()
  const captured: Uint8Array[] = []
  const listener = (bytes: ArrayBuffer) => { captured.push(new Uint8Array(bytes)) }
  const session = new YWebrtcSession(room, newSessionKey(), document, () => {}, () => {})
  vi.stubGlobal('location', { href: 'https://meshboard.invalid/' })
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => ({
    iceServers: [{ urls: ['stun:localhost:3478'] }], iceTransportPolicy: 'all', localDevelopment: true,
  }) }))
  document.ydoc.getMap('elements').set('fixture', element('fixture'))
  bc.subscribe(topic, listener)
  try {
    await session.start()
    const provider = (session as unknown as { provider: WebrtcProvider }).provider
    expect(provider.room).not.toBeNull()
    await settle()
    expect(captured).toEqual([])
    expect(provider.room!.bcconnected).toBe(false)

    // A room-name-only sender must not be able to inject plaintext updates either.
    const outsider = new Y.Doc()
    try {
      outsider.getMap('elements').set('injected', element('injected'))
      const message = encoding.createEncoder()
      encoding.writeVarUint(message, 0)
      encoding.writeVarUint(message, 2)
      encoding.writeVarUint8Array(message, Y.encodeStateAsUpdate(outsider))
      bc.publish(topic, encoding.toUint8Array(message))
      await settle()
      expect(document.ydoc.getMap('elements').has('injected')).toBe(false)
    } finally { outsider.destroy() }
    captured.length = 0

    document.ydoc.getMap('elements').set('later', element('later'))
    provider.awareness.setLocalState({ marker: 'private-presence' })
    provider.disconnect()
    provider.connect()
    await settle()
    expect(provider.room!.bcconnected).toBe(false)
    session.dispose()
    await settle()
    expect(captured).toEqual([])
  } finally {
    session.dispose()
    document.ydoc.destroy()
    bc.unsubscribe(topic, listener)
  }
})

it('detects the upstream plaintext startup publication when isolation is omitted', async () => {
  const name = `meshboard-review-control:${crypto.randomUUID()}`
  const doc = new Y.Doc(), captured: Uint8Array[] = []
  const listener = (bytes: ArrayBuffer) => { captured.push(new Uint8Array(bytes)) }
  doc.getMap('elements').set('fixture', 'control-marker')
  bc.subscribe(name, listener)
  const provider = new WebrtcProvider(name, doc, { filterBcConns: false })
  try {
    await provider.key
    bc.unsubscribe(name, provider.room!._bcSubscriber)
    provider.room!.bcconnected = false
    await settle()
    expect(captured.some(bytes => new TextDecoder().decode(bytes).includes('control-marker'))).toBe(true)
  } finally {
    provider.destroy(); provider.awareness.destroy(); doc.destroy(); bc.unsubscribe(name, listener)
  }
})
