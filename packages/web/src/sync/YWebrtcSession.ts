import { WebrtcProvider, type WebrtcConn } from 'y-webrtc'
import * as bc from 'lib0/broadcastchannel'
import * as decoding from 'lib0/decoding'
import * as Y from 'yjs'
import { elementSchema, rtcConfigSchema, type BoardElement } from '@meshboard/shared-protocol'
import { CrdtDocument } from './CrdtDocument'
import { INITIAL_STATUS, type SessionStatus } from './MeshSession'
import { FRAGMENT_CHANNEL, FRAGMENT_SDP, fragmentYMessage, MAX_Y_MESSAGE, YMessageReceiver } from './YTransport'

type SimplePeer = {
  connected: boolean; destroyed: boolean; _pc: RTCPeerConnection; _channel: RTCDataChannel | null
  send(bytes: Uint8Array): void; destroy(error?: Error): void
  listeners(event: 'data'): ((bytes: Uint8Array) => void)[]
  removeAllListeners(event: 'data'): void
  on(event: 'data', fn: (bytes: Uint8Array) => void): void
  on(event: 'connect' | 'close', fn: () => void): void
  prependListener(event: 'signal', fn: (signal: { sdp?: string }) => void): void
}
type OutgoingMessage = { message?: Uint8Array; frames?: Uint8Array[]; next: number }
type PeerState = { queue: OutgoingMessage[]; bytes: number; relay: boolean; fragments: boolean }

/** Actual upstream provider; hooks are limited to input validation, bounded I/O,
 * negotiated large-message fragmentation and disabling same-browser shortcuts. */
export class YWebrtcSession {
  private provider?: WebrtcProvider
  private stopped = false
  private controller = new AbortController()
  private timer?: ReturnType<typeof setInterval>
  private previewTimer?: ReturnType<typeof setTimeout>
  private pendingPreview: BoardElement | null = null
  private peers = new Map<WebrtcConn, PeerState>()
  private shown = new Set<string>()
  private status = { ...INITIAL_STATUS }
  constructor(private room: string, private document: CrdtDocument,
    private onStatus: (status: SessionStatus) => void,
    private onPreview: (id: string, element: BoardElement | null) => void) {}

  async start() {
    try {
      if (!['localhost', '127.0.0.1', '[::1]'].includes(location.hostname)) throw new Error('CRDT preview is local-only.')
      const response = await fetch('/api/rtc-config', { cache: 'no-store', signal: AbortSignal.any([this.controller.signal, AbortSignal.timeout(8_000)]) })
      if (!response.ok) throw new Error('Connection setup unavailable.')
      const config = rtcConfigSchema.parse(await response.json())
      if (!config.localDevelopment) throw new Error('CRDT preview requires a development server.')
      if (this.stopped) return
      const url = new URL('/signal-y-webrtc', location.href); url.protocol = location.protocol === 'https:' ? 'wss:' : 'ws:'
      const provider = this.provider = new WebrtcProvider(`meshboard-crdt:${this.room}`, this.document.ydoc, {
        signaling: [url.href], maxConns: 7, filterBcConns: false,
        peerOpts: {
          channelName: FRAGMENT_CHANNEL,
          config: { iceServers: config.iceServers, iceTransportPolicy: config.iceTransportPolicy },
        },
      })
      provider.on('peers', () => this.attachPeers())
      provider.awareness.on('change', () => this.showAwareness())
      for (const connection of provider.signalingConns) {
        connection.on('message', (message: { type: string; code?: string; topic?: string; data?: { type: string; from: string; to?: string; signal?: { type: string; sdp?: string } } }) => {
          if (message.type === 'error') {
            connection.disconnect()
            this.status.error = message.code === 'room-full' ? 'This board already has 8 participants.' : 'The connection service declined this session.'
            this.report()
          }
          const data = message.data, room = provider.room
          if (message.type === 'publish' && message.topic === room?.name && data?.type === 'signal' && data.to === room?.peerId && data.signal?.sdp) {
            const conn = room?.webrtcConns.get(data.from), state = conn && this.peers.get(conn)
            if (state) state.fragments = data.signal.sdp.split('\r\n').includes(FRAGMENT_SDP)
          }
        })
      }
      await provider.key
      if (this.stopped) return
      const room = provider.room!
      // filterBcConns=false alone does NOT disable BroadcastChannel document sync.
      // Every preview peer must actually exercise the WebRTC path and validation.
      bc.unsubscribe(room.name, room._bcSubscriber); room.bcconnected = false
      provider.awareness.setLocalState({ meshboard: { peerId: room.peerId, preview: null } })
      let ticks = 0
      this.timer = setInterval(() => {
        this.flush()
        if (++ticks % 25 === 0) { this.attachPeers(); this.report(); void this.routes() }
      }, 20)
      this.report()
    } catch (error) {
      if (!this.stopped) { this.status.error = error instanceof Error ? error.message : 'Could not start CRDT preview.'; this.report() }
    }
  }

