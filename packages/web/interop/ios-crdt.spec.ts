import { historyInterop } from './history'
import { selectionInterop } from './selection'
import { pressureInterop } from './pressure'
import { arrowsInterop } from './arrows'
import { textElementsInterop } from './text-elements'
import { expect, type Page } from '@playwright/test'
import { test as base } from './server-fixtures'
import { iosPeer, nativePeer } from './peers'
import type { UpstreamFixture } from './y-webrtc-peer'

declare global { interface Window { upstream: UpstreamFixture } }

const test = base.extend<{ ios: Awaited<ReturnType<typeof iosPeer>>; relay: boolean }>({
  relay: [false, { option: true }],
  ios: [async ({ relay }, use) => {
    const peer = await iosPeer(relay)
    try { await use(peer) } finally { await peer.close() }
  }, { timeout: 120_000 }],
})
const elements = (page: Page) => page.getByTestId('drawing-elements').locator(':scope > *')
const stroke = (id: string, count = 2) => ({ id, type: 'pen' as const, color: '#387c59', width: 3, points: Array.from({ length: count }, (_, i) => ({ x: 500 + i / count * 100, y: 280 + i / count * 80 })) })
async function draw(page: Page) {
  await page.mouse.move(350, 300); await page.mouse.down()
  await page.mouse.move(450, 360, { steps: 8 }); await page.mouse.up()
}
async function invite(page: Page) {
  await page.goto('/')
  await page.getByRole('button', { name: 'Share board', exact: true }).click()
  const link = await page.getByLabel('Board invite', { exact: true }).inputValue()
  await page.getByRole('button', { name: 'Close sharing' }).click()
  expect(link).toContain('#crdt=')
  return link
}

test('web creates; iOS exchanges CRDT drawings and awareness, erases and retries', async ({ page, ios }) => {
  const link = await invite(page)
  await draw(page)
  ios.send({ type: 'join', invite: link })
  await expect.poll(() => ios.state().elements.length).toBe(1)
  ios.send({ type: 'preview', element: stroke('ios') })
  await expect(page.getByTestId('remote-drafts').locator(':scope > *')).toHaveCount(1)
  ios.send({ type: 'put', element: stroke('ios') })
  ios.send({ type: 'preview', element: null })
  await expect(elements(page)).toHaveCount(2)
  await expect(page.getByTestId('remote-drafts').locator(':scope > *')).toHaveCount(0)
  // Browser awareness flows in the other direction while a gesture is unfinished.
  await page.mouse.move(250, 250); await page.mouse.down(); await page.mouse.move(275, 275, { steps: 4 })
  await expect.poll(() => ios.state().previews.length).toBe(1)
  await page.mouse.up()
  await expect.poll(() => ios.state().elements.length).toBe(3)
  ios.send({ type: 'remove', ids: ['ios'] })
  await expect(elements(page)).toHaveCount(2)
  ios.send({ type: 'retry' })
  await expect.poll(() => ios.state().connected).toBe(1)
  await draw(page)
  await expect.poll(() => ios.state().elements.length).toBe(3)
  await page.keyboard.press('e'); await page.mouse.click(350, 300)
  // Both overlapping strokes are erased; the separate awareness-test stroke remains.
  await expect.poll(() => ios.state().elements.length).toBe(1)
})

test('iOS creates; four peers converge on large state, survive creator departure and recover reloads', async ({ page, browser, ios }, testInfo) => {
  const desktop = nativePeer()
  const context = await browser.newContext()
  let fourth: Page | undefined
  try {
    ios.send({ type: 'put', element: stroke('large', 12000) })
    ios.send({ type: 'share', origin: 'http://127.0.0.1:5174' })
    await expect.poll(() => ios.state().signaling).toBe(true)
    const link = ios.state().invite
    await page.goto(link)
    await expect(elements(page)).toHaveCount(1)
    desktop.send({ type: 'join', invite: link })
    fourth = await context.newPage(); await fourth.goto(link)
    await expect.poll(() => [ios.state().connected, desktop.state().connected]).toEqual([3, 3])
    await expect.poll(() => desktop.state().elements).toEqual(ios.state().elements)
    for (const peer of [page, fourth]) {
      await expect(peer.getByTestId('connection-status')).toHaveText('3 peers connected')
      await expect(elements(peer)).toHaveCount(1)
    }
    await Promise.all([draw(page), draw(fourth)])
    ios.send({ type: 'put', element: stroke('ios') })
    desktop.send({ type: 'put', element: stroke('desktop') })
    await expect.poll(() => [ios, desktop].map(peer => ({
      ids: peer.state().elements.map(e => e.id).sort(),
      count: peer.state().elements.length,
    })), { message: 'Both native replicas must receive both native edits and both browser strokes' }).toEqual([
      { ids: expect.arrayContaining(['desktop', 'ios', 'large']), count: 5 },
      { ids: expect.arrayContaining(['desktop', 'ios', 'large']), count: 5 },
    ])
    await expect.poll(() => ios.state().elements).toEqual(desktop.state().elements)
    await expect(elements(page)).toHaveCount(5); await expect(elements(fourth)).toHaveCount(5)
    await expect.poll(() => elements(page).evaluateAll(nodes => nodes.map(node => node.outerHTML))).toEqual(await elements(fourth).evaluateAll(nodes => nodes.map(node => node.outerHTML)))
    ios.send({ type: 'leave' })
    await expect.poll(() => desktop.state().connected).toBe(2)
    desktop.send({ type: 'remove', ids: ['large'] })
    await expect(elements(fourth)).toHaveCount(4)
    await page.reload(); await expect(elements(page)).toHaveCount(4)
    ios.send({ type: 'join', invite: link })
    await expect.poll(() => ios.state().elements).toEqual(desktop.state().elements)
    desktop.send({ type: 'remove', ids: desktop.state().elements.map(element => element.id) })
    await expect.poll(() => [ios.state().elements.length, desktop.state().elements.length]).toEqual([0, 0])
    await expect(elements(page)).toHaveCount(0); await expect(elements(fourth)).toHaveCount(0)
  } catch (error) {
    const states = [ios, desktop].map(peer => {
      try { const { elements, previews, invite: _invite, ...state } = peer.state(true); return { ...state, ids: elements.map(e => e.id), previews: previews.length } }
      catch (stateError) { return { error: String(stateError) } }
    })
    const browsers = await Promise.allSettled([page, fourth].filter((peer): peer is Page => !!peer).map(peer => peer.evaluate(() => ({
      status: document.querySelector('[data-testid="connection-status"]')?.textContent,
      elements: document.querySelector('[data-testid="drawing-elements"]')?.childElementCount,
      error: document.querySelector('[role="alert"]')?.textContent,
    }))))
    await testInfo.attach('browser-states-before-cleanup', { body: JSON.stringify(browsers, null, 2), contentType: 'application/json' })
    await testInfo.attach('native-peer-states-before-cleanup', { body: JSON.stringify(states, null, 2), contentType: 'application/json' })
    await testInfo.attach('ios-connection-events', { body: JSON.stringify(ios.diagnostics(), null, 2), contentType: 'application/json' })
    throw error
  } finally { await desktop.close(); await context.close() }
})

