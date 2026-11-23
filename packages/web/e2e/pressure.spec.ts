import { test, expect } from '@playwright/test'
import { drawPressure } from '../interop/pressure'

test('stylus pressure varies width and release preserves the last contact pressure', async ({ page }) => {
  await page.goto('/')
  await expect(page.getByRole('img', { name: 'Drawing canvas' })).toBeVisible()
  await drawPressure(page)
  const lines = page.getByTestId('drawing-elements').locator('[data-element-type="pressure-pen"] line')
  const widths = await lines.evaluateAll(nodes => nodes.map(node => Number(node.getAttribute('stroke-width'))))
  expect(widths.length).toBeGreaterThan(2)
  expect(widths[0]).toBeCloseTo(3 * 0.325)
  expect(widths.at(-1)).toBeCloseTo(3 * 0.925)
  await page.mouse.move(400, 400); await page.mouse.down(); await page.mouse.move(550, 400); await page.mouse.up()
  await expect(page.getByTestId('drawing-elements').locator(':scope > polyline')).toHaveAttribute('stroke-width', '3')
  await page.keyboard.press('e'); await page.mouse.click(500, 300)
  await expect(lines).toHaveCount(0)
})

test('finger force varies only with changing contact readings; cancel and pinch leave no marks', async ({ browser }) => {
  const context = await browser.newContext({ hasTouch: true, viewport: { width: 1200, height: 800 } })
  const page = await context.newPage()
  await page.goto('/')
  await expect(page.getByRole('img', { name: 'Drawing canvas' })).toBeVisible()
  const cdp = await context.newCDPSession(page)
  const touch = async (type: 'touchStart' | 'touchMove' | 'touchEnd' | 'touchCancel', points: { x: number; y: number; force: number; id?: number }[]) => {
    await cdp.send('Input.dispatchTouchEvent', { type, touchPoints: points })
  }
  try {
    // The usual fixed placeholder must render at the selected base width.
    await touch('touchStart', [{ x: 400, y: 300, force: 0.5 }])
    await touch('touchMove', [{ x: 500, y: 300, force: 0.5 }])
    await touch('touchEnd', [])
    const elements = page.getByTestId('drawing-elements')
    await expect(elements.locator(':scope > polyline')).toHaveAttribute('stroke-width', '3')
    await touch('touchStart', [{ x: 400, y: 400, force: 0.2 }])
    await touch('touchMove', [{ x: 450, y: 400, force: 0.2 }])
    await touch('touchMove', [{ x: 500, y: 400, force: 0.8 }])
    await touch('touchMove', [{ x: 550, y: 400, force: 0.8 }])
    await touch('touchEnd', [])
    const widths = await elements.locator('[data-element-type="pressure-pen"] line').evaluateAll(nodes => nodes.map(n => Number(n.getAttribute('stroke-width'))))
    expect(widths[0]).toBeCloseTo(3 * 0.4)
    expect(widths.at(-1)).toBeCloseTo(3 * 0.85)
    await touch('touchStart', [{ x: 400, y: 500, force: 0.2 }])
    await touch('touchMove', [{ x: 450, y: 500, force: 0.8 }])
    await touch('touchCancel', [])
    await expect(elements.locator(':scope > *')).toHaveCount(2)
    await touch('touchStart', [{ x: 400, y: 500, force: 0.5, id: 1 }])
    await touch('touchStart', [{ x: 400, y: 500, force: 0.5, id: 1 }, { x: 500, y: 500, force: 0.5, id: 2 }])
    await touch('touchMove', [{ x: 380, y: 500, force: 0.5, id: 1 }, { x: 520, y: 500, force: 0.5, id: 2 }])
    await touch('touchEnd', [])
    await expect(elements.locator(':scope > *')).toHaveCount(2)
    // Fresh contact after cancellation/navigation draws normally.
    await touch('touchStart', [{ x: 400, y: 600, force: 0.5 }])
    await touch('touchEnd', [])
    await expect(elements.locator(':scope > *')).toHaveCount(3)
  } finally { await cdp.detach(); await context.close() }
})

