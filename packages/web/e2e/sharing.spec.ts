import { expect, test, type Browser, type BrowserContext, type Page } from '@playwright/test'

const elements = (page: Page) => page.getByTestId('drawing-elements').locator(':scope > *')

async function drag(page: Page, x: number, y: number) {
  await page.mouse.move(x, y)
  await page.mouse.down()
  await page.mouse.move(x + 100, y + 60, { steps: 8 })
  await page.mouse.up()
}

async function createShared(page: Page) {
  await page.goto('/')
  await page.getByRole('button', { name: 'Share board', exact: true }).click()
  const invite = await page.getByLabel('Board invite', { exact: true }).inputValue()
  await expect(page.getByRole('img', { name: 'QR code for this board’s invite link' })).toBeVisible()
  await page.getByRole('button', { name: 'Close sharing' }).click()
  return invite
}

async function newPeer(browser: Browser, url: string, relay = false) {
  const context = await browser.newContext()
  if (relay) await forceRelay(context)
  const page = await context.newPage()
  await page.goto(url)
  return { context, page }
}

async function forceRelay(context: BrowserContext) {
  // Use the real local TURN server; only the browser policy is forced here.
  await context.route('**/api/rtc-config', async route => {
    const response = await route.fetch()
    const config = await response.json()
    await route.fulfill({ response, json: { ...config, iceTransportPolicy: 'relay' } })
  })
}

test('two separate browsers share strokes, previews, erasing, and clear without board traffic on signaling', async ({ page, browser }, testInfo) => {
  const errors: string[] = []
  const signals: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  page.on('websocket', socket => { if (socket.url().endsWith('/signal')) socket.on('framesent', frame => signals.push(String(frame.payload))) })
  const invite = await createShared(page)
  await page.getByRole('button', { name: 'Share board', exact: true }).click()
  await page.screenshot({ path: testInfo.outputPath('share-dialog.png') })
  await page.getByRole('button', { name: 'Close sharing' }).click()
  await drag(page, 350, 300)
  const b = await newPeer(browser, invite)
  try {
    await expect(page.getByTestId('connection-status')).toHaveText('1 peer connected', { timeout: 25_000 })
    await expect(page.getByTestId('connection-route')).toContainText('DIRECT CONNECTION')
    await expect(elements(b.page)).toHaveCount(1)
    await b.page.mouse.move(650, 400)
    await b.page.mouse.down()
    await b.page.mouse.move(750, 460, { steps: 8 })
    await expect(page.getByTestId('remote-drafts').locator(':scope > *')).toHaveCount(1)
    await b.page.mouse.up()
    await expect(elements(page)).toHaveCount(2)
    await expect(page.getByTestId('remote-drafts').locator(':scope > *')).toHaveCount(0)
    await b.page.keyboard.press('e')
    await b.page.mouse.click(350, 300)
    await expect(elements(page)).toHaveCount(1)
    await b.page.getByRole('button', { name: 'Clear board', exact: true }).click()
    await b.page.getByRole('dialog', { name: 'Clear this board?' }).getByRole('button', { name: 'Clear board', exact: true }).click()
    await expect(elements(page)).toHaveCount(0)
    expect(signals.length).toBeGreaterThan(1)
    expect(signals.map(signal => JSON.parse(signal).type).every(type => ['join', 'signal'].includes(type))).toBe(true)
    expect(signals.some(signal => /"points"|"color"|"element"|"snapshot"/.test(signal))).toBe(false)
    expect(errors).toEqual([])
  } finally { await b.context.close() }
})

test('creator can leave while other peers continue, refresh, and eventually end the session', async ({ page, browser }) => {
  const invite = await createShared(page)
  await drag(page, 350, 300)
  const b = await newPeer(browser, invite), c = await newPeer(browser, invite)
  try {
    await expect(b.page.getByTestId('connection-status')).toHaveText('2 peers connected', { timeout: 25_000 })
    await expect(elements(c.page)).toHaveCount(1)
    await page.close()
    await expect(b.page.getByTestId('connection-status')).toHaveText('1 peer connected')
    await drag(b.page, 650, 400)
    await expect(elements(c.page)).toHaveCount(2)
    await c.page.reload()
    await expect(elements(c.page)).toHaveCount(2, { timeout: 25_000 })
  } finally { await b.context.close(); await c.context.close() }
  const fresh = await newPeer(browser, invite)
  try {
    await expect(fresh.page.getByTestId('connection-status')).toHaveText('Waiting for a peer')
    await expect(elements(fresh.page)).toHaveCount(0)
  } finally { await fresh.context.close() }
})

test('two peers draw through an actual TURN relay with direct candidates disabled', async ({ page, context, browser }) => {
  await forceRelay(context)
  const invite = await createShared(page)
  const b = await newPeer(browser, invite, true)
  try {
    await expect(page.getByTestId('connection-status')).toHaveText('1 peer connected', { timeout: 30_000 })
    await expect(page.getByTestId('connection-route')).toContainText('VIA RELAY')
    await expect(b.page.getByTestId('connection-route')).toContainText('VIA RELAY')
    await drag(page, 350, 300)
    await expect(elements(b.page)).toHaveCount(1)
    await drag(b.page, 650, 400)
    await expect(elements(page)).toHaveCount(2)
  } finally { await b.context.close() }
})

test('sharing preserves the local drawing and leaving discards only this copy', async ({ page, browser }) => {
  await page.goto('/')
  await drag(page, 350, 300)
  await page.getByRole('button', { name: 'Share board', exact: true }).click()
  const invite = await page.getByLabel('Board invite', { exact: true }).inputValue()
  const b = await newPeer(browser, invite)
  try {
    await expect(elements(b.page)).toHaveCount(1, { timeout: 25_000 })
    await page.getByRole('button', { name: 'Leave this board', exact: true }).click()
    await page.getByRole('button', { name: 'Leave board', exact: true }).click()
    await expect(page.getByTestId('connection-status')).toHaveText('Local only')
    await expect(elements(page)).toHaveCount(0)
    await expect(elements(b.page)).toHaveCount(1)
  } finally { await b.context.close() }
})
