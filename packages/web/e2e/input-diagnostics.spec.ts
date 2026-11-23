import { expect, test } from '@playwright/test'

test('input diagnostics are opt-in and distinguish committed strokes from cancellation', async ({ page }) => {
  await page.goto('/')
  await expect(page.getByRole('img', { name: 'Drawing canvas' })).toBeVisible()
  await expect(page.getByTestId('input-diagnostics')).toHaveCount(0)

  await page.goto('/?input-debug=1')
  const canvas = page.getByRole('img', { name: 'Drawing canvas' })
  await expect(canvas).toBeVisible()
  const trace = page.getByTestId('input-diagnostics')
  await page.mouse.move(300, 250)
  await page.mouse.down()
  await page.mouse.move(430, 270)
  await page.mouse.up()
  await expect(trace).toContainText(/pointerup mouse.*gesture=none active=0 saved=1/)
  await expect(page.getByRole('button', { name: 'Undo', exact: true })).toBeEnabled()
  // The trace deliberately contains event/state metadata rather than stroke data.
  await expect(trace).not.toContainText('clientX')
  await expect(trace).not.toContainText('pressure')
  await expect(trace).not.toContainText('points')

  await page.mouse.move(300, 250)
  await page.mouse.down()
  await page.mouse.move(450, 280)
  const pointerId = await trace.locator('pre').textContent().then(value => Number(value!.match(/pointerdown mouse id=(\d+)/)![1]))
  await canvas.dispatchEvent('pointercancel', { pointerId, pointerType: 'mouse' })
  await page.mouse.up()
  await expect(trace).toContainText(/pointercancel mouse.*gesture=none active=0 saved=1/)
  await expect(page.getByTestId('drawing-elements').locator(':scope > *')).toHaveCount(1)

  await page.reload()
  await expect(trace).not.toContainText('saved=1')
})
