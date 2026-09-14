import { rtcConfigSchema, serverSignalSchema, type BoardElement, type BoardMessage, type RtcConfig, type SignalPayload } from '@meshboard/shared-protocol'
import { BoardDocument } from './BoardDocument'
import { encodeFrames, FrameReceiver } from './frames'

export type SessionStatus = {
  signaling: boolean; connected: number; connecting: number; relay: number;
  error: string | null; localDevelopment: boolean;
}
export const INITIAL_STATUS: SessionStatus = { signaling: false, connected: 0, connecting: 0, relay: 0, error: null, localDevelopment: true }
type Peer = {
  pc: RTCPeerConnection; channel?: RTCDataChannel; queue: string[]; queuedBytes: number;
  receiver: FrameReceiver; candidates: RTCIceCandidateInit[]; signals: Promise<void>;
  timeout?: ReturnType<typeof setTimeout>; route: 'direct' | 'relay';
}

export class MeshSession {
  private socket: WebSocket | null = null
  private config: RtcConfig | null = null
  private self = ''
  private peers = new Map<string, Peer>()
  private desired = new Set<string>()
  private attempts = new Map<string, number>()
  private stopped = false
  private controller = new AbortController()
  private reconnect?: ReturnType<typeof setTimeout>
  private reconnectDelay = 1_000
  private previewTimer?: ReturnType<typeof setTimeout>
  private lastPreview: BoardElement | null = null
  private timers = new Set<ReturnType<typeof setTimeout>>()
  private unsubscribe: () => void
  private status = { ...INITIAL_STATUS }

  constructor(private room: string, private document: BoardDocument,
    private onStatus: (status: SessionStatus) => void,
    private onPreview: (peer: string, element: BoardElement | null) => void) {
    this.unsubscribe = document.onLocalMessage(message => this.broadcast(message))
  }

  async start() {
    try {
      const response = await fetch('/api/rtc-config', { cache: 'no-store', signal: AbortSignal.any([this.controller.signal, AbortSignal.timeout(8_000)]) })
      if (!response.ok) throw new Error('Connection setup is unavailable. Try again shortly.')
      this.config = rtcConfigSchema.parse(await response.json())
      if (this.stopped) return
      this.report({ localDevelopment: this.config.localDevelopment })
      this.connectSocket()
    } catch {
      if (!this.stopped) this.report({ error: 'Could not load connection settings. Your drawing is still in this tab.' })
    }
  }

  private report(change: Partial<SessionStatus> = {}) {
    if (this.stopped) return
    const open = [...this.peers.values()].filter(peer => peer.channel?.readyState === 'open')
    this.status = { ...this.status, connected: open.length, connecting: this.peers.size - open.length, relay: open.filter(peer => peer.route === 'relay').length, ...change }
    this.onStatus(this.status)
  }

  private connectSocket() {
    if (this.stopped) return
    const url = new URL('/signal', location.href)
    url.protocol = location.protocol === 'https:' ? 'wss:' : 'ws:'
    const socket = new WebSocket(url)
    this.socket = socket
    socket.onopen = () => socket.send(JSON.stringify({ v: 1, type: 'join', room: this.room }))
    socket.onmessage = event => {
      try {
        const message = serverSignalSchema.parse(JSON.parse(event.data))
        if (message.type === 'welcome') {
          // A new signaling identity requires renegotiation after reconnect.
          if (this.self && this.self !== message.self) for (const id of [...this.peers.keys()]) this.dropPeer(id)
          this.self = message.self
          this.desired = new Set(message.peers)
          this.attempts.clear()
          this.reconnectDelay = 1_000
          this.report({ signaling: true, error: null })
          for (const id of this.desired) this.ensurePeer(id)
        } else if (message.type === 'peer-joined') {
          this.desired.add(message.peer)
          this.ensurePeer(message.peer)
        } else if (message.type === 'peer-left') {
          this.desired.delete(message.peer)
          // Allow a signaling shutdown to finish before treating this as a leave.
          const peer = this.peers.get(message.peer)
          if (peer?.channel?.readyState !== 'open') this.dropPeer(message.peer)
          else {
            clearTimeout(peer.timeout)
            peer.timeout = setTimeout(() => {
              if (this.socket?.readyState === WebSocket.OPEN && !this.desired.has(message.peer)) this.dropPeer(message.peer)
            }, 1_000)
          }
        } else if (message.type === 'signal') {
          if (!this.desired.has(message.from)) return
          const peer = this.ensurePeer(message.from)
          if (peer) peer.signals = peer.signals.then(() => this.receiveSignal(peer, message.payload)).catch(() => this.failPeer(message.from, peer))
        } else {
          const error = message.code === 'room-full' ? 'This board already has 8 participants.' : 'The connection service declined this session. Try again.'
          this.report({ error })
          // Policy rejection is not a transient failure: do not retry in a loop.
          socket.onclose = () => this.report({ signaling: false })
          socket.close()
        }
      } catch { this.report({ error: 'The connection service sent an unsupported message.' }); socket.close() }
    }
    socket.onerror = () => { /* onclose retries without logging connection metadata. */ }
    socket.onclose = () => {
      if (this.stopped || this.socket !== socket) return
      this.report({ signaling: false, error: 'Reconnecting to the connection service… Existing peer connections can still draw.' })
      this.reconnect = setTimeout(() => this.connectSocket(), this.reconnectDelay)
      this.reconnectDelay = Math.min(8_000, this.reconnectDelay * 2)
    }
  }

