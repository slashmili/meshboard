import { createServer } from 'node:http'
import type { AddressInfo } from 'node:net'
import { randomUUID } from 'node:crypto'
import { WebSocket } from 'ws'
import { afterEach, expect, it } from 'vitest'
import { attachYWebrtc } from './y-webrtc.ts'

const cleanups: (() => Promise<void>)[] = []
afterEach(async () => { for (const fn of cleanups.splice(0)) await fn() })
async function fixture(enabled = true) {
  const server = createServer(), close = attachYWebrtc(server, enabled)
  await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve))
  cleanups.push(() => { close(); return new Promise(resolve => server.close(() => resolve())) })
  const url = `ws://127.0.0.1:${(server.address() as AddressInfo).port}/signal-y-webrtc`
  async function connect(topic = `meshboard-crdt:${randomUUID()}`) {
    const socket = new WebSocket(url), messages: Record<string, any>[] = []
    socket.on('message', bytes => messages.push(JSON.parse(bytes.toString())))
    await new Promise<void>((resolve, reject) => { socket.on('open', resolve); socket.on('error', reject) })
    const send = (value: unknown) => socket.send(JSON.stringify(value))
    send({ type: 'subscribe', topics: [topic] }); send({ type: 'ping' })
    await expect.poll(() => messages.length).toBeGreaterThan(0)
    const id = randomUUID()
    const publish = (data: unknown) => send({ type: 'publish', topic, data })
    return { socket, messages, send, publish, id, topic }
  }
  return { url, connect }
}
it('supports upstream subscribe/announce/signal/ping and isolates topics', async () => {
  const { connect } = await fixture()
  const a = await connect(), b = await connect(a.topic), other = await connect()
  a.publish({ type: 'announce', from: a.id })
  await expect.poll(() => b.messages.some(m => m.data?.from === a.id)).toBe(true)
  const signal = { type: 'offer', sdp: 'v=0\r\n' }
  a.publish({ type: 'signal', from: a.id, to: b.id, token: 1234.5, signal })
  await expect.poll(() => b.messages.some(m => m.data?.signal?.sdp === signal.sdp)).toBe(true)
  expect(other.messages).toEqual([{ type: 'pong' }])
  a.send({ type: 'subscribe', topics: [a.topic] }) // upstream re-announces idempotently
  a.send({ type: 'ping' })
  await expect.poll(() => a.messages.filter(m => m.type === 'pong').length).toBe(2)
})
it('rejects board payloads, sender switches, cross-topic publish and binary frames', async () => {
  const { connect } = await fixture()
  for (const kind of ['board', 'sender', 'topic', 'binary']) {
    const a = await connect(), b = await connect(a.topic)
    a.publish({ type: 'announce', from: a.id })
    await expect.poll(() => b.messages.length).toBe(2)
    if (kind === 'board') a.publish({ type: 'update', from: a.id, board: 'must-not-relay' })
    if (kind === 'sender') a.publish({ type: 'announce', from: b.id })
    if (kind === 'topic') a.send({ type: 'publish', topic: `meshboard-crdt:${randomUUID()}`, data: { type: 'announce', from: a.id } })
    if (kind === 'binary') a.socket.send(Buffer.from([1, 2, 3]))
    await expect.poll(() => a.messages.some(m => m.type === 'error')).toBe(true)
    expect(b.messages.length).toBe(2)
  }
})
it('caps rooms at eight and releases subscriptions after departure', async () => {
  const { connect } = await fixture()
  const topic = `meshboard-crdt:${randomUUID()}`, peers = []
  for (let i = 0; i < 8; i++) peers.push(await connect(topic))
  const ninth = await connect(topic)
  expect(ninth.messages[0]).toEqual({ type: 'error', code: 'room-full' })
  await Promise.all(peers.map(peer => new Promise<void>(resolve => { peer.socket.once('close', resolve); peer.socket.close() })))
  expect((await connect(topic)).messages[0]).toEqual({ type: 'pong' })
})
it('does not expose preview signaling in production', async () => {
  const { url } = await fixture(false)
  const socket = new WebSocket(url)
  await new Promise<void>((resolve, reject) => { socket.on('error', () => resolve()); socket.on('open', () => { socket.close(); reject(new Error('Exposed preview')) }) })
})
