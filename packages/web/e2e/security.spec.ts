import { expect, test, type BrowserContext, type Page } from '@playwright/test'

declare global {
  interface Window { securityProbe: { frames: number[][]; mode: 'none' | 'tamper' | 'replay'; last: number[] | null; blocked: boolean } }
}
async function probe(context: BrowserContext) {
  await context.addInitScript(() => {
    window.securityProbe = { frames: [], mode: 'none', last: null, blocked: false }
    const original = RTCDataChannel.prototype.send as (this: RTCDataChannel, data: unknown) => void
    RTCDataChannel.prototype.send = function(data: unknown) {
      const bytes = data instanceof ArrayBuffer ? new Uint8Array(data) : ArrayBuffer.isView(data) ? new Uint8Array(data.buffer, data.byteOffset, data.byteLength) : null
      if (!bytes) throw new Error('Non-binary application frame')
      const probe = window.securityProbe
      if (probe.blocked) return
      if (probe.frames.length < 256) probe.frames.push(Array.from(bytes))
      let outgoing = bytes
      if (bytes[2] === 69) {
        if (probe.mode === 'tamper') { outgoing = bytes.slice(); outgoing[outgoing.length - 1] ^= 1; probe.mode = 'none'; probe.blocked = true }
        else if (probe.mode === 'replay' && probe.last) { outgoing = new Uint8Array(probe.last); probe.mode = 'none'; probe.blocked = true }
        probe.last = Array.from(bytes)
      }
      original.call(this, outgoing)
    }
  })
}
async function draw(page: Page) {
  await page.mouse.move(350, 300); await page.mouse.down(); await page.mouse.move(460, 390, { steps: 8 }); await page.mouse.up()
}
async function share(page: Page) {
  await page.goto('/')
  await page.getByRole('button', { name: 'Share board', exact: true }).click()
  const link = await page.getByLabel('Board invite', { exact: true }).inputValue()
  await page.getByRole('button', { name: 'Close sharing' }).click()
  return link
}
const elements = (page: Page) => page.getByTestId('drawing-elements').locator(':scope > *')

test('all application frames are encrypted and signaling never receives the invitation secret', async ({ browser }) => {
  const a = await browser.newContext(), b = await browser.newContext()
  await probe(a); await probe(b)
  const signals: string[] = []
  try {
    const creator = await a.newPage(), joiner = await b.newPage()
    for (const page of [creator, joiner]) page.on('websocket', socket => {
      socket.on('framesent', frame => signals.push(String(frame.payload)))
      socket.on('framereceived', frame => signals.push(String(frame.payload)))
    })
    const invite = await share(creator)
    const key = new URLSearchParams(new URL(invite).hash.slice(1)).get('key')!
    expect(key).toMatch(/^[A-Za-z0-9_-]{43}$/)
    await draw(creator); await joiner.goto(invite)
    await expect(elements(joiner)).toHaveCount(1)
    await draw(joiner); await expect(elements(creator)).toHaveCount(2)
    const frames = [...await creator.evaluate(() => window.securityProbe.frames), ...await joiner.evaluate(() => window.securityProbe.frames)]
    expect(frames.length).toBeGreaterThan(8)
    const rawKey = Buffer.from(key, 'base64url')
    for (const frame of frames) {
      expect(frame.slice(0, 2)).toEqual([77, 66])
      expect([69, 72]).toContain(frame[2]); expect(frame[3]).toBe(1)
      if (frame[2] === 72) expect(frame).toHaveLength(36)
      else expect(frame.length).toBeGreaterThanOrEqual(29)
      const buffer = Buffer.from(frame)
      expect(buffer.includes(rawKey)).toBe(false)
      expect(buffer.toString()).not.toMatch(/"points"|"color"|"meshboard"/)
    }
    expect(signals.length).toBeGreaterThan(4)
    for (const signal of signals) {
      expect(signal).not.toContain(key)
      expect(signal).not.toMatch(/"points"|"color"|"preview"|"key"/)
    }
  } finally { await a.close(); await b.close() }
})
for (const mode of ['tamper', 'replay'] as const) test(`rejects ${mode} on an authenticated real data channel`, async ({ browser }) => {
  const a = await browser.newContext(), b = await browser.newContext(); await probe(b)
  try {
    const creator = await a.newPage(), attacker = await b.newPage()
    const invite = await share(creator); await draw(creator); await attacker.goto(invite)
    await expect(elements(attacker)).toHaveCount(1)
    await expect(attacker.getByTestId('connection-status')).toHaveText('1 peer connected')
    await attacker.evaluate(mode => { window.securityProbe.mode = mode }, mode)
    // Send exactly one bad record. A legitimate key holder can otherwise
    // reconnect and synchronize its clean local edits after rejection.
    await draw(attacker)
    await expect(creator.getByRole('alert')).toContainText('invalid encrypted')
    await expect(elements(creator)).toHaveCount(1)
  } finally { await a.close(); await b.close() }
})
test('a missing invitation secret cannot start a session', async ({ page }) => {
  await page.goto('/#crdt=12345678-1234-4234-8234-123456789abc')
  await expect(page.getByRole('alert')).toContainText('invalid')
  await expect(page.getByTestId('connection-status')).not.toHaveText('1 peer connected')
})