  private validate(bytes: Uint8Array) {
    if (!bytes.length || bytes.length > MAX_Y_MESSAGE) throw new Error('Invalid message size')
    const decoder = decoding.createDecoder(bytes)
    switch (decoding.readVarUint(decoder)) {
      case 0: {
        const step = decoding.readVarUint(decoder), data = decoding.readVarUint8Array(decoder)
        if (step === 0) Y.decodeStateVector(data)
        else if (step === 1 || step === 2) this.document.validateUpdate(data)
        else throw new Error('Unsupported sync step')
        break
      }
      case 1: {
        const update = decoding.createDecoder(decoding.readVarUint8Array(decoder))
        const count = decoding.readVarUint(update)
        if (count > 64) throw new Error('Too many awareness clients')
        const ids = new Set(this.provider?.awareness.meta.keys())
        const incoming = new Set<number>()
        for (let i = 0; i < count; i++) {
          const id = decoding.readVarUint(update); decoding.readVarUint(update)
          if (incoming.has(id)) throw new Error('Duplicate awareness client')
          incoming.add(id); ids.add(id)
          if (ids.size > 64) throw new Error('Too many awareness clients')
          const raw = decoding.readVarString(update)
          if (new TextEncoder().encode(raw).length > 64 * 1024) throw new Error('Awareness exceeds limits')
          const value = JSON.parse(raw)
          if (value !== null && (typeof value !== 'object' || Array.isArray(value))) throw new Error('Invalid awareness')
          if (value?.meshboard != null && (typeof value.meshboard !== 'object' || Array.isArray(value.meshboard))) throw new Error('Invalid Meshboard awareness')
          if (value?.meshboard?.preview != null) elementSchema.parse(value.meshboard.preview)
        }
        if (decoding.hasContent(update)) throw new Error('Trailing awareness data')
        break
      }
      case 3: break
      default: throw new Error('Unsupported y-webrtc message')
    }
    if (decoding.hasContent(decoder)) throw new Error('Trailing message data')
  }
  private attachPeers() {
    for (const conn of this.provider?.room?.webrtcConns.values() ?? []) {
      if (this.peers.has(conn)) continue
      const peer = conn.peer as SimplePeer
      const state: PeerState = { queue: [], bytes: 0, relay: false, fragments: false }
      this.peers.set(conn, state)
      // Add capability to the signaled SDP, after simple-peer reads its local
      // description: WebRTC implementations can discard unknown SDP attributes.
      peer.prependListener('signal', signal => {
        if (signal.sdp && !signal.sdp.split('\r\n').includes(FRAGMENT_SDP)) signal.sdp = signal.sdp.replace('\r\nm=', `\r\n${FRAGMENT_SDP}\r\nm=`)
      })
      const send = peer.send.bind(peer)
      const fragments = () => peer._channel?.label === FRAGMENT_CHANNEL && state.fragments
      peer.send = bytes => {
        if (peer.destroyed) return
        // Native peers may ask for state before simple-peer finishes its ICE
        // stats/readiness checks. Dropping that reply leaves the newcomer empty:
        // our later state-vector request synchronizes the opposite direction.
        if (!bytes.length || bytes.length > MAX_Y_MESSAGE || state.bytes + bytes.length > 8 * 1024 * 1024) { peer.destroy(new Error('Peer is too slow.')); return }
        state.queue.push({ message: bytes.slice(), next: 0 }); state.bytes += bytes.length
        this.drain(peer, state, send)
      }
      peer.on('connect', () => this.drain(peer, state, send))
      peer.on('close', () => { state.queue.length = 0; state.bytes = 0 })
      const receive = peer.listeners('data')
      peer.removeAllListeners('data')
      const assembler = new YMessageReceiver()
      peer.on('data', bytes => {
        try {
          if (!(bytes instanceof Uint8Array)) throw new Error('Binary data required')
          const message = fragments() ? assembler.accept(bytes) : bytes
          if (!message) return
          this.validate(message)
          for (const listener of receive) listener(message)
        } catch {
          this.status.error = 'A peer sent invalid or oversized drawing data. Its connection was closed.'
          peer.destroy(); this.report()
        }
      })
      // Retain the original sender without repeatedly wrapping it during flush.
      this.senders.set(conn, send)
    }
    for (const conn of this.peers.keys()) if (conn.closed) { this.peers.delete(conn); this.senders.delete(conn) }
  }
  private senders = new Map<WebrtcConn, (bytes: Uint8Array) => void>()
  private drain(peer: SimplePeer, state: PeerState, send: (bytes: Uint8Array) => void) {
    try {
      while (!peer.destroyed && peer.connected && peer._channel && state.queue.length && peer._channel.bufferedAmount < 256 * 1024) {
        const pending = state.queue[0]
        if (!pending.frames) {
          const bytes = pending.message!
          // Choose framing only after channel/SDP negotiation has completed.
          pending.frames = peer._channel.label === FRAGMENT_CHANNEL && state.fragments ? fragmentYMessage(bytes) : [bytes]
          state.bytes += pending.frames.reduce((n, frame) => n + frame.length, 0) - bytes.length
          delete pending.message
          if (state.bytes > 8 * 1024 * 1024) throw new Error('Peer is too slow.')
        }
        const frame = pending.frames[pending.next]
        send(frame); pending.next++; state.bytes -= frame.length
        if (pending.next === pending.frames.length) state.queue.shift()
      }
    } catch { peer.destroy(new Error('Could not send drawing update.')) }
  }
  private flush() { for (const [conn, state] of this.peers) this.drain(conn.peer as SimplePeer, state, this.senders.get(conn)!) }
  private report() {
    if (this.stopped) return
    const connected = [...this.peers].filter(([conn]) => conn.connected)
    this.status = { ...this.status, signaling: this.provider?.signalingConns.some(conn => conn.connected) ?? false,
      connected: connected.length, connecting: [...this.peers.keys()].filter(conn => !conn.connected && !conn.closed).length,
      relay: connected.filter(([, state]) => state.relay).length }
    this.onStatus(this.status)
  }
  private async routes() {
    for (const [conn, state] of this.peers) {
      if (!conn.connected) continue
      try {
        const stats = await (conn.peer as SimplePeer)._pc.getStats()
        for (const entry of stats.values()) if (entry.type === 'transport' && entry.selectedCandidatePairId) {
          const pair = stats.get(entry.selectedCandidatePairId)
          state.relay = !!pair && [pair.localCandidateId, pair.remoteCandidateId].some(id => stats.get(id)?.candidateType === 'relay')
        }
      } catch { /* Peer closed while stats were in flight. */ }
    }
  }
  private showAwareness() {
    const next = new Set<string>()
    for (const [id, state] of this.provider?.awareness.getStates() ?? []) {
      if (id === this.document.ydoc.clientID) continue
      const key = String(id), element = state.meshboard?.preview
      const parsed = elementSchema.safeParse(element)
      if (parsed.success) { next.add(key); this.onPreview(key, parsed.data) }
    }
    for (const id of this.shown) if (!next.has(id)) this.onPreview(id, null)
    this.shown = next
  }
  preview(element: BoardElement | null) {
    // Awareness is ephemeral and deliberately smaller than committed strokes.
    this.pendingPreview = element && new TextEncoder().encode(JSON.stringify(element)).length <= 48 * 1024 ? element : null
    const send = () => {
      this.previewTimer = undefined
      const provider = this.provider
      if (provider?.room) provider.awareness.setLocalState({ meshboard: { peerId: provider.room.peerId, preview: this.pendingPreview } })
    }
    if (!element) { clearTimeout(this.previewTimer); send() }
    else if (!this.previewTimer) this.previewTimer = setTimeout(send, 50)
  }
  dispose() {
    this.stopped = true; this.controller.abort(); clearInterval(this.timer); clearTimeout(this.previewTimer)
    this.provider?.disconnect(); this.provider?.destroy(); this.provider?.awareness.destroy()
    this.peers.clear(); this.senders.clear()
    for (const id of this.shown) this.onPreview(id, null)
  }
}
