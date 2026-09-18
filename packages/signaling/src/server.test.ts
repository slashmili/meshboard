import { createServer, type Server } from 'node:http'
import type { AddressInfo } from 'node:net'
import { randomUUID } from 'node:crypto'
import { WebSocket } from 'ws'
import { afterEach, describe, expect, it } from 'vitest'
import { rtcConfigSchema, type ServerSignal } from '@meshboard/shared-protocol'
import { attachSignaling, rtcConfiguration } from './server.ts'

const cleanups: (() => Promise<void>)[] = []
afterEach(async () => { for (const cleanup of cleanups.splice(0)) await cleanup() })

async function fixture(localDevelopment = true) {
  const server: Server = createServer((request, response) => relay.handleHttp(request, response, () => response.writeHead(404).end()))
  const relay = attachSignaling(server, { ...rtcConfiguration({}, true), localDevelopment })
  await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve))
  const base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`
  cleanups.push(() => { relay.close(); return new Promise(resolve => server.close(() => resolve())) })
  async function connect(room: string, path = '/signal') {
    const socket = new WebSocket(base.replace('http:', 'ws:') + path)
    const messages: ServerSignal[] = []
    socket.on('message', data => messages.push(JSON.parse(data.toString())))
    await new Promise<void>(resolve => socket.on('open', resolve))
    socket.send(JSON.stringify({ v: 1, type: 'join', room }))
    await expect.poll(() => messages[0]).toBeTruthy()
    return { socket, messages, first: messages[0] }
  }
  return { base, connect }
}

describe('signaling-only relay', () => {
  it('isolates legacy and CRDT preview rooms even when their UUIDs match', async () => {
    const { connect } = await fixture()
    const room = randomUUID()
    const legacy = await connect(room), preview = await connect(room, '/signal-crdt')
    const other = await connect(room, '/signal-crdt')
    if (legacy.first.type !== 'welcome' || preview.first.type !== 'welcome' || other.first.type !== 'welcome') throw new Error('Missing welcome')
    expect(preview.first.peers).toEqual([])
    expect(other.first.peers).toEqual([preview.first.self])
    legacy.socket.send(JSON.stringify({ v: 1, type: 'signal', to: preview.first.self, payload: { description: { type: 'offer', sdp: 'v=0\r\n' } } }))
    legacy.socket.send(JSON.stringify({ v: 1, type: 'join', room }))
    await expect.poll(() => legacy.messages.some(message => message.type === 'error')).toBe(true)
    expect(preview.messages.some(message => message.type === 'signal')).toBe(false)
  })
  it('does not expose the preview signaling endpoint in production configuration', async () => {
    const { base } = await fixture(false)
    const socket = new WebSocket(base.replace('http:', 'ws:') + '/signal-crdt')
    await new Promise<void>((resolve, reject) => { socket.on('error', () => resolve()); socket.on('open', () => { socket.close(); reject(new Error('Preview endpoint was exposed')) }) })
  })
  it('introduces peers and forwards connection metadata with a server-bound sender', async () => {
    const { connect } = await fixture()
    const room = randomUUID()
    const a = await connect(room), b = await connect(room)
    expect(a.first.type).toBe('welcome'); expect(b.first.type).toBe('welcome')
    if (a.first.type !== 'welcome' || b.first.type !== 'welcome') throw new Error('Missing welcome')
    expect(b.first.peers).toEqual([a.first.self])
    const payload = { description: { type: 'offer', sdp: 'v=0\r\n' } }
    a.socket.send(JSON.stringify({ v: 1, type: 'signal', to: b.first.self, payload }))
    await expect.poll(() => b.messages.find(message => message.type === 'signal')).toEqual({ v: 1, type: 'signal', from: a.first.self, payload })
    a.socket.close()
    await expect.poll(() => b.messages.find(message => message.type === 'peer-left')).toEqual({ v: 1, type: 'peer-left', peer: a.first.self })
  })

  it('rejects board content and room switching, and never forwards across rooms', async () => {
    const { connect } = await fixture()
    const a = await connect(randomUUID()), b = await connect(randomUUID())
    if (b.first.type !== 'welcome') throw new Error('Missing welcome')
    a.socket.send(JSON.stringify({ v: 1, type: 'signal', to: b.first.self, payload: { description: { type: 'offer', sdp: 'v=0\r\n' } } }))
    // The next message is processed after the cross-room signal on the same socket.
    a.socket.send(JSON.stringify({ v: 1, type: 'put', element: { secret: 'board-content' } }))
    await expect.poll(() => a.messages.find(message => message.type === 'error')).toEqual({ v: 1, type: 'error', code: 'invalid-message' })
    expect(b.messages.some(message => message.type === 'signal')).toBe(false)
    b.socket.send(JSON.stringify({ v: 1, type: 'join', room: randomUUID() }))
    await expect.poll(() => b.messages.find(message => message.type === 'error')).toBeTruthy()
  })

  it('enforces eight participants and drops room membership after the last one leaves', async () => {
    const { connect } = await fixture()
    const room = randomUUID()
    const peers = []
    for (let i = 0; i < 8; i++) peers.push(await connect(room))
    const ninth = await connect(room)
    expect(ninth.first).toEqual({ v: 1, type: 'error', code: 'room-full' })
    await Promise.all(peers.map(peer => new Promise<void>(resolve => { peer.socket.on('close', resolve); peer.socket.close() })))
    const fresh = await connect(room)
    expect(fresh.first.type).toBe('welcome')
    if (fresh.first.type === 'welcome') expect(fresh.first.peers).toEqual([])
  })

  it('serves uncached connection settings and requires TURN outside local development', async () => {
    const { base } = await fixture()
    const response = await fetch(`${base}/api/rtc-config`)
    expect(response.headers.get('cache-control')).toBe('no-store')
    expect(rtcConfigSchema.parse(await response.json()).iceServers[0].urls[0]).toContain('turn:')
    expect(() => rtcConfiguration({})).toThrow('Configure TURN')
    expect(rtcConfiguration({ MESHBOARD_TURN_URLS: 'turn:relay.example:3478', MESHBOARD_TURN_USERNAME: 'user', MESHBOARD_TURN_CREDENTIAL: 'test', MESHBOARD_RELAY_ONLY: 'true' }).iceTransportPolicy).toBe('relay')
  })
})
