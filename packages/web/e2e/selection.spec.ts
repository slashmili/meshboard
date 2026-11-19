import { test, expect } from '@playwright/test'
async function drag(page: import('@playwright/test').Page, a: number[], b: number[]) {
  await page.mouse.move(a[0], a[1]); await page.mouse.down(); await page.mouse.move(b[0], b[1]); await page.mouse.up()
}
test('select and move a group, cancel a move, and keep the toolbar usable on phones', async ({ page }) => {
  await page.goto('/')
  await page.keyboard.press('r')
  await drag(page, [400,300], [480,360]); await drag(page, [600,300], [680,360])
  const rects = page.getByTestId('drawing-elements').locator('rect')
  const before = await rects.evaluateAll(nodes => nodes.map(n => [Number(n.getAttribute('x')), Number(n.getAttribute('y'))]))
  await page.keyboard.press('v'); await drag(page,[350,250],[720,410])
  await expect(page.getByTestId('selection-overlay').locator('rect')).toHaveCount(2)
  await drag(page,[440,330],[540,430])
  expect(await rects.evaluateAll(nodes => nodes.map(n => [Number(n.getAttribute('x')), Number(n.getAttribute('y'))]))).toEqual(before.map(([x,y]) => [x+100,y+100]))
  const moved = await rects.evaluateAll(nodes => nodes.map(n => n.outerHTML))
  await page.mouse.move(540,430); await page.mouse.down(); await page.mouse.move(600,500)
  await page.keyboard.press('Escape'); await page.mouse.up()
  expect(await rects.evaluateAll(nodes => nodes.map(n => n.outerHTML))).toEqual(moved)
  await page.setViewportSize({ width:390, height:844 })
  await expect(page.getByRole('button', {name:'Select (V)', exact:true})).toBeInViewport()
  await expect(page.getByRole('button', {name:'Pan (H)', exact:true})).toBeInViewport()
})