test('a palm does not cancel or navigate an active browser pen', async ({ page }) => {
  await page.goto('/')
  await expect(page.getByRole('img', { name: 'Drawing canvas' })).toBeVisible()
  const cdp = await page.context().newCDPSession(page)
  try {
    await cdp.send('Input.dispatchMouseEvent', { type: 'mousePressed', x: 400, y: 300, button: 'left', buttons: 1, pointerType: 'pen', force: 0.1, clickCount: 1 })
    await cdp.send('Input.dispatchMouseEvent', { type: 'mouseMoved', x: 450, y: 300, button: 'left', buttons: 1, pointerType: 'pen', force: 0.1 })
    await expect(page.getByTestId('drawing-elements').locator('[data-element-type="pressure-pen"] line')).toHaveCount(1)
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: 650, y: 500, force: 0.5 }] })
    await expect(page.getByTestId('drawing-elements').locator(':scope > *')).toHaveCount(1)
    await cdp.send('Input.dispatchMouseEvent', { type: 'mouseMoved', x: 550, y: 300, button: 'left', buttons: 1, pointerType: 'pen', force: 0.9 })
    await cdp.send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: 550, y: 300, button: 'left', buttons: 0, pointerType: 'pen', force: 0, clickCount: 1 })
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] })
    await expect(page.getByTestId('drawing-elements').locator(':scope > *')).toHaveCount(1)
    await expect(page.getByTestId('drawing-elements').locator('[data-element-type="pressure-pen"]')).toHaveCount(1)
  } finally { await cdp.detach() }
})

test('coalesced samples cross the segment limit without dropping pressure or adding an endpoint dot', async ({ page }) => {
  await page.goto('/')
  const canvas = page.getByRole('img', { name: 'Drawing canvas' })
  await expect(canvas).toBeVisible()
  const cdp = await page.context().newCDPSession(page)
  try {
    await canvas.evaluate(svg => svg.addEventListener('pointerdown', event => svg.setAttribute('data-pointer-id', String((event as PointerEvent).pointerId))))
    for (const count of [11999, 12000]) {
      await cdp.send('Input.dispatchMouseEvent', { type: 'mousePressed', x: 400, y: 300, button: 'left', buttons: 1, pointerType: 'pen', force: 0.1, clickCount: 1 })
      await canvas.evaluate((svg, count) => {
        const pointerId = Number(svg.getAttribute('data-pointer-id'))
        const samples = Array.from({ length: count }, (_, i) => new PointerEvent('pointermove', {
          clientX: 400 + (i + 1) % 100, clientY: 300, pressure: i % 2 ? 0.9 : 0.1, pointerId, pointerType: 'pen', buttons: 1,
        }))
        const event = new PointerEvent('pointermove', { bubbles: true, pointerId, pointerType: 'pen', buttons: 1 })
        Object.defineProperty(event, 'getCoalescedEvents', { value: () => samples })
        svg.dispatchEvent(event)
      }, count)
      await cdp.send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: 400 + count % 100, y: 300, button: 'left', buttons: 0, pointerType: 'pen', force: 0, clickCount: 1 })
    }
    const strokes = page.getByTestId('drawing-elements').locator('[data-element-type="pressure-pen"]')
    await expect(strokes).toHaveCount(3)
    await expect(strokes.nth(0).locator('line')).toHaveCount(11999)
    await expect(strokes.nth(1).locator('line')).toHaveCount(11999)
    await expect(strokes.nth(2).locator('line')).toHaveCount(1)
  } finally { await cdp.detach() }
})

test('a pen without varying pressure keeps a full-width stroke after lift-off', async ({ page }) => {
  const cdp = await page.context().newCDPSession(page)
  try {
    for (const force of [0, 0.5, 1]) {
      await page.goto('/')
      await expect(page.getByRole('img', { name: 'Drawing canvas' })).toBeVisible()
      await cdp.send('Input.dispatchMouseEvent', { type: 'mousePressed', x: 400, y: 300, button: 'left', buttons: 1, pointerType: 'pen', force, clickCount: 1 })
      await cdp.send('Input.dispatchMouseEvent', { type: 'mouseMoved', x: 550, y: 300, button: 'left', buttons: 1, pointerType: 'pen', force })
      await expect(page.getByTestId('drawing-elements').locator(':scope > *')).toHaveCount(1)
      await cdp.send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: 550, y: 300, button: 'left', buttons: 0, pointerType: 'pen', force: 0, clickCount: 1 })
      await expect(page.getByTestId('drawing-elements').locator(':scope > polyline')).toHaveAttribute('stroke-width', '3')
      await expect(page.getByRole('button', { name: 'Undo', exact: true })).toBeEnabled()
    }
  } finally { await cdp.detach() }
})
