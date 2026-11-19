import { expect, type Page } from '@playwright/test'
import type { nativePeer } from './peers'

export async function drawPressure(page: Page) {
  const cdp = await page.context().newCDPSession(page)
  try {
    await cdp.send('Input.dispatchMouseEvent', { type: 'mousePressed', x: 400, y: 300, button: 'left', buttons: 1, pointerType: 'pen', force: 0.1, clickCount: 1 })
    for (const [x, force] of [[450, 0.1], [500, 0.9], [550, 0.9]]) {
      await cdp.send('Input.dispatchMouseEvent', { type: 'mouseMoved', x, y: 300, button: 'left', buttons: 1, pointerType: 'pen', force })
    }
    await cdp.send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: 550, y: 300, button: 'left', buttons: 0, pointerType: 'pen', force: 0, clickCount: 1 })
  } finally { await cdp.detach() }
}

export async function pressureInterop(page: Page, native: Pick<ReturnType<typeof nativePeer>, 'send' | 'state'>) {
  await page.goto('/')
  await page.getByRole('button', { name: 'Share board', exact: true }).click()
  const link = await page.getByLabel('Board invite', { exact: true }).inputValue()
  await page.getByRole('button', { name: 'Close sharing' }).click()
  native.send({ type: 'join', invite: link })
  await expect.poll(() => native.state().connected, { timeout: 30_000 }).toBe(1)
  await drawPressure(page)
  await expect.poll(() => native.state().elements.length).toBe(1)
  const pen = native.state().elements[0]
  expect(pen.points[0].pressure).toBeCloseTo(0.1)
  expect(pen.points.at(-1)!.pressure).toBeCloseTo(0.9)
  native.send({ type: 'put', element: { ...pen, points: pen.points.map(p => ({ ...p, pressure: 0.5 })) } })
  const lines = page.getByTestId('drawing-elements').locator('[data-element-type="pressure-pen"] line')
  await expect(lines.first()).toHaveAttribute('stroke-width', String(pen.width * 0.625))
  await page.reload()
  await expect(lines.first()).toHaveAttribute('stroke-width', String(pen.width * 0.625), { timeout: 30_000 })
}
