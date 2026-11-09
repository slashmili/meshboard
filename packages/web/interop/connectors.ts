import {expect,type Page} from '@playwright/test'
import type {nativePeer} from './peers'
export async function connectorsInterop(page:Page,native:Pick<ReturnType<typeof nativePeer>,'send'|'state'>) {
 await page.goto('/');await page.getByRole('button',{name:'Share board',exact:true}).click()
 const invite=await page.getByLabel('Board invite',{exact:true}).inputValue();await page.getByRole('button',{name:'Close sharing'}).click()
 native.send({type:'join',invite});await expect.poll(()=>native.state().connected,{timeout:30000}).toBe(1)
 const box={id:'box',type:'rectangle',color:'#123456',width:3,points:[{x:400,y:200},{x:500,y:300}]}
 native.send({type:'put',element:box});await expect(page.getByTestId('drawing-elements').locator('rect')).toHaveCount(1)
 const canvas=(await page.getByRole('img',{name:'Drawing canvas'}).boundingBox())!
 await page.keyboard.press('a');await page.mouse.move(500,canvas.y+250);await page.mouse.down();await page.mouse.move(700,canvas.y+250);await page.mouse.up()
 await expect.poll(()=>native.state().elements.find(e=>e.type==='arrow')?.startAttachment?.id).toBe('box')
 const arrow=page.locator('[data-element-type="arrow"]')
 native.send({type:'move',ids:['box'],dx:50,dy:20});await expect(arrow).toHaveAttribute('points',/^550,270 /)
 native.send({type:'remove',ids:['box']});await expect(arrow).toHaveCount(0)
 native.send({type:'undo'});await expect(arrow).toHaveAttribute('points',/^550,270 /)
 await page.reload();await expect(arrow).toHaveAttribute('points',/^550,270 /,{timeout:30000})
 native.send({type:'put',element:{...native.state().elements.find(e=>e.type==='arrow')!,endAttachment:{id:'box',x:0,y:.5}}})
 await expect(arrow).toHaveAttribute('points',/^550,270 450,270 /)
 // A concurrent removal must not be undone by releasing an endpoint drag.
 await page.keyboard.press('a')
 await page.mouse.move(550,canvas.y+270);await page.mouse.down();await page.mouse.move(650,canvas.y+350)
 native.send({type:'remove',ids:native.state().elements.map(e=>e.id)})
 await expect(page.getByTestId('drawing-elements').locator('rect')).toHaveCount(0)
 await page.mouse.up();await expect(arrow).toHaveCount(0)
 expect(native.state().elements).toHaveLength(0)
 native.send({type:'undo'});await expect(arrow).toHaveCount(1)

}
