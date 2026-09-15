import { iosPeer, nativePeer } from './peers'
import { expect, test, type Page } from '@playwright/test'
import type { BoardElement } from '@meshboard/shared-protocol'

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

test('web creates; iOS joins, exchanges previews and strokes, erases and rejoins', async ({ page }) => {
  const native = await iosPeer()
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

test('iOS creates; large late-join snapshots and creator departure work across platforms', async ({ page, browser }) => {
  const native = await iosPeer()
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

test('iOS and web draw through real TURN with direct candidates disabled', async ({ page, context }) => {
  const native = await iosPeer(true)
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


test('iOS, macOS and web mesh continues after the iOS creator leaves', async ({ page }) => {
  const ipad = await iosPeer()
  const mac = nativePeer()
  try {
    ipad.send({ type: 'share', origin: 'http://127.0.0.1:5173' })
    await expect.poll(() => ipad.state().signaling, { timeout: 30_000 }).toBe(true)
    const invite = ipad.state().invite
    mac.send({ type: 'join', invite })
    await page.goto(invite)
    await expect.poll(() => ipad.state().connected, { timeout: 30_000 }).toBe(2)
    await expect.poll(() => mac.state().connected, { timeout: 30_000 }).toBe(2)
    ipad.send({ type: 'put', element: stroke('ipad-mesh') })
    await expect.poll(() => mac.state().elements.length).toBe(1)
    await expect(elements(page)).toHaveCount(1)
    ipad.send({ type: 'leave' })
    await expect.poll(() => mac.state().connected).toBe(1)
    mac.send({ type: 'put', element: stroke('mac-after-creator') })
    await expect(elements(page)).toHaveCount(2)
    ipad.send({ type: 'join', invite })
    await expect.poll(() => ipad.state().elements.length, { timeout: 30_000 }).toBe(2)
  } finally { await ipad.close(); await mac.close() }
})
