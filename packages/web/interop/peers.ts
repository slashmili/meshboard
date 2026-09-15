import { spawn, execFileSync } from 'node:child_process'
import { readFileSync } from 'node:fs'
import { createInterface } from 'node:readline'
import { createConnection, type Socket } from 'node:net'
import { fileURLToPath } from 'node:url'
import { join } from 'node:path'
import type { BoardElement } from '@meshboard/shared-protocol'

type NativeState = { connected: number; relayed: number; invite: string; signaling: boolean; error: string | null; elements: BoardElement[]; previews: BoardElement[] }
export function nativePeer(relay = false) {
  const classpath = readFileSync(new URL('../../native/build/interop/classpath.txt', import.meta.url), 'utf8')
  const child = spawn('java', ['-cp', classpath, 'meshboard.InteropPeerKt', ...(relay ? ['--relay'] : [])])
  let state: NativeState = { connected: 0, relayed: 0, invite: '', signaling: false, error: null, elements: [], previews: [] }
  let diagnostics = ''
  child.stderr.on('data', data => { diagnostics = (diagnostics + data).slice(-8000) })
  child.on('error', error => { diagnostics += error.message })
  const lines = createInterface({ input: child.stdout })
  lines.on('line', line => { if (line.startsWith('MESHBOARD ')) state = JSON.parse(line.slice(10)) })
  const send = (message: unknown) => { if (child.exitCode !== null) throw new Error(`Native peer exited: ${diagnostics}`); child.stdin.write(JSON.stringify(message) + '\n') }
  return {
    send,
    state: () => { if (child.exitCode !== null) throw new Error(`Native peer exited: ${diagnostics}`); if (state.error) throw new Error(`Native peer: ${state.error}\n${diagnostics}`); return state },
    async close() {
      if (child.exitCode !== null) { lines.close(); return }
      send({ type: 'close' })
      await new Promise<void>(resolve => {
        const timer = setTimeout(() => { child.kill('SIGKILL'); resolve() }, 12_000)
        child.once('exit', () => { clearTimeout(timer); resolve() })
      })
      lines.close()
    },
  }
}

export async function androidPeer(relay = false) {
  const root = fileURLToPath(new URL('../../../', import.meta.url))
  const adb = join(process.env.ANDROID_HOME || join(root, '.android-sdk'), 'platform-tools', process.platform === 'win32' ? 'adb.exe' : 'adb')
  const serial = process.env.ANDROID_SERIAL || 'emulator-5554'
  const run = (args: string[]) => execFileSync(adb, ['-s', serial, ...args], { encoding: 'utf8', timeout: 15_000 })
  const port = Number(run(['forward', 'tcp:0', 'tcp:18765']).trim())
  const child = spawn(adb, ['-s', serial, 'shell', 'am', 'instrument', '-w', '-e', 'class', 'dev.meshboard.android.InteropTest', '-e', 'meshboard.interop', 'true', '-e', 'meshboard.relay', String(relay), 'dev.meshboard.android.test/androidx.test.runner.AndroidJUnitRunner'])
  let diagnostics = ''
  let spawnError: Error | undefined
  child.stdout.on('data', data => { diagnostics += data })
  child.stderr.on('data', data => { diagnostics += data })
  child.on('error', error => { spawnError = error })
  let socket: Socket | undefined
  let state: NativeState | undefined
  let socketError = ''
  async function close() {
    if (socket && !socket.destroyed) { socket.write('{"type":"close"}\n'); socket.end() }
    if (child.exitCode === null && !spawnError) await new Promise<void>(resolve => {
      const timer = setTimeout(() => { child.kill(); resolve() }, 10_000)
      child.once('exit', () => { clearTimeout(timer); resolve() })
    })
    socket?.destroy()
    run(['forward', '--remove', `tcp:${port}`])
  }
  try {
    const deadline = Date.now() + 25_000
    while (Date.now() < deadline && !state) {
      if (spawnError || child.exitCode !== null) throw new Error(`Android instrumentation exited: ${spawnError ?? ''}\n${diagnostics}`)
      // adb accepts a forwarded socket before the device listener is ready, then closes it.
      socket = createConnection({ host: '127.0.0.1', port })
      socket.on('error', error => { socketError = error.message })
      const lines = createInterface({ input: socket })
      lines.on('line', line => { if (line.startsWith('MESHBOARD ')) state = JSON.parse(line.slice(10)) })
      await new Promise(resolve => setTimeout(resolve, 500))
      if (!state) { lines.close(); socket.destroy() }
    }
    if (!state) throw new Error(`Android harness did not start: ${socketError}\n${diagnostics}`)
    return {
      send(message: unknown) { if (!socket || socket.destroyed) throw new Error(`Android disconnected: ${diagnostics}`); socket.write(JSON.stringify(message) + '\n') },
      state() { if (!state || socket?.destroyed || child.exitCode !== null) throw new Error(`Android disconnected: ${diagnostics}`); if (state.error) throw new Error(`Android peer: ${state.error}`); return state },
      async close() { await close(); if (!/OK \(1 test\)/.test(diagnostics)) throw new Error(`Android instrumentation failed: ${diagnostics}`) },
    }
  } catch (error) { await close(); throw error }
}
