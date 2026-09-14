import { spawn } from 'node:child_process'
import { readFileSync } from 'node:fs'
import { createInterface } from 'node:readline'
import { expect, test, type Page } from '@playwright/test'
import type { BoardElement } from '@meshboard/shared-protocol'

type NativeState = { connected: number; relayed: number; invite: string; signaling: boolean; error: string | null; elements: BoardElement[]; previews: BoardElement[] }
function nativePeer(relay = false) {
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
const elements = (page: Page) => page.getByTestId('drawing-elements').locator(':scope > *')
const stroke = (id: string, points = 2): BoardElement => ({ id, type: 'pen', color: '#387c59', width: 3, points: Array.from({ length: points }, (_, i) => ({ x: 500 + i / points * 100, y: 280 + i / points * 80 })) })
async function draw(page: Page) {
  await page.mouse.move(350, 300); await page.mouse.down()
  await page.mouse.move(450, 360, { steps: 8 }); await page.mouse.up()
}
async function inviteFromWeb(page: Page) {
  await page.goto('/')
  await page.getByRole('button', { name: 'Share board', exact: true }).click()
  const invite = await page.getByLabel('Board invite', { exact: true }).inputValue()
  await page.getByRole('button', { name: 'Close sharing' }).click()
  return invite
}

test('web creates; native joins, exchanges previews and strokes, erases and rejoins', async ({ page }) => {
  const native = nativePeer()
  try {
    const invite = await inviteFromWeb(page)
    await draw(page)
    native.send({ type: 'join', invite })
    await expect.poll(() => native.state().connected, { timeout: 30_000 }).toBe(1)
    await expect.poll(() => native.state().elements.length).toBe(1)
    native.send({ type: 'preview', element: stroke('native-stroke') })
    await expect(page.getByTestId('remote-drafts').locator(':scope > *')).toHaveCount(1)
    native.send({ type: 'put', element: stroke('native-stroke') })
    native.send({ type: 'preview', element: null })
    await expect(elements(page)).toHaveCount(2)
    await expect(page.getByTestId('remote-drafts').locator(':scope > *')).toHaveCount(0)
    native.send({ type: 'remove', ids: ['native-stroke'] })
    await expect(elements(page)).toHaveCount(1)
    native.send({ type: 'leave' })
    await expect.poll(() => native.state().invite).toBe('')
    native.send({ type: 'join', invite })
    await expect.poll(() => native.state().elements.length, { timeout: 30_000 }).toBe(1)
    await page.keyboard.press('e'); await page.mouse.click(350, 300)
    await expect.poll(() => native.state().elements.length).toBe(0)
    expect(native.state().error).toBeNull()
  } finally { await native.close() }
})

test('native creates; large late-join snapshots and creator departure work across platforms', async ({ page, browser }) => {
  const native = nativePeer()
  const context = await browser.newContext()
  try {
    native.send({ type: 'put', element: stroke('large-native-stroke', 12000) })
    native.send({ type: 'share', origin: 'http://127.0.0.1:5173' })
    await expect.poll(() => native.state().signaling, { timeout: 30_000 }).toBe(true)
    await page.goto(native.state().invite)
    await expect(elements(page)).toHaveCount(1, { timeout: 30_000 })
    await draw(page)
    await expect.poll(() => native.state().elements.length).toBe(2)
    const third = await context.newPage()
    await third.goto(native.state().invite)
    await expect(third.getByTestId('connection-status')).toHaveText('2 peers connected', { timeout: 30_000 })
    await expect(elements(third)).toHaveCount(2)
    native.send({ type: 'leave' })
    await expect(page.getByTestId('connection-status')).toHaveText('1 peer connected')
    await draw(third)
    await expect(elements(page)).toHaveCount(3)
    await page.reload()
    await expect(elements(page)).toHaveCount(3, { timeout: 30_000 })
  } finally { await native.close(); await context.close() }
})

test('native and web draw through real TURN with direct candidates disabled', async ({ page, context }) => {
  const native = nativePeer(true)
  await context.route('**/api/rtc-config', async route => {
    const response = await route.fetch()
    await route.fulfill({ response, json: { ...await response.json(), iceTransportPolicy: 'relay' } })
  })
  try {
    const invite = await inviteFromWeb(page)
    native.send({ type: 'join', invite })
    await expect.poll(() => native.state().connected, { timeout: 30_000 }).toBe(1)
    await expect(page.getByTestId('connection-route')).toContainText('VIA RELAY')
    await expect.poll(() => native.state().relayed).toBe(1)
    await draw(page)
    await expect.poll(() => native.state().elements.length).toBe(1)
    native.send({ type: 'put', element: stroke('relayed-native-stroke') })
    await expect(elements(page)).toHaveCount(2)
    expect(native.state().error).toBeNull()
  } finally { await native.close() }
})
