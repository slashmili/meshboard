import { test, expect } from '@playwright/test'
import { drawPressure } from '../interop/pressure'

test('stylus pressure varies width and release preserves the last contact pressure', async ({ page }) => {
  await page.goto('/')
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
