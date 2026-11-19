import { WebrtcProvider, type WebrtcConn } from 'y-webrtc'
import * as decoding from 'lib0/decoding'
import * as Y from 'yjs'
import { elementSchema, rtcConfigSchema, type BoardElement } from '@meshboard/shared-protocol'
import { SecureChannel, SECURE_CHANNEL } from './SecureChannel'
import { CrdtDocument } from './CrdtDocument'
import { INITIAL_STATUS, type SessionStatus } from './SessionStatus'
import { fragmentYMessage, MAX_Y_MESSAGE, YMessageReceiver } from './YTransport'

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
type PeerState = { queue: OutgoingMessage[]; bytes: number; relay: boolean; secure?: SecureChannel; draining: boolean; deadline: ReturnType<typeof setTimeout> }

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
  constructor(private room: string, private key: string, private document: CrdtDocument,
    private onStatus: (status: SessionStatus) => void,
    private onPreview: (id: string, element: BoardElement | null) => void) {}

  async start() {
    try {
      const response = await fetch('/api/rtc-config', { cache: 'no-store', signal: AbortSignal.any([this.controller.signal, AbortSignal.timeout(8_000)]) })
      if (!response.ok) throw new Error('Connection setup unavailable.')
      const config = rtcConfigSchema.parse(await response.json())
      if (this.stopped) return
      const url = new URL('/signal-y-webrtc', location.href); url.protocol = location.protocol === 'https:' ? 'wss:' : 'ws:'
      const provider = this.provider = new WebrtcProvider(`meshboard-crdt:${this.room}`, this.document.ydoc, {
        signaling: [url.href], maxConns: 7, filterBcConns: false, disableBc: true,
        peerOpts: {
          channelName: SECURE_CHANNEL,
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

        })
      }
      await provider.key
      if (this.stopped) return
      const room = provider.room!
      provider.awareness.setLocalState({ meshboard: { peerId: room.peerId, preview: null } })
      let ticks = 0
      this.timer = setInterval(() => {
        this.flush()
        if (++ticks % 25 === 0) { this.attachPeers(); this.report(); void this.routes() }
      }, 20)
      this.report()
    } catch (error) {
      if (!this.stopped) { this.status.error = error instanceof Error ? error.message : 'Could not start this session.'; this.report() }
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
      const state: PeerState = { queue: [], bytes: 0, relay: false, draining: false,
        deadline: setTimeout(() => { peer.destroy(new Error('Peer authentication timed out.')) }, 20_000) }
      this.peers.set(conn, state)
      const send = peer.send.bind(peer)
      peer.send = bytes => {
        if (peer.destroyed) return
        // Native peers may ask for state before simple-peer finishes its ICE
        // stats/readiness checks. Dropping that reply leaves the newcomer empty:
        // our later state-vector request synchronizes the opposite direction.
        if (!bytes.length || bytes.length > MAX_Y_MESSAGE || state.bytes + bytes.length > 8 * 1024 * 1024) { peer.destroy(new Error('Peer is too slow.')); return }
        state.queue.push({ message: bytes.slice(), next: 0 }); state.bytes += bytes.length
        this.drain(peer, state, send)
      }
      let resolveReady: () => void
      const ready = new Promise<void>(resolve => { resolveReady = resolve })
      const fail = () => {
        state.secure?.close(); peer.destroy()
        this.status.error = 'A peer could not authenticate or sent invalid encrypted data.'; this.report()
      }
      peer.on('connect', () => {
        try {
          if (peer._channel?.label !== SECURE_CHANNEL) throw new Error('Encrypted channel required')
          state.secure = new SecureChannel(this.key, this.room, this.provider!.room!.peerId, conn.remotePeerId,
            peer._pc.localDescription!.sdp, peer._pc.remoteDescription!.sdp)
          send(state.secure.hello); resolveReady!()
        } catch { fail(); resolveReady!() }
      })
      peer.on('close', () => {
        clearTimeout(state.deadline); state.secure?.close(); state.queue.length = 0; state.bytes = 0; resolveReady!()
      })
      const receive = peer.listeners('data')
      peer.removeAllListeners('data')
      const assembler = new YMessageReceiver()
      let chain = Promise.resolve(), incomingBytes = 0, incomingFrames = 0
      peer.on('data', bytes => {
        if (peer.destroyed) return
        if (!(bytes instanceof Uint8Array) || bytes.length < 29 || ++incomingFrames > 512 || bytes.length > 16 * 1024 + 28 || (incomingBytes += bytes.length) > 8 * 1024 * 1024) { fail(); return }
        const copy = bytes.slice()
        chain = chain.then(async () => {
          await ready
          if (peer.destroyed || !state.secure) return
          const result = await state.secure.receive(copy)
          if (peer.destroyed) return
          if (result.response) send(result.response)
          if (result.admitted) { clearTimeout(state.deadline); this.report(); this.drain(peer, state, send) }
          if (result.data) {
            const message = assembler.accept(result.data)
            if (message) { this.validate(message); for (const listener of receive) listener(message) }
          }
        }).catch(fail).finally(() => { incomingBytes -= copy.length; incomingFrames-- })
      })
      // Retain the original sender without repeatedly wrapping it during flush.
      this.senders.set(conn, send)
    }
    for (const conn of this.peers.keys()) if (conn.closed) { this.peers.delete(conn); this.senders.delete(conn) }
  }
  private senders = new Map<WebrtcConn, (bytes: Uint8Array) => void>()
  private async drain(peer: SimplePeer, state: PeerState, send: (bytes: Uint8Array) => void) {
    if (state.draining || !state.secure?.authenticated) return
    state.draining = true
    try {
      while (!peer.destroyed && peer.connected && peer._channel && state.queue.length && peer._channel.bufferedAmount < 256 * 1024) {
        const pending = state.queue[0]
        if (!pending.frames) {
          const bytes = pending.message!
          // Choose framing only after channel/SDP negotiation has completed.
          pending.frames = fragmentYMessage(bytes)
          state.bytes += pending.frames.reduce((n, frame) => n + frame.length, 0) - bytes.length
          delete pending.message
          if (state.bytes > 8 * 1024 * 1024) throw new Error('Peer is too slow.')
        }
        const frame = pending.frames[pending.next]
        const encrypted = await state.secure.encrypt(frame)
        if (peer.destroyed) return
        send(encrypted); pending.next++; state.bytes -= frame.length
        if (pending.next === pending.frames.length) state.queue.shift()
      }
    } catch { peer.destroy(new Error('Could not send drawing update.')) } finally { state.draining = false }
  }
  private flush() { for (const [conn, state] of this.peers) this.drain(conn.peer as SimplePeer, state, this.senders.get(conn)!) }
  private report() {
    if (this.stopped) return
    const connected = [...this.peers].filter(([conn, state]) => conn.connected && state.secure?.authenticated)
    this.status = { ...this.status, signaling: this.provider?.signalingConns.some(conn => conn.connected) ?? false,
      connected: connected.length, connecting: [...this.peers].filter(([conn, state]) => !state.secure?.authenticated && !conn.closed).length,
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
    for (const state of this.peers.values()) { clearTimeout(state.deadline); state.secure?.close() }
    this.peers.clear(); this.senders.clear()
    for (const id of this.shown) this.onPreview(id, null)
  }
}
