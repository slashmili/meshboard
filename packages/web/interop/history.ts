import { expect, type Page } from '@playwright/test'
import type { nativePeer } from './peers'
export async function historyInterop(page: Page, native: Pick<ReturnType<typeof nativePeer>, 'send' | 'state'>) {
  await page.goto('/')
  await page.mouse.move(400,300); await page.mouse.down(); await page.mouse.move(460,360); await page.mouse.up()
  await page.getByRole('button', { name:'Share board', exact:true }).click()
  const invite = await page.getByLabel('Board invite', { exact:true }).inputValue()
  await page.getByRole('button', { name:'Close sharing' }).click()
  native.send({type:'join',invite})
  await expect.poll(() => native.state().elements.length, {timeout:30000}).toBe(1)
  const webElement = native.state().elements[0]
  native.send({type:'put',element:{...webElement,id:'native-history'}})
  const objects = page.getByTestId('drawing-elements').locator(':scope > *')
  await expect(objects).toHaveCount(2)
  await page.getByRole('button',{name:'Undo',exact:true}).click()
  await expect.poll(() => native.state().elements.map(e=>e.id)).toEqual(['native-history'])
  await page.getByRole('button',{name:'Redo',exact:true}).click()
  await expect.poll(() => native.state().elements.length).toBe(2)
  native.send({type:'undo'}); await expect(objects).toHaveCount(1)
  expect(native.state().elements[0].id).toBe(webElement.id)
  native.send({type:'redo'}); await expect(objects).toHaveCount(2)
  // Even a same-value remote write owns the object now: local undo must not erase it.
  native.send({type:'put',element:webElement})
  await expect(page.getByRole('button',{name:'Undo',exact:true})).toBeDisabled()
  await page.reload(); await expect(objects).toHaveCount(2,{timeout:30000})
  await expect(page.getByRole('button',{name:'Undo',exact:true})).toBeDisabled()
}
