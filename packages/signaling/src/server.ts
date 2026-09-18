import { randomUUID } from 'node:crypto'
import type { EventEmitter } from 'node:events'
import type { IncomingMessage, ServerResponse } from 'node:http'
import { WebSocket, WebSocketServer } from 'ws'
import { clientSignalSchema, MAX_PEERS, MAX_SIGNAL_BYTES, type RtcConfig, type ServerSignal } from '@meshboard/shared-protocol'
export { rtcConfiguration, rtcConfigurationProvider } from './config.ts'

type Client = { id: string; socket: WebSocket; room?: string; alive: boolean; count: number; window: number }

export function attachSignaling(server: EventEmitter, config: RtcConfig | (() => RtcConfig), options: { trustProxy?: boolean } = {}) {
  const rooms = new Map<string, Map<string, Client>>()
  const clients = new Set<Client>()
  const wss = new WebSocketServer({ noServer: true, maxPayload: MAX_SIGNAL_BYTES, perMessageDeflate: false })
  const limits = new Map<string, { since: number; requests: number; upgrades: number; connections: number }>()
  function budget(request: IncomingMessage) {
    // Only enable behind a trusted proxy with no public path to this listener.
    const forwarded = options.trustProxy ? request.headers['x-forwarded-for'] : undefined
    const ip = (typeof forwarded === 'string' ? forwarded.split(',')[0].trim() : undefined) || request.socket.remoteAddress || 'unknown'
    let entry = limits.get(ip)
    if (!entry) {
      if (limits.size >= 4096) return undefined
      entry = { since: Date.now(), requests: 0, upgrades: 0, connections: 0 }
      limits.set(ip, entry)
    }
    if (Date.now() - entry.since >= 60_000) { entry.since = Date.now(); entry.requests = 0; entry.upgrades = 0 }
    return entry
  }

  function send(client: Client, message: ServerSignal) {
    if (client.socket.readyState !== WebSocket.OPEN) return
    if (client.socket.bufferedAmount > 256 * 1024) { client.socket.terminate(); return }
    client.socket.send(JSON.stringify(message))
  }

  function reject(client: Client, code: Extract<ServerSignal, { type: 'error' }>['code']) {
    send(client, { v: 1, type: 'error', code })
    client.socket.close(1008, code)
  }

  const upgrade = (request: IncomingMessage, socket: import('node:stream').Duplex, head: Buffer) => {
    if (request.url?.split('?')[0] !== '/signal') { socket.destroy(); return }
    // Browser requests must be same-origin. Native clients may omit Origin.
    const origin = request.headers.origin
    if (origin) {
      try { if (new URL(origin).host !== request.headers.host) { socket.destroy(); return } }
      catch { socket.destroy(); return }
    }
    if (clients.size >= 256) { socket.destroy(); return }
    const limit = budget(request)
    if (!limit || ++limit.upgrades > 30 || limit.connections >= 16) { socket.destroy(); return }
    wss.handleUpgrade(request, socket, head, ws => {
      limit.connections++
      ws.on('close', () => { limit.connections-- })
      wss.emit('connection', ws)
    })
  }
  server.on('upgrade', upgrade)

  wss.on('connection', socket => {
    const client: Client = { id: randomUUID(), socket, alive: true, count: 0, window: Date.now() }
    clients.add(client)
    const joinTimeout = setTimeout(() => { if (!client.room) socket.close(1008, 'join-timeout') }, 10_000)
    socket.on('pong', () => { client.alive = true })
    socket.on('error', () => { /* Close handles cleanup; never log signaling payloads. */ })
    socket.on('message', (data, isBinary) => {
      if (Date.now() - client.window >= 10_000) { client.window = Date.now(); client.count = 0 }
      if (++client.count > 200) { reject(client, 'rate-limit'); return }
      if (isBinary) { reject(client, 'invalid-message'); return }
      let value: unknown
      try { value = JSON.parse(data.toString()) } catch { reject(client, 'invalid-message'); return }
      const parsed = clientSignalSchema.safeParse(value)
      if (!parsed.success) { reject(client, 'invalid-message'); return }
      const message = parsed.data
      if (message.type === 'join') {
        if (client.room) { reject(client, 'invalid-message'); return }
        const room = rooms.get(message.room) ?? new Map<string, Client>()
        if (room.size >= MAX_PEERS) { reject(client, 'room-full'); return }
        clearTimeout(joinTimeout)
        client.room = message.room
        send(client, { v: 1, type: 'welcome', self: client.id, peers: [...room.keys()] })
        for (const peer of room.values()) send(peer, { v: 1, type: 'peer-joined', peer: client.id })
        room.set(client.id, client)
        rooms.set(message.room, room)
      } else {
        if (!client.room) { reject(client, 'invalid-message'); return }
        const peer = rooms.get(client.room)?.get(message.to)
        if (peer && peer !== client) send(peer, { v: 1, type: 'signal', from: client.id, payload: message.payload })
      }
    })
    socket.on('close', () => {
      clearTimeout(joinTimeout)
      clients.delete(client)
      const room = client.room ? rooms.get(client.room) : undefined
      if (!room) return
      room.delete(client.id)
      for (const peer of room.values()) send(peer, { v: 1, type: 'peer-left', peer: client.id })
      if (room.size === 0) rooms.delete(client.room!)
    })
  })

  const heartbeat = setInterval(() => {
    for (const [ip, limit] of limits) if (!limit.connections && Date.now() - limit.since >= 60_000) limits.delete(ip)
    for (const client of clients) {
      if (!client.alive) { client.socket.terminate(); continue }
      client.alive = false
      client.socket.ping()
    }
  }, 15_000)
  heartbeat.unref()

  function handleHttp(request: IncomingMessage, response: ServerResponse, next: () => void) {
    if (request.url !== '/api/rtc-config' && request.url !== '/health') { next(); return }
    if (request.method !== 'GET') { response.writeHead(405).end(); return }
    if (request.url === '/api/rtc-config') {
      const limit = budget(request)
      if (!limit || ++limit.requests > 30) {
        response.writeHead(429, { 'retry-after': '60', 'cache-control': 'no-store' }).end()
        return
      }
    }
    response.writeHead(200, { 'content-type': 'application/json', 'cache-control': 'no-store', 'x-content-type-options': 'nosniff' })
    response.end(JSON.stringify(request.url === '/health' ? { ok: true } : typeof config === 'function' ? config() : config))
  }

  function close() {
    clearInterval(heartbeat)
    server.off('upgrade', upgrade)
    for (const client of clients) client.socket.terminate()
    wss.close()
    rooms.clear()
    limits.clear()
  }
  return { handleHttp, close }
}