  private sendSignal(to: string, payload: SignalPayload) {
    if (this.socket?.readyState === WebSocket.OPEN) this.socket.send(JSON.stringify({ v: 1, type: 'signal', to, payload }))
  }

  private ensurePeer(id: string): Peer | undefined {
    if (this.peers.has(id)) return this.peers.get(id)
    if (this.stopped || !this.config || !this.desired.has(id)) return
    const attempts = this.attempts.get(id) ?? 0
    if (attempts >= 3) { this.report({ error: 'A peer could not connect. Check the network or retry the connection.' }); return }
    this.attempts.set(id, attempts + 1)
    const pc = new RTCPeerConnection({ iceServers: this.config.iceServers, iceTransportPolicy: this.config.iceTransportPolicy })
    const peer: Peer = { pc, queue: [], queuedBytes: 0, candidates: [], signals: Promise.resolve(), receiver: new FrameReceiver(), route: 'direct' }
    this.peers.set(id, peer)
    pc.onicecandidate = event => {
      if (event.candidate) {
        const value = event.candidate.toJSON()
        this.sendSignal(id, { candidate: { candidate: value.candidate ?? '', sdpMid: value.sdpMid ?? null, sdpMLineIndex: value.sdpMLineIndex ?? null, usernameFragment: value.usernameFragment } })
      }
    }
    pc.onconnectionstatechange = () => {
      if (pc.connectionState === 'failed') this.failPeer(id, peer)
      if (pc.connectionState === 'connected') void this.updateRoute(peer)
    }
    pc.ondatachannel = event => this.attachChannel(id, peer, event.channel)
    peer.timeout = setTimeout(() => this.failPeer(id, peer), 20_000)
    if (this.self < id) {
      this.attachChannel(id, peer, pc.createDataChannel('meshboard.v1', { ordered: true }))
      void (async () => {
        await pc.setLocalDescription(await pc.createOffer())
        if (this.peers.get(id) === peer) this.sendSignal(id, { description: { type: 'offer', sdp: pc.localDescription!.sdp } })
      })().catch(() => this.failPeer(id, peer))
    }
    this.report()
    return peer
  }

  private async receiveSignal(peer: Peer, payload: SignalPayload) {
    if ('description' in payload) {
      await peer.pc.setRemoteDescription(payload.description)
      for (const candidate of peer.candidates.splice(0)) await peer.pc.addIceCandidate(candidate)
      if (payload.description.type === 'offer') {
        await peer.pc.setLocalDescription(await peer.pc.createAnswer())
        const id = [...this.peers].find(([, value]) => value === peer)?.[0]
        if (id) this.sendSignal(id, { description: { type: 'answer', sdp: peer.pc.localDescription!.sdp } })
      }
    } else if (peer.pc.remoteDescription) await peer.pc.addIceCandidate(payload.candidate)
    else {
      if (peer.candidates.length >= 128) throw new Error('Too many ICE candidates.')
      peer.candidates.push(payload.candidate)
    }
  }

