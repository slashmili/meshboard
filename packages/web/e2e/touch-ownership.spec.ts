import { expect, test } from '@playwright/test'

test('canvas owns native touch defaults while controls retain their normal touch behavior', async ({ page }) => {
  await page.goto('/?input-debug=1')
  const canvas = page.getByRole('img', { name: 'Drawing canvas' })
  await expect(canvas).toBeVisible()
  const prevented = await canvas.evaluate(svg => {
    const result: boolean[] = []
    for (const type of ['touchstart', 'touchmove']) {
      const event = new Event(type, { bubbles: true, cancelable: true })
      svg.dispatchEvent(event)
      result.push(event.defaultPrevented)
    }
    svg.dispatchEvent(new Event('touchmove', { bubbles: true, cancelable: false }))
    return result
  })
  expect(prevented).toEqual([true, true])
  await expect(canvas.locator('..')).toHaveCSS('touch-action', 'none')
  await expect(page.getByTestId('input-diagnostics')).toContainText('touchstart prevented=true')
  const control = page.getByRole('button', { name: 'Pen (P)', exact: true })
  expect(await control.evaluate(button => {
    const event = new Event('touchstart', { bubbles: true, cancelable: true })
    button.dispatchEvent(event)
    return event.defaultPrevented
  })).toBe(false)
  const rectangle = page.getByRole('button', { name: 'Rectangle (R)', exact: true })
  await rectangle.click()
  await expect(rectangle).toHaveAttribute('aria-pressed', 'true')
})

test('a cancelled touch followed by touchend stays cancelled and a fresh contact recovers', async ({ browser, browserName }) => {
  test.skip(browserName !== 'chromium', 'Real touch cancellation injection uses CDP.')
  const context = await browser.newContext({ hasTouch: true, viewport: { width: 1100, height: 800 } })
  const page = await context.newPage()
  const cdp = await context.newCDPSession(page)
  try {
    await page.goto('/?input-debug=1')
    const canvas = page.getByRole('img', { name: 'Drawing canvas' })
    await expect(canvas).toBeVisible()
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: 350, y: 250 }] })
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x: 450, y: 280 }] })
    await expect(page.getByTestId('drawing-elements').locator(':scope > *')).toHaveCount(1)
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchCancel', touchPoints: [] })
    await canvas.dispatchEvent('touchend', { bubbles: true })
    await expect(page.getByTestId('drawing-elements').locator(':scope > *')).toHaveCount(0)
    await expect(page.getByRole('button', { name: 'Undo', exact: true })).toBeDisabled()
    await expect(page.getByTestId('input-diagnostics')).toContainText(/touchend.*gesture=none active=0 saved=0/)
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: 350, y: 250 }] })
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x: 450, y: 280 }] })
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] })
    await expect(page.getByRole('button', { name: 'Undo', exact: true })).toBeEnabled()
    await expect(page.getByTestId('drawing-elements').locator(':scope > *')).toHaveCount(1)
  } finally { await cdp.detach(); await context.close() }
})

test('native touch taps commit through the canvas touch guard', async ({ browser }) => {
  const context = await browser.newContext({ hasTouch: true, isMobile: true, viewport: { width: 1100, height: 800 } })
  const page = await context.newPage()
  try {
    await page.goto('/?input-debug=1')
    await expect(page.getByRole('img', { name: 'Drawing canvas' })).toBeVisible()
    await page.touchscreen.tap(350, 250)
    await expect(page.getByTestId('drawing-elements').locator(':scope > *')).toHaveCount(1)
    await expect(page.getByRole('button', { name: 'Undo', exact: true })).toBeEnabled()
    const trace = page.getByTestId('input-diagnostics')
    await expect(trace).toContainText('touchstart prevented=true')
    await expect(trace).toContainText(/pointerup touch.*saved=1/)
    await expect(trace).not.toContainText('pointercancel')
  } finally { await context.close() }
})
