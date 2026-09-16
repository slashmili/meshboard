import * as Y from 'yjs'
import { WebrtcProvider } from 'y-webrtc'
import type { BoardElement } from '@meshboard/shared-protocol'

// Vite dev fixture only: not imported by the app or a production build entry.
if (!import.meta.env.DEV || !['127.0.0.1', 'localhost'].includes(location.hostname)) throw new Error('Local test fixture only')
const room = new URL(location.href).searchParams.get('room')!
const config = await (await fetch('/api/rtc-config')).json()
const doc = new Y.Doc(), elements = doc.getMap('elements')
const provider = new WebrtcProvider(`meshboard-crdt:${room}`, doc, {
  signaling: [`ws://${location.host}/signal-y-webrtc`], maxConns: 7,
  peerOpts: { config: { iceServers: config.iceServers, iceTransportPolicy: config.iceTransportPolicy } },
})
const fixture = {
  elements: () => [...elements.values()],
  peers: () => [...provider.room?.webrtcConns.values() ?? []].filter(peer => peer.connected).length,
  put: (element: BoardElement) => elements.set(element.id, element),
  remove: (id: string) => elements.delete(id),
  preview: (element: BoardElement | null) => provider.awareness.setLocalStateField('meshboard', { preview: element }),
  drafts: () => [...provider.awareness.getStates()].filter(([id]) => id !== doc.clientID).map(([, state]) => state.meshboard?.preview).filter(Boolean),
  // Used to prove application validation happens before the upstream provider applies.
  invalid: () => elements.set('invalid', { id: 'invalid', points: [] }),
}
Object.assign(window, { upstream: fixture })
export type UpstreamFixture = typeof fixture
