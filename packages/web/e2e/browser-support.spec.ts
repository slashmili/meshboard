import { expect, test } from '@playwright/test'
import { drawPressure } from '../interop/pressure'

for (const missing of ['isSecureContext', 'RTCPeerConnection', 'crypto', 'createImageBitmap', 'TextEncoder', 'URLSearchParams.size']) {
  test(`explains missing ${missing} before initializing an invite`, async ({ page }) => {
    const requests: string[] = []
    const sockets: string[] = []
    const errors: string[] = []
    page.on('websocket', socket => sockets.push(socket.url()))
    page.on('pageerror', error => errors.push(error.message))
    page.on('request', request => requests.push(new URL(request.url()).pathname))
    await page.addInitScript(key => {
      if (key === 'URLSearchParams.size') {
        Object.defineProperty(URLSearchParams.prototype, 'size', { configurable: true, value: undefined })
      } else {
        Object.defineProperty(window, key, { configurable: true, value: undefined })
      }
    }, missing)
    await page.goto('/#crdt=123e4567-e89b-42d3-a456-426614174000&key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA')
    await expect(page.getByRole('heading', { name: 'Meshboard could not open' })).toBeVisible()
    await expect(page.getByRole('alert')).toContainText(missing === 'isSecureContext' ? 'HTTPS' : 'supported browser')
    await expect(page.getByTestId('drawing-elements')).toHaveCount(0)
    expect(requests).not.toContain('/api/rtc-config')
    expect(sockets).toEqual([])
    expect(errors).toEqual([])
  })
}

test('a capable browser opens the drawing board', async ({ page }) => {
  await page.goto('/')
  await expect(page.getByRole('img', { name: 'Drawing canvas' })).toBeVisible()
  await expect(page.getByRole('alert')).toHaveCount(0)
})

// The capability gate loads App after the document, so raw input must wait for it.
test('first pen input waits for a delayed board module', async ({ page }) => {
  let releaseModule!: () => void
  const moduleHeld = new Promise<void>(resolve => { releaseModule = resolve })
  let markRequested!: () => void
  const moduleRequested = new Promise<void>(resolve => { markRequested = resolve })
  await page.route(/\/(?:src\/App\.tsx|assets\/App-[^/]+\.js)(?:\?.*)?$/, async route => {
    markRequested()
    await moduleHeld
    await route.continue()
  })
  await page.goto('/')
  await moduleRequested
  await expect(page.getByRole('img', { name: 'Drawing canvas' })).toHaveCount(0)
  const drawing = drawPressure(page)
  releaseModule()
  await drawing
  await expect(page.getByTestId('drawing-elements').locator('[data-element-type="pressure-pen"]')).toHaveCount(1)
})
