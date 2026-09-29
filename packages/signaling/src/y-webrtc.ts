import type { EventEmitter } from 'node:events'
import type { IncomingMessage } from 'node:http'
import { WebSocket, WebSocketServer } from 'ws'
import { MAX_SIGNAL_BYTES, MAX_PEERS, signalPayloadSchema } from '@meshboard/shared-protocol'

// The preview supports the unencrypted y-webrtc signaling profile, not a generic
// publish/subscribe relay: board updates and awareness must never pass here.
export function attachYWebrtc(server: EventEmitter, enabled: boolean) {
  const wss = new WebSocketServer({ noServer: true, maxPayload: MAX_SIGNAL_BYTES, perMessageDeflate: false })
  type Client = { socket: WebSocket; topic?: string; id?: string; alive: boolean }
  const clients = new Set<Client>()
  const topics = new Map<string, Set<Client>>()
  const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
  const validTopic = (value: unknown): value is string => typeof value === 'string' && value.startsWith('meshboard-crdt:') && uuid.test(value.slice(15))
  function send(client: Client, value: unknown) {
    if (client.socket.bufferedAmount > 256 * 1024) { client.socket.terminate(); return }
    if (client.socket.readyState === WebSocket.OPEN) client.socket.send(JSON.stringify(value))
  }
  function unsubscribe(client: Client) {
    if (!client.topic) return
    const members = topics.get(client.topic)
    members?.delete(client)
    if (!members?.size) topics.delete(client.topic)
    client.topic = undefined; client.id = undefined
  }
  function keys(value: Record<string, unknown>, ...allowed: string[]) {
    if (Object.keys(value).some(key => !allowed.includes(key))) throw new Error('Unexpected fields')
  }
  const upgrade = (request: IncomingMessage, socket: import('node:stream').Duplex, head: Buffer) => {
    if (request.url?.split('?')[0] !== '/signal-y-webrtc') return
    try {
      if (!enabled || clients.size >= 256 || (request.headers.origin && new URL(request.headers.origin).host !== request.headers.host)) { socket.destroy(); return }
    } catch { socket.destroy(); return }
    wss.handleUpgrade(request, socket, head, ws => wss.emit('connection', ws))
  }
  server.on('upgrade', upgrade)
  wss.on('connection', socket => {
    const client: Client = { socket, alive: true }; clients.add(client)
    let count = 0, window = Date.now()
    const timeout = setTimeout(() => { if (!client.topic) socket.close(1008, 'subscribe-timeout') }, 10_000)
    socket.on('error', () => {})
    socket.on('pong', () => { client.alive = true })
    socket.on('close', () => { clearTimeout(timeout); unsubscribe(client); clients.delete(client) })
    socket.on('message', (raw, binary) => {
      try {
        if (Date.now() - window >= 10_000) { count = 0; window = Date.now() }
        if (binary || ++count > 200) throw new Error('rate-limit')
        const message = JSON.parse(raw.toString())
        if (message.type === 'ping') { keys(message, 'type'); send(client, { type: 'pong' }); return }
        if (message.type === 'subscribe' || message.type === 'unsubscribe') {
          keys(message, 'type', 'topics')
          if (!Array.isArray(message.topics) || message.topics.length !== 1 || !validTopic(message.topics[0])) throw new Error('invalid-topic')
          const topic = message.topics[0]
          if (client.topic && client.topic !== topic) throw new Error('topic-switch')
          if (message.type === 'unsubscribe') { unsubscribe(client); return }
          const members = topics.get(topic) ?? new Set<Client>()
          if (!members.has(client) && members.size >= MAX_PEERS) throw new Error('room-full')
          members.add(client); topics.set(topic, members); client.topic = topic; clearTimeout(timeout)
          return
        }
        if (message.type !== 'publish') throw new Error('invalid-message')
        keys(message, 'type', 'topic', 'data')
        if (!client.topic || message.topic !== client.topic) throw new Error('not-subscribed')
        const data = message.data
        if (!data || typeof data !== 'object' || typeof data.from !== 'string' || !uuid.test(data.from)) throw new Error('invalid-sender')
        const members = topics.get(client.topic)!
        if (client.id && client.id !== data.from) throw new Error('sender-switch')
        if (!client.id && [...members].some(peer => peer !== client && peer.id === data.from)) throw new Error('duplicate-sender')
        if (data.type === 'announce') keys(data, 'type', 'from')
        else if (data.type === 'signal') {
          keys(data, 'type', 'from', 'to', 'token', 'signal')
          if (typeof data.to !== 'string' || !uuid.test(data.to) || typeof data.token !== 'number' || !Number.isFinite(data.token)) throw new Error('invalid-signal')
          const signal = data.signal
          if (signal?.type === 'offer' || signal?.type === 'answer') {
            keys(signal, 'type', 'sdp')
            signalPayloadSchema.parse({ description: signal })
          } else if (signal?.type === 'candidate') {
            keys(signal, 'type', 'candidate')
            signalPayloadSchema.parse({ candidate: signal.candidate })
          } else throw new Error('unsupported-signal')
        } else throw new Error('invalid-publish')
        client.id = data.from
        for (const peer of members) send(peer, { type: 'publish', topic: client.topic, data, clients: members.size })
      } catch (error) {
        const code = error instanceof Error && error.message === 'room-full' ? 'room-full' : 'invalid-message'
        send(client, { type: 'error', code }); socket.close(1008, code)
      }
    })
  })
  const heartbeat = setInterval(() => {
    for (const client of clients) {
      if (!client.alive) client.socket.terminate()
      else { client.alive = false; client.socket.ping() }
    }
  }, 15_000)
  heartbeat.unref()
  return () => {
    clearInterval(heartbeat); server.off('upgrade', upgrade)
    for (const client of clients) client.socket.terminate()
    wss.close(); topics.clear()
  }
}
