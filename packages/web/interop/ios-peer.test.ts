import { PassThrough } from 'node:stream'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { iosPeer } from './peers'

type Callback = (error: Error | null, stdout: string, stderr: string) => void
const { run, connect } = vi.hoisted(() => ({
  run: vi.fn<(file: string, args: string[], options: unknown, callback: Callback) => void>(),
  connect: vi.fn(),
}))
vi.mock('node:child_process', async importOriginal => ({
  ...await importOriginal<typeof import('node:child_process')>(), execFile: run,
}))
vi.mock('node:net', async importOriginal => ({
  ...await importOriginal<typeof import('node:net')>(), createConnection: connect,
}))

const state = { connected: 0, relayed: 0, invite: '', signaling: false, error: null, elements: [], previews: [] }
let socket: PassThrough

beforeEach(() => {
  vi.useFakeTimers()
  vi.stubEnv('MESHBOARD_IOS_SIMULATOR', 'test-ipad')
  run.mockReset().mockImplementation((_file, _args, _options, callback) => {
    queueMicrotask(() => callback(null, '', ''))
  })
  socket = new PassThrough()
  connect.mockReset().mockImplementation(() => {
    setTimeout(() => socket.write(`MESHBOARD ${JSON.stringify(state)}\n`), 1)
    return socket
  })
})
afterEach(() => {
  socket.destroy()
  vi.useRealTimers()
  vi.unstubAllEnvs()
})

async function start(relay = false) {
  const peer = iosPeer(relay)
  await vi.advanceTimersByTimeAsync(300)
  return peer
}

it('starts asynchronously with bounded simulator commands and preserves relay mode', async () => {
  const peer = await start(true)
  expect(peer.state()).toEqual(state)
  expect(run).toHaveBeenNthCalledWith(2, 'xcrun', [
    'simctl', 'launch', 'test-ipad', 'dev.meshboard.ios', '--meshboard-interop', '--relay',
  ], expect.objectContaining({ timeout: 30_000, killSignal: 'SIGKILL' }), expect.any(Function))
  await peer.close()
  expect(socket.destroyed).toBe(true)
})

it('awaits slow shutdown without blocking the event loop', async () => {
  const peer = await start()
  let complete!: Callback
  run.mockImplementationOnce((_file, _args, _options, callback) => { complete = callback })
  let closed = false
  const closing = peer.close().then(() => { closed = true })
  await vi.advanceTimersByTimeAsync(10_000)
  expect(closed).toBe(false)
  complete(null, '', '')
  await closing
  expect(closed).toBe(true)
  expect(socket.destroyed).toBe(true)
})

it('reports shutdown failure and still releases the socket', async () => {
  const peer = await start()
  run.mockImplementationOnce((_file, _args, _options, callback) => callback(new Error('simctl timed out'), '', ''))
  await expect(peer.close()).rejects.toThrow('simctl timed out')
  expect(socket.destroyed).toBe(true)
})

it('cleans up after launch failure and preserves the original error', async () => {
  run.mockImplementationOnce((_file, _args, _options, callback) => callback(null, '', ''))
    .mockImplementationOnce((_file, _args, _options, callback) => callback(new Error('launch failed'), '', ''))
    .mockImplementationOnce((_file, _args, _options, callback) => callback(new Error('cleanup failed'), '', ''))
  await expect(iosPeer()).rejects.toThrow('launch failed')
  expect(run.mock.calls.map(call => call[1][1])).toEqual(['terminate', 'launch', 'terminate'])
  expect(connect).not.toHaveBeenCalled()
})

it('cleans up the connection and app if the debug adapter never becomes ready', async () => {
  connect.mockImplementation(() => socket)
  const rejected = expect(iosPeer()).rejects.toThrow('iOS debug adapter did not start')
  await vi.advanceTimersByTimeAsync(21_000)
  await rejected
  expect(socket.destroyed).toBe(true)
  expect(run.mock.calls.map(call => call[1][1])).toEqual(['terminate', 'launch', 'terminate'])
})
