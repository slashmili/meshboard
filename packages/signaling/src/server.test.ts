import { createServer, type Server } from 'node:http'
import type { AddressInfo } from 'node:net'
import { WebSocket } from 'ws'
import { afterEach, describe, expect, it } from 'vitest'
import { rtcConfigSchema } from '@meshboard/shared-protocol'
import { attachSignaling, rtcConfiguration, rtcConfigurationProvider } from './server.ts'

const cleanups: (() => Promise<void>)[] = []
afterEach(async () => { for (const cleanup of cleanups.splice(0)) await cleanup() })

async function fixture(config: Parameters<typeof attachSignaling>[1] = rtcConfiguration({}, true), trustProxy = false) {
  const server: Server = createServer((request, response) => relay.handleHttp(request, response, () => response.writeHead(404).end()))
  const relay = attachSignaling(server, config, { trustProxy })
  await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve))
  const base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`
  cleanups.push(() => { relay.close(); return new Promise(resolve => server.close(() => resolve())) })
  return { base }
}

describe('signaling-only relay', () => {
  it.each([false, true])('rejects obsolete signaling endpoints (local=%s)', async localDevelopment => {
    const { base } = await fixture({ ...rtcConfiguration({}, true), localDevelopment })
    for (const path of ['/signal', '/signal-crdt']) {
      const socket = new WebSocket(base.replace('http:', 'ws:') + path)
      await new Promise<void>((resolve, reject) => {
        socket.on('error', () => resolve())
        socket.on('open', () => { socket.close(); reject(new Error('Obsolete protocol accepted')) })
      })
    }
  })
  it.each([false, true])('serves CRDT signaling with production configuration (provider=%s)', async provider => {
    const config = { ...rtcConfiguration({}, true), localDevelopment: false }
    const { base } = await fixture(provider ? () => config : config)
    const socket = new WebSocket(base.replace('http:', 'ws:') + '/signal-y-webrtc')
    await new Promise<void>((resolve, reject) => {
      socket.on('error', reject)
      socket.on('open', () => socket.send(JSON.stringify({ type: 'ping' })))
      socket.on('message', data => {
        try { expect(JSON.parse(data.toString())).toEqual({ type: 'pong' }); socket.close(); resolve() }
        catch (error) { reject(error) }
      })
    })
  })
  it('serves uncached connection settings and requires TURN outside local development', async () => {
    const { base } = await fixture()
    const response = await fetch(`${base}/api/rtc-config`)
    expect(response.headers.get('cache-control')).toBe('no-store')
    expect(rtcConfigSchema.parse(await response.json()).iceServers[0].urls[0]).toContain('turn:')
    expect(() => rtcConfiguration({})).toThrow('Configure TURN')
    expect(rtcConfiguration({ MESHBOARD_TURN_URLS: 'turn:relay.example:3478', MESHBOARD_TURN_USERNAME: 'user', MESHBOARD_TURN_CREDENTIAL: 'test', MESHBOARD_RELAY_ONLY: 'true' }).iceTransportPolicy).toBe('relay')
  })

  it('issues fresh credentials on each HTTP request without exposing the signing secret', async () => {
    const secret = 'ab'.repeat(32)
    const { base } = await fixture(rtcConfigurationProvider({ MESHBOARD_TURN_SECRET: secret, MESHBOARD_TURN_URLS: 'turn:turn.example.com:3478' }))
    const a = rtcConfigSchema.parse(await (await fetch(`${base}/api/rtc-config`)).json())
    const b = rtcConfigSchema.parse(await (await fetch(`${base}/api/rtc-config`)).json())
    expect(a.iceServers[0].username).not.toBe(b.iceServers[0].username)
    expect(JSON.stringify(a)).not.toContain(secret)
  })

  it('limits credential issuance, ignores untrusted forwarded headers, and keeps health available', async () => {
    const { base } = await fixture()
    for (let i = 0; i < 30; i++) {
      expect((await fetch(`${base}/api/rtc-config`, { headers: { 'x-forwarded-for': `192.0.2.${i}` } })).status).toBe(200)
    }
    const limited = await fetch(`${base}/api/rtc-config`)
    expect(limited.status).toBe(429)
    expect(limited.headers.get('retry-after')).toBe('60')
    expect(limited.headers.get('cache-control')).toBe('no-store')
    expect((await fetch(`${base}/health`)).status).toBe(200)
  })

  it('separates client budgets only when configured behind a trusted proxy', async () => {
    const { base } = await fixture(undefined, true)
    for (let i = 0; i < 31; i++) await fetch(`${base}/api/rtc-config`, { headers: { 'x-forwarded-for': '192.0.2.1' } })
    expect((await fetch(`${base}/api/rtc-config`, { headers: { 'x-forwarded-for': '192.0.2.1' } })).status).toBe(429)
    expect((await fetch(`${base}/api/rtc-config`, { headers: { 'x-forwarded-for': '192.0.2.2' } })).status).toBe(200)
  })
})
