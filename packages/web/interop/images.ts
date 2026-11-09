import { expect, type Page } from '@playwright/test'
import { fileURLToPath } from 'node:url'
import fixtures from '../../shared-protocol/fixtures/image-elements.json' with { type: 'json' }
import type { nativePeer } from './peers'
export async function imagesInterop(page: Page, native: Pick<ReturnType<typeof nativePeer>,'send'|'state'>) {
  await page.goto('/')
  await page.getByLabel('Image file',{exact:true}).setInputFiles(fileURLToPath(new URL('../../shared-protocol/fixtures/image.png',import.meta.url)))
  const images = page.getByTestId('drawing-elements').locator('image')
  await expect(images).toHaveCount(1)
  await page.getByRole('button',{name:'Share board',exact:true}).click()
  const invite = await page.getByLabel('Board invite',{exact:true}).inputValue()
  await page.getByRole('button',{name:'Close sharing'}).click()
  native.send({type:'join',invite})
  await expect.poll(() => native.state().elements.length,{timeout:30000}).toBe(1)
  expect(native.state().elements[0].image).toBe(fixtures.valid[0].image)
  // Valid JPEG comment segments exercise image-sized encrypted fragmentation.
  const jpeg = Buffer.from(fixtures.valid[1].image.split(',')[1], 'base64')
  const comment = Buffer.alloc(60004, 65); comment.set([255,254,234,98],0)
  const large = 'data:image/jpeg;base64,' + Buffer.concat([jpeg.subarray(0,2), ...Array(4).fill(comment), jpeg.subarray(2)]).toString('base64')
  native.send({type:'put',element:{...fixtures.valid[1],image:large}})
  await expect(images).toHaveCount(2)
  expect((await page.locator('image[href^="data:image/jpeg"]').getAttribute('href'))?.length).toBe(large.length)
  native.send({type:'move',ids:['image-jpeg'],dx:100,dy:30})
  await expect(page.locator('image[href^="data:image/jpeg"]')).toHaveAttribute('x','500')
  native.send({type:'undo'})
  await expect(page.locator('image[href^="data:image/jpeg"]')).toHaveAttribute('x','400')
  native.send({type:'redo'})
  await expect(page.locator('image[href^="data:image/jpeg"]')).toHaveAttribute('x','500')
  await page.reload(); await expect(images).toHaveCount(2,{timeout:30000})
  native.send({type:'remove',ids:native.state().elements.map(e=>e.id)})
  await expect(images).toHaveCount(0)
  native.send({type:'undo'}); await expect(images).toHaveCount(2)
}