  private attachChannel(id: string, peer: Peer, channel: RTCDataChannel) {
    if (peer.channel || channel.label !== 'meshboard.v1' || !channel.ordered || channel.maxRetransmits !== null || channel.maxPacketLifeTime !== null) {
      channel.close(); return
    }
    peer.channel = channel
    channel.bufferedAmountLowThreshold = 64 * 1024
    channel.onbufferedamountlow = () => this.flush(id, peer)
    let initialized = false
    const opened = () => {
      if (initialized || this.peers.get(id) !== peer) return
      initialized = true
      clearTimeout(peer.timeout)
      this.attempts.set(id, 0)
      // Finish incoming-channel setup before sending. Chromium can otherwise leave
      // bufferedAmount stuck at the bytes sent during the opening event, preventing
      // large snapshots from resuming after the first backpressure limit.
      const timer = setTimeout(() => {
        this.timers.delete(timer)
        if (this.peers.get(id) === peer) this.send(id, peer, this.document.snapshot())
      }, 0)
      this.timers.add(timer)
      void this.updateRoute(peer)
      this.report({ error: null })
    }
    channel.onmessage = event => {
      try {
        const message = peer.receiver.accept(event.data)
        if (!message) return
        if (message.type === 'preview') this.onPreview(id, message.element)
        else {
          this.onPreview(id, null)
          this.document.receive(message)
        }
      } catch {
        this.report({ error: 'A peer sent invalid or oversized drawing data. Its connection was closed.' })
        this.desired.delete(id)
        this.dropPeer(id)
      }
    }
    channel.onclose = () => this.failPeer(id, peer)
    channel.onerror = () => this.failPeer(id, peer)
    channel.onopen = opened
    // An incoming channel can already be open when its datachannel event is handled.
    if (channel.readyState === 'open') opened()
  }

  private send(id: string, peer: Peer, message: BoardMessage) {
    if (peer.channel?.readyState !== 'open') return
    try {
      const frames = encodeFrames(message)
      const bytes = frames.reduce((total, frame) => total + frame.length, 0)
      if (peer.queuedBytes + bytes > 8 * 1024 * 1024) throw new Error('Peer is too slow.')
      peer.queue.push(...frames)
      peer.queuedBytes += bytes
      this.flush(id, peer)
    } catch { this.failPeer(id, peer) }
  }

  private flush(id: string, peer: Peer) {
    const channel = peer.channel
    if (channel?.readyState !== 'open') return
    try {
      while (peer.queue.length && channel.bufferedAmount < 256 * 1024) {
        const frame = peer.queue[0]
        channel.send(frame)
        peer.queue.shift()
        peer.queuedBytes -= frame.length
      }
    } catch { this.failPeer(id, peer) }
  }

  private broadcast(message: BoardMessage) {
    for (const [id, peer] of this.peers) this.send(id, peer, message)
  }

  preview(element: BoardElement | null) {
    this.lastPreview = element
    if (!element) {
      clearTimeout(this.previewTimer)
      this.previewTimer = undefined
      this.broadcast({ v: 1, type: 'preview', element: null })
    } else if (!this.previewTimer) {
      this.previewTimer = setTimeout(() => {
        this.previewTimer = undefined
        this.broadcast({ v: 1, type: 'preview', element: this.lastPreview })
      }, 50)
    }
  }

  private async updateRoute(peer: Peer) {
    try {
      const stats = await peer.pc.getStats()
      for (const report of stats.values()) {
        if (report.type !== 'transport' || !report.selectedCandidatePairId) continue
        const pair = stats.get(report.selectedCandidatePairId)
        if (pair) peer.route = stats.get(pair.localCandidateId)?.candidateType === 'relay' || stats.get(pair.remoteCandidateId)?.candidateType === 'relay' ? 'relay' : 'direct'
      }
      this.report()
    } catch { /* The peer can close while stats are in flight. */ }
  }

  private dropPeer(id: string) {
    const peer = this.peers.get(id)
    if (!peer) return
    this.peers.delete(id)
    clearTimeout(peer.timeout)
    peer.pc.onconnectionstatechange = null
    if (peer.channel) { peer.channel.onclose = null; peer.channel.onerror = null; peer.channel.close() }
    peer.pc.close()
    this.onPreview(id, null)
    this.report()
  }

  private failPeer(id: string, peer: Peer) {
    if (this.peers.get(id) !== peer) return
    this.dropPeer(id)
    if (!this.stopped && this.desired.has(id) && this.socket?.readyState === WebSocket.OPEN) {
      const timer = setTimeout(() => { this.timers.delete(timer); this.ensurePeer(id) }, 1_500)
      this.timers.add(timer)
    }
  }

  dispose() {
    this.stopped = true
    this.controller.abort()
    this.unsubscribe()
    clearTimeout(this.reconnect)
    clearTimeout(this.previewTimer)
    for (const timer of this.timers) clearTimeout(timer)
    for (const id of [...this.peers.keys()]) this.dropPeer(id)
    if (this.socket) { this.socket.onclose = null; this.socket.onmessage = null; this.socket.close() }
  }
}
