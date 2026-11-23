import { test, expect, type Page } from '@playwright/test'

const INK = '#293b36'
const DARK_INK = '#c4d6d1'
const stroke = (page: Page) => page.getByTestId('drawing-elements').locator(':scope > *').first()
const toggle = (page: Page) => page.getByTestId('appearance-toggle')

async function draw(page: Page) {
  await page.mouse.move(450, 350); await page.mouse.down(); await page.mouse.move(560, 420, { steps: 6 }); await page.mouse.up()
}

test('appearance follows live system changes and retains explicit override without changing board data', async ({ page }, testInfo) => {
  await page.emulateMedia({ colorScheme: 'dark' })
  await page.goto('/')
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark')
  await expect(toggle(page)).toHaveAccessibleName(/Appearance: System/)
  await page.emulateMedia({ colorScheme: 'light' })
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'light')
  await draw(page)
  await expect(stroke(page)).toHaveAttribute('stroke', INK)
  const light = await page.getByTestId('drawing-elements').innerHTML()
  const undo = page.getByRole('button', { name: 'Undo', exact: true })
  await expect(undo).toBeEnabled()
  await expect(page.getByRole('button', { name: 'Redo', exact: true })).toBeDisabled()

  await toggle(page).click() // System -> Light
  await expect(toggle(page)).toHaveAccessibleName(/Appearance: Light/)
  await toggle(page).click() // Light -> Dark
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark')
  await expect(page.locator('.header')).toHaveCSS('background-color', 'rgb(32, 41, 34)')
  await expect(page.locator('.workspace')).toHaveCSS('background-color', 'rgb(22, 27, 23)')
  await expect(page.locator('#grid circle')).toHaveAttribute('fill', '#3a463d')
  // Only the displayed ink changes; switching themes adds no history entries.
  await expect(stroke(page)).toHaveAttribute('stroke', DARK_INK)
  await expect(page.getByRole('radio', { name: 'Ink' })).toHaveAttribute('style', new RegExp(`--swatch: ${DARK_INK}`))
  await expect(page.getByRole('button', { name: 'Redo', exact: true })).toBeDisabled()
  await page.screenshot({ path: testInfo.outputPath('dark-desktop.png') })
  await page.setViewportSize({ width: 390, height: 844 })
  await page.screenshot({ path: testInfo.outputPath('dark-mobile.png') })

  await page.reload()
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark')
  await expect(toggle(page)).toHaveAccessibleName(/Appearance: Dark/)
  await page.getByRole('button', { name: 'Help and keyboard shortcuts' }).click()
  await page.getByRole('combobox', { name: 'Appearance' }).selectOption('system')
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'light')
  await page.emulateMedia({ colorScheme: 'dark' })
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark')
  expect(light).toContain(`stroke="${INK}"`)
})

test('light and dark collaborators keep identical canonical strokes', async ({ browser }) => {
  const darkContext = await browser.newContext({ colorScheme: 'dark' })
  const lightContext = await browser.newContext({ colorScheme: 'light' })
  try {
    const dark = await darkContext.newPage(), light = await lightContext.newPage()
    await dark.goto('/')
    await dark.getByRole('button', { name: 'Share board', exact: true }).click()
    const invite = await dark.getByLabel('Board invite', { exact: true }).inputValue()
    await dark.getByRole('button', { name: 'Close sharing' }).click()
    await light.goto(invite)
    await expect(light.getByTestId('connection-status')).toContainText('1 peer connected', { timeout: 30_000 })
    await draw(dark)
    await expect(stroke(dark)).toHaveAttribute('stroke', DARK_INK)
    await expect(stroke(light)).toHaveAttribute('stroke', INK)
    // The light peer switching to dark changes only its own rendering.
    await light.getByTestId('appearance-toggle').click()
    await light.getByTestId('appearance-toggle').click()
    await expect(light.locator('html')).toHaveAttribute('data-theme', 'dark')
    await expect(stroke(light)).toHaveAttribute('stroke', DARK_INK)
    await expect(dark.getByTestId('drawing-elements').locator(':scope > *')).toHaveCount(1)
    await expect(dark.getByRole('button', { name: 'Redo', exact: true })).toBeDisabled()
  } finally {
    await darkContext.close(); await lightContext.close()
  }
})

test('appearance still works when browser storage is blocked', async ({ page }) => {
  await page.addInitScript(() => {
    Object.defineProperty(window, 'localStorage', { get() { throw new Error('Storage blocked') } })
  })
  await page.goto('/')
  await toggle(page).click(); await toggle(page).click()
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark')
})
