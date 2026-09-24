import { test as base, expect, type Page } from '@playwright/test'
import { nativePeer } from './peers'
import type { UpstreamFixture } from './y-webrtc-peer'

declare global { interface Window { upstream: UpstreamFixture } }

const test = base.extend<{ native: ReturnType<typeof nativePeer>; relay: boolean }>({
  relay: [false, { option: true }],
  native: [async ({ relay }, use) => {
    const peer = nativePeer(relay)
    try { await use(peer) } finally { await peer.close() }
  }, { timeout: 30_000 }],
})
const elements = (page: Page) => page.getByTestId('drawing-elements').locator(':scope > *')
const stroke = (id: string, count = 2) => ({ id, type: 'pen' as const, color: '#387c59', width: 3, points: Array.from({ length: count }, (_, i) => ({ x: 500 + i / count * 100, y: 280 + i / count * 80 })) })
async function draw(page: Page) {
  await page.mouse.move(350, 300); await page.mouse.down()
  await page.mouse.move(450, 360, { steps: 8 }); await page.mouse.up()
}
async function invite(page: Page) {
  await page.goto('/?crdt=1')
  await page.getByRole('button', { name: 'Share board', exact: true }).click()
  const value = await page.getByLabel('Board invite', { exact: true }).inputValue()
  await page.getByRole('button', { name: 'Close sharing' }).click()
  expect(value).toContain('#crdt=')
  return value
}

test('web creates; native exchanges CRDT drawings and previews, erases and rejoins', async ({ page, native }) => {
  const link = await invite(page)
  await draw(page)
  native.send({ type: 'join', invite: link })
  await expect.poll(() => native.state().elements.length, { timeout: 30_000 }).toBe(1)
  native.send({ type: 'preview', element: stroke('native') })
  await expect(page.getByTestId('remote-drafts').locator(':scope > *')).toHaveCount(1)
  native.send({ type: 'put', element: stroke('native') })
  native.send({ type: 'preview', element: null })
  await expect(elements(page)).toHaveCount(2)
  native.send({ type: 'remove', ids: ['native'] })
  await expect(elements(page)).toHaveCount(1)
  native.send({ type: 'leave' })
  await expect.poll(() => native.state().invite).toBe('')
  native.send({ type: 'join', invite: link })
  await expect.poll(() => native.state().elements.length, { timeout: 30_000 }).toBe(1)
  await page.keyboard.press('e'); await page.mouse.click(350, 300)
  await expect.poll(() => native.state().elements.length).toBe(0)
})

test('four mixed peers converge, continue after creator departure, and restore a reloaded peer', async ({ page, browser, native }, testInfo) => {
  // Large snapshots, validation and simultaneous updates share the native
  // executor. The default five-second assertion budget is too short on CI.
  const meshExpect = expect.configure({ timeout: 30_000 })
  const secondNative = nativePeer()
  const context = await browser.newContext()
  let fourth: Page | undefined
  try {
    native.send({ type: 'put', element: stroke('large', 12000) })
    native.send({ type: 'share', origin: 'http://127.0.0.1:5174' })
    await expect.poll(() => native.state().signaling, { timeout: 30_000 }).toBe(true)
    const link = native.state().invite
    await page.goto(link)
    await expect(elements(page)).toHaveCount(1, { timeout: 30_000 })
    secondNative.send({ type: 'join', invite: link })
    fourth = await context.newPage()
    await fourth.goto(link)
    // An open channel is not proof that its initial snapshot has been applied.
    await meshExpect.poll(() => [native, secondNative].map(peer => {
      const state = peer.state()
      return { connected: state.connected, ids: state.elements.map(element => element.id) }
    })).toEqual([{ connected: 3, ids: ['large'] }, { connected: 3, ids: ['large'] }])
    for (const peer of [page, fourth]) {
      await meshExpect(peer.getByTestId('connection-status')).toHaveText('3 peers connected')
      await meshExpect(elements(peer)).toHaveCount(1)
    }
    await Promise.all([draw(page), draw(fourth)])
    secondNative.send({ type: 'put', element: stroke('second-native') })
    await meshExpect.poll(() => [native.state().elements.length, secondNative.state().elements.length]).toEqual([4, 4])
    await meshExpect.poll(() => secondNative.state().elements).toEqual(native.state().elements)
    await meshExpect(elements(page)).toHaveCount(4)
    await meshExpect(elements(fourth)).toHaveCount(4)
    await meshExpect.poll(() => elements(page).evaluateAll(nodes => nodes.map(node => node.outerHTML))).toEqual(await elements(fourth).evaluateAll(nodes => nodes.map(node => node.outerHTML)))
    native.send({ type: 'leave' })
    await meshExpect.poll(() => secondNative.state().connected).toBe(2)
    secondNative.send({ type: 'remove', ids: ['large'] })
    await meshExpect(elements(fourth)).toHaveCount(3)
    await page.reload()
    await expect(elements(page)).toHaveCount(3, { timeout: 30_000 })
    native.send({ type: 'join', invite: link })
    await expect.poll(() => native.state().elements.length, { timeout: 30_000 }).toBe(3)
    await meshExpect.poll(() => native.state().elements).toEqual(secondNative.state().elements)
    secondNative.send({ type: 'remove', ids: secondNative.state().elements.map(element => element.id) })
    await meshExpect(elements(page)).toHaveCount(0)
    await meshExpect(elements(fourth)).toHaveCount(0)
    await meshExpect.poll(() => native.state().elements.length).toBe(0)
  } catch (error) {
    // Capture before closing peers: cleanup changes connection counts and can
    // otherwise make a slow update look like a dropped peer in the screenshots.
    const nativeStates = [native, secondNative].map(peer => {
      try {
        const { elements, previews, ...state } = peer.state()
        return { ...state, elementIds: elements.map(element => element.id), previewIds: previews.map(element => element.id) }
      } catch (stateError) { return { error: String(stateError) } }
    })
    const browserStates = await Promise.allSettled([page, fourth].filter((peer): peer is Page => !!peer).map(peer => peer.evaluate(() => ({
      url: location.href,
      status: document.querySelector('[data-testid="connection-status"]')?.textContent,
      elements: document.querySelector('[data-testid="drawing-elements"]')?.childElementCount,
      error: document.querySelector('[role="alert"]')?.textContent,
    }))))
    await testInfo.attach('four-peer-state', { body: JSON.stringify({ nativeStates, browserStates }, null, 2), contentType: 'application/json' })
    throw error
  } finally { await secondNative.close(); await context.close() }
})

