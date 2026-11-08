import { expect, type Page } from '@playwright/test'
import type { nativePeer } from './peers'

export async function arrowsInterop(page: Page, native: Pick<ReturnType<typeof nativePeer>, 'send' | 'state'>) {
  await page.goto('/')
  await page.getByRole('button', { name: 'Share board', exact: true }).click()
  const link = await page.getByLabel('Board invite', { exact: true }).inputValue()
  await page.getByRole('button', { name: 'Close sharing' }).click()
  native.send({ type: 'join', invite: link })
  await expect.poll(() => native.state().connected, { timeout: 30_000 }).toBe(1)
  await page.keyboard.press('a')
  await page.mouse.move(350, 300); await page.mouse.down()
  await page.mouse.move(550, 300); await page.mouse.up()
  await expect.poll(() => native.state().elements.map(e => e.type)).toEqual(['arrow'])
  const arrow = native.state().elements[0]
  native.send({ type: 'put', element: { ...arrow, points: [...arrow.points].reverse() } })
  const rendered = page.getByTestId('drawing-elements').locator('[data-element-type="arrow"]')
  await expect.poll(async () => (await rendered.getAttribute('points'))?.split(' ')[0]).toBe(`${arrow.points[1].x},${arrow.points[1].y}`)
  await page.reload()
  await expect(rendered).toHaveCount(1, { timeout: 30_000 })
  await page.keyboard.press('e'); await page.mouse.click(450, 300)
  await expect.poll(() => native.state().elements.length).toBe(0)
}
