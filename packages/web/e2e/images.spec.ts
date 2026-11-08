import { test, expect } from '@playwright/test'
import { fileURLToPath } from 'node:url'
const fixture = (ext:string) => fileURLToPath(new URL(`../../shared-protocol/fixtures/image.${ext}`,import.meta.url))
test('insert PNG and JPEG, move, undo, erase, and reject invalid files', async ({page}) => {
  await page.goto('/')
  const input = page.getByLabel('Image file',{exact:true})
  const images = page.getByTestId('drawing-elements').locator('image')
  for (const ext of ['png','jpeg']) await input.setInputFiles(fixture(ext))
  await expect(images).toHaveCount(2)
  await expect(images.first()).toHaveAttribute('href',/^data:image\/(png|jpeg);base64,/)
  const before = Number(await images.first().getAttribute('x'))
  const canvas = (await page.getByRole('img',{name:'Drawing canvas'}).boundingBox())!
  const x=canvas.x+canvas.width/2,y=canvas.y+canvas.height/2
  await page.keyboard.press('v'); await page.mouse.move(x,y); await page.mouse.down(); await page.mouse.move(x+80,y+40); await page.mouse.up()
  await page.getByRole('button',{name:'Undo',exact:true}).click()
  expect(Number(await images.first().getAttribute('x'))).toBe(before)
  await page.keyboard.press('e'); await page.mouse.click(x,y); await expect(images).toHaveCount(0)
  await page.getByRole('button',{name:'Undo',exact:true}).click(); await expect(images).toHaveCount(2)
  await input.setInputFiles({name:'bad.svg',mimeType:'image/svg+xml',buffer:Buffer.from('<svg/>')})
  await expect(page.getByRole('alert')).toContainText('Cannot insert')
  await expect(images).toHaveCount(2)
  await page.getByRole('button',{name:'Dismiss',exact:true}).click()
  await input.setInputFiles({name:'large.png',mimeType:'image/png',buffer:Buffer.alloc(512*1024+1)})
  await expect(page.getByRole('alert')).toContainText('512 KiB')
  await expect(images).toHaveCount(2)
  await page.setViewportSize({width:390,height:844})
  await expect(page.getByRole('button',{name:'Insert image',exact:true})).toBeInViewport()
})