const relayTest = test.extend({ relay: true })
relayTest('CRDT updates use real TURN when direct candidates are disabled', async ({ page, context, native }) => {
  await context.route('**/api/rtc-config', async route => {
    const response = await route.fetch()
    await route.fulfill({ response, json: { ...await response.json(), iceTransportPolicy: 'relay' } })
  })
  const link = await invite(page)
  native.send({ type: 'join', invite: link })
  await expect.poll(() => native.state().connected, { timeout: 30_000 }).toBe(1)
  await expect.poll(() => native.state().relayed).toBe(1)
  await expect(page.getByTestId('connection-route')).toContainText('VIA RELAY')
  await draw(page)
  await expect.poll(() => native.state().elements.length).toBe(1)
  native.send({ type: 'put', element: stroke('relay') })
  await expect(elements(page)).toHaveCount(2)
})

test('legacy links are refused by preview mode and vice versa', async ({ page }) => {
  const id = '12345678-1234-4234-8234-123456789abc'
  await page.goto(`/?crdt=1#room=${id}`)
  await expect(page.getByRole('alert')).toContainText('another protocol')
  await page.goto(`/#crdt=${id}`)
  await expect(page.getByRole('alert')).toContainText('another protocol')
})

for (const creator of ['web', 'desktop']) test(`unmodified upstream y-webrtc exchanges raw sync, awareness and deletes (${creator} creates)`, async ({ page, native }) => {
  let room = crypto.randomUUID() as string
  if (creator === 'desktop') {
    // Larger than a fragment, smaller than standard SCTP limits. A channel label
    // selected by native must NOT force fragments on an unmodified provider.
    native.send({ type: 'put', element: stroke('raw-seed', 1000) })
    native.send({ type: 'share', origin: 'http://127.0.0.1:5174' })
    await expect.poll(() => native.state().signaling, { timeout: 30_000 }).toBe(true)
    room = new URLSearchParams(new URL(native.state().invite).hash.slice(1)).get('crdt')!
  }
  await page.goto(`/interop/y-webrtc.html?room=${room}`)
  await page.waitForFunction(() => !!window.upstream)
  if (creator === 'web') native.send({ type: 'join', invite: `http://127.0.0.1:5174/?crdt=1#crdt=${room}` })
  await expect.poll(() => page.evaluate(() => window.upstream.peers()), { timeout: 30_000 }).toBe(1)
  if (creator === 'desktop') {
    await expect.poll(() => page.evaluate(() => window.upstream.elements().length)).toBe(1)
    native.send({ type: 'remove', ids: ['raw-seed'] })
    await expect.poll(() => page.evaluate(() => window.upstream.elements().length)).toBe(0)
  }
  await page.evaluate(element => window.upstream.put(element), stroke('upstream'))
  await expect.poll(() => native.state().elements.length).toBe(1)
  native.send({ type: 'put', element: stroke('native') })
  await expect.poll(() => page.evaluate(() => window.upstream.elements().length)).toBe(2)
  await page.evaluate(element => window.upstream.preview(element), stroke('draft'))
  await expect.poll(() => native.state().previews.length).toBe(1)
  await page.evaluate(() => window.upstream.preview(null))
  await expect.poll(() => native.state().previews.length).toBe(0)
  native.send({ type: 'preview', element: stroke('desktop-draft') })
  await expect.poll(() => page.evaluate(() => window.upstream.drafts().length)).toBe(1)
  native.send({ type: 'preview', element: null })
  await expect.poll(() => page.evaluate(() => window.upstream.drafts().length)).toBe(0)
  await page.evaluate(() => window.upstream.remove('native'))
  await expect.poll(() => native.state().elements.map(e => e.id)).toEqual(['upstream'])
  native.send({ type: 'remove', ids: ['upstream'] })
  await expect.poll(() => page.evaluate(() => window.upstream.elements().length)).toBe(0)
})

test('rejects an invalid upstream update before it reaches the visible browser document', async ({ page, browser }) => {
  const link = await invite(page)
  await draw(page)
  const room = new URLSearchParams(new URL(link).hash.slice(1)).get('crdt')
  const context = await browser.newContext()
  try {
    const upstream = await context.newPage()
    await upstream.goto(new URL(`/interop/y-webrtc.html?room=${room}`, link).href)
    await upstream.waitForFunction(() => !!window.upstream)
    await expect.poll(() => upstream.evaluate(() => window.upstream.elements().length)).toBe(1)
    await upstream.evaluate(() => window.upstream.invalid())
    await expect(page.getByRole('alert')).toContainText('invalid or oversized')
    await expect(elements(page)).toHaveCount(1)
  } finally { await context.close() }
})
