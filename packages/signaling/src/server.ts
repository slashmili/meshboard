import type { EventEmitter } from 'node:events'
import type { IncomingMessage, ServerResponse } from 'node:http'
import type { RtcConfig } from '@meshboard/shared-protocol'
import { attachYWebrtc } from './y-webrtc.ts'
export { rtcConfiguration, rtcConfigurationProvider } from './config.ts'

export function attachSignaling(server: EventEmitter, config: RtcConfig | (() => RtcConfig), options: { trustProxy?: boolean } = {}) {
  // TURN credentials are issued per request.
  const localDevelopment = (typeof config === 'function' ? config() : config).localDevelopment
  const closeYWebrtc = attachYWebrtc(server, true)
  const limits = new Map<string, { since: number; requests: number }>()
  function budget(request: IncomingMessage) {
    // Only enable behind a trusted proxy with no public path to this listener.
    const forwarded = options.trustProxy ? request.headers['x-forwarded-for'] : undefined
    const ip = (typeof forwarded === 'string' ? forwarded.split(',')[0].trim() : undefined) || request.socket.remoteAddress || 'unknown'
    let entry = limits.get(ip)
    if (!entry) {
      if (limits.size >= 4096) return undefined
      entry = { since: Date.now(), requests: 0 }
      limits.set(ip, entry)
    }
    if (Date.now() - entry.since >= 60_000) { entry.since = Date.now(); entry.requests = 0 }
    return entry
  }

  const upgrade = (request: IncomingMessage, socket: import('node:stream').Duplex) => {
    const path = request.url?.split('?')[0]
    if (path === '/signal-y-webrtc') return
    // Leave Vite's development HMR socket alone; obsolete protocols are closed.
    if (!localDevelopment || path === '/signal' || path === '/signal-crdt') socket.destroy()
  }
  server.on('upgrade', upgrade)
  const heartbeat = setInterval(() => {
    for (const [ip, limit] of limits) if (Date.now() - limit.since >= 60_000) limits.delete(ip)
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
    closeYWebrtc()
    clearInterval(heartbeat)
    server.off('upgrade', upgrade)
    limits.clear()
  }
  return { handleHttp, close }
}
