import { expect, type Page, type Browser } from '@playwright/test'
import type { nativePeer } from './peers'
const elements = (page: Page) => page.getByTestId('drawing-elements').locator(':scope > *')

export async function textElementsInterop(page: Page, native: Pick<ReturnType<typeof nativePeer>, 'send' | 'state'>, browser: Browser) {
  await page.goto('/')
  await page.getByRole('button', { name: 'Share board', exact: true }).click()
  const link = await page.getByLabel('Board invite', { exact: true }).inputValue()
  await page.getByRole('button', { name: 'Close sharing' }).click()
  native.send({ type: 'join', invite: link })
  await expect.poll(() => native.state().connected, { timeout: 30_000 }).toBe(1)
  await page.keyboard.press('n')
  await page.mouse.click(450, 300)
  await page.getByLabel('Text', { exact: true }).fill('Web note 世界 👋')
  await page.getByRole('button', { name: 'Save text' }).click()
  await expect.poll(() => native.state().elements[0]?.text).toBe('Web note 世界 👋')
  const note = native.state().elements[0]
  native.send({ type: 'put', element: { ...note, text: 'Edited on native' } })
  await expect(elements(page).first()).toHaveText('Edited on native')
  const label = { id: 'native-text', type: 'text' as const, color: '#4878c8', width: 1, points: [{ x: 780, y: 240 }, { x: 1068, y: 312 }], text: 'Native text <plain>' }
  native.send({ type: 'put', element: label })
  await expect(elements(page)).toHaveCount(2)
  const context = await browser.newContext()
  try {
    const late = await context.newPage()
    await late.goto(link)
    await expect(elements(late)).toHaveCount(2, { timeout: 30_000 })
    await expect(late.getByTestId('drawing-elements')).toContainText('Native text <plain>')
    // A delete while the editor is open must not resurrect the removed object.
    await page.keyboard.press('t'); await page.mouse.click(800, 340)
    await expect(page.getByLabel('Text', { exact: true })).toHaveValue('Native text <plain>')
    native.send({ type: 'remove', ids: ['native-text'] })
    await expect(elements(page)).toHaveCount(1)
    await page.getByRole('button', { name: 'Save text' }).click()
    await expect(page.getByRole('alert')).toContainText('deleted by another participant')
    await expect(elements(late)).toHaveCount(1)
  } finally { await context.close() }
}