const relayTest = test.extend({ relay: true })
relayTest('iOS CRDT updates use real TURN with direct candidates disabled', async ({ page, context, ios }) => {
  await context.route('**/api/rtc-config', async route => {
    const response = await route.fetch()
    await route.fulfill({ response, json: { ...await response.json(), iceTransportPolicy: 'relay' } })
  })
  ios.send({ type: 'join', invite: await invite(page) })
  await expect.poll(() => ios.state().relayed).toBe(1)
  await expect(page.getByTestId('connection-route')).toContainText('VIA RELAY')
  await draw(page); await expect.poll(() => ios.state().elements.length).toBe(1)
  ios.send({ type: 'put', element: stroke('relay', 12000) })
  await expect(elements(page)).toHaveCount(2)
})

test('iOS rejects plaintext peers before sharing document state', async ({ page, ios }) => {
  ios.send({ type: 'put', element: stroke('private-seed') })
  ios.send({ type: 'share', origin: 'http://127.0.0.1:5174' })
  await expect.poll(() => ios.state().signaling).toBe(true)
  const room = new URLSearchParams(new URL(ios.state().invite).hash.slice(1)).get('crdt')!
  await page.goto(`/interop/y-webrtc.html?room=${room}`)
  await page.waitForFunction(() => !!window.upstream)
  await expect.poll(() => ios.state(true).error).toMatch(/could not authenticate|could not connect/)
  expect(await page.evaluate(() => window.upstream.elements())).toEqual([])
  expect(ios.state(true).elements.map(e => e.id)).toEqual(['private-seed'])
  expect(ios.state(true).connected).toBe(0)
})

test('iOS cannot join with the wrong invitation secret', async ({ page, ios }) => {
  const link = await invite(page); await draw(page)
  const wrong = new URL(link), params = new URLSearchParams(wrong.hash.slice(1))
  params.set('key', 'A'.repeat(43)); wrong.hash = params.toString()
  ios.send({ type: 'join', invite: wrong.href })
  await expect.poll(() => ios.state(true).error).not.toBeNull()
  expect(ios.state(true).elements).toHaveLength(0)
  expect(ios.state(true).connected).toBe(0)
  await expect(elements(page)).toHaveCount(1)
  ios.send({ type: 'join', invite: link })
  await expect.poll(() => ios.state(true).elements.length).toBe(1)
  expect(ios.state().connected).toBe(1)
})

test('iOS app refuses legacy invites and insecure origins without clearing the board', async ({ ios }) => {
  ios.send({ type: 'put', element: stroke('keep') })
  await expect.poll(() => ios.state().elements.length).toBe(1)
  ios.send({ type: 'join', invite: 'http://127.0.0.1:5174/#room=12345678-1234-4234-8234-123456789abc' })
  await expect.poll(() => ios.state(true).error).toContain('another protocol')
  expect(ios.state(true).elements.map(e => e.id)).toEqual(['keep'])
  ios.send({ type: 'share', origin: 'http://example.invalid' })
  await expect.poll(() => ios.state(true).error).toContain('HTTPS')
  expect(ios.state(true).invite).toBe('')
  expect(ios.state(true).elements.map(e => e.id)).toEqual(['keep'])
})

test('text and sticky notes synchronize edits and late joins across web and iOS', async ({ page, ios, browser }) => {
  await textElementsInterop(page, ios, browser)
})

test('arrows synchronize native edits, reloads and erasing', async ({ page, ios }) => {
  await arrowsInterop(page, ios)
})

test('pressure samples synchronize native edits and reloads', async ({ page, ios }) => {
  await pressureInterop(page, ios)
})

test('selection moves preserve remote edits and skip deleted objects', async ({ page, ios }) => {
  await selectionInterop(page, ios)
})

test('undo and redo preserve remote objects across encrypted peers', async ({ page, ios }) => {
  await historyInterop(page, ios)
})
