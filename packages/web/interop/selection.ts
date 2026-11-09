import { expect, type Page } from '@playwright/test'
import type { nativePeer } from './peers'
export async function selectionInterop(page: Page, native: Pick<ReturnType<typeof nativePeer>, 'send' | 'state'>) {
  await page.goto('/')
  await page.getByRole('button', { name: 'Share board', exact: true }).click()
  const invite = await page.getByLabel('Board invite', { exact: true }).inputValue()
  await page.getByRole('button', { name: 'Close sharing' }).click()
  native.send({type:'join', invite})
  await expect.poll(() => native.state().connected, {timeout:30000}).toBe(1)
  for (const [id,x] of [['one',400],['two',600]] as const) native.send({type:'put',element:{id,type:'rectangle',color:'#293b36',width:3,points:[{x,y:200},{x:x+60,y:260}]}})
  const objects = page.getByTestId('drawing-elements').locator('rect')
  await expect(objects).toHaveCount(2)
  const canvas = (await page.getByRole('img',{name:'Drawing canvas'}).boundingBox())!
  const y = canvas.y
  await page.keyboard.press('v')
  await page.mouse.move(350,y+150); await page.mouse.down(); await page.mouse.move(700,y+300); await page.mouse.up()
  await expect(page.getByTestId('selection-overlay').locator('rect')).toHaveCount(2)
  await page.mouse.move(430,y+230); await page.mouse.down(); await page.mouse.move(530,y+330)
  native.send({type:'remove',ids:['two']})
  const one = native.state().elements.find(e => e.id === 'one')!
  native.send({type:'put',element:{...one,color:'#4878c8'}})
  await expect(objects).toHaveCount(1)
  await expect(objects).toHaveAttribute('stroke','#4878c8')
  await page.mouse.up()
  await expect.poll(() => native.state().elements.map(e => ({id:e.id,x:e.points[0].x,color:e.color}))).toEqual([{id:'one',x:500,color:'#4878c8'}])
  native.send({type:'move',ids:['one','two'],dx:20,dy:-10})
  await expect(objects).toHaveAttribute('x','520')
  await page.reload(); await expect(objects).toHaveAttribute('x','520',{timeout:30000})
}
