import { expect, test, type Page } from '@playwright/test'
import type { BoardElement } from '@meshboard/shared-protocol'
import { androidPeer, nativePeer } from './peers'

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

test('web creates; Android joins, exchanges previews and drawings, erases and rejoins', async ({ page }) => {
  const android = await androidPeer()
  try {
    const invite = await inviteFromWeb(page)
    await draw(page)
    android.send({ type: 'join', invite })
    await expect.poll(() => android.state().connected, { timeout: 30_000 }).toBe(1)
    await expect.poll(() => android.state().elements.length).toBe(1)
    android.send({ type: 'preview', element: stroke('android-stroke') })
    await expect(page.getByTestId('remote-drafts').locator(':scope > *')).toHaveCount(1)
    android.send({ type: 'put', element: stroke('android-stroke') })
    android.send({ type: 'preview', element: null })
    await expect(elements(page)).toHaveCount(2)
    await expect(page.getByTestId('remote-drafts').locator(':scope > *')).toHaveCount(0)
    android.send({ type: 'remove', ids: ['android-stroke'] })
    await expect(elements(page)).toHaveCount(1)
    android.send({ type: 'leave' })
    await expect.poll(() => android.state().invite).toBe('')
    android.send({ type: 'join', invite })
    await expect.poll(() => android.state().elements.length, { timeout: 30_000 }).toBe(1)
    await page.keyboard.press('e'); await page.mouse.click(350, 300)
    await expect.poll(() => android.state().elements.length).toBe(0)
  } finally { await android.close() }
})

test('Android creates; web and desktop recover large snapshots and keep drawing after creator leaves', async ({ page }) => {
  const android = await androidPeer()
  const desktop = nativePeer()
  try {
    android.send({ type: 'put', element: stroke('large-android-stroke', 12000) })
    android.send({ type: 'share', origin: 'http://127.0.0.1:5173' })
    await expect.poll(() => android.state().signaling, { timeout: 30_000 }).toBe(true)
    const invite = android.state().invite
    await page.goto(invite)
    await expect(elements(page)).toHaveCount(1, { timeout: 30_000 })
    desktop.send({ type: 'join', invite })
    await expect.poll(() => desktop.state().connected, { timeout: 30_000 }).toBe(2)
    await expect.poll(() => android.state().connected).toBe(2)
    await expect.poll(() => desktop.state().elements[0]?.points.length).toBe(12000)
    android.send({ type: 'leave' })
    await expect(page.getByTestId('connection-status')).toHaveText('1 peer connected')
    desktop.send({ type: 'put', element: stroke('desktop-stroke') })
    await expect(elements(page)).toHaveCount(2)
    await draw(page)
    await expect.poll(() => desktop.state().elements.length).toBe(3)
    android.send({ type: 'join', invite })
    await expect.poll(() => android.state().elements.length, { timeout: 30_000 }).toBe(3)
    expect(android.state().elements.find(e => e.id === 'large-android-stroke')?.points.length).toBe(12000)
  } finally { await desktop.close(); await android.close() }
})

test('Android and web draw through real TURN with direct candidates disabled', async ({ page, context }) => {
  const android = await androidPeer(true)
  await context.route('**/api/rtc-config', async route => {
    const response = await route.fetch()
    await route.fulfill({ response, json: { ...await response.json(), iceTransportPolicy: 'relay' } })
  })
  try {
    android.send({ type: 'join', invite: await inviteFromWeb(page) })
    await expect.poll(() => android.state().connected, { timeout: 30_000 }).toBe(1)
    await expect(page.getByTestId('connection-route')).toContainText('VIA RELAY')
    await expect.poll(() => android.state().relayed).toBe(1)
    await draw(page)
    await expect.poll(() => android.state().elements.length).toBe(1)
    android.send({ type: 'put', element: stroke('relayed-android-stroke') })
    await expect(elements(page)).toHaveCount(2)
  } finally { await android.close() }
})
