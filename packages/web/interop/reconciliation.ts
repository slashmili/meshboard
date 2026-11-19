import {expect,type Page} from '@playwright/test'
import type {nativePeer} from './peers'
/** Inject a missed application notification before encryption, leaving counters intact. */
export async function reconciliationInterop(page:Page,native:Pick<ReturnType<typeof nativePeer>,'send'|'state'>) {
  await page.route('**/src/sync/YWebrtcSession.ts',async route=>{
    const response=await route.fetch(), body=await response.text()
    const marker='const room = provider.room;'
    expect(body).toContain(marker)
    const hook=`${marker}
      window.meshboardMissedUpdate = (remove) => {
        const handler = room._docUpdateHandler;
        provider.doc.off('update', handler);
        try {
          const map = provider.doc.getMap('elements');
          if (remove) map.delete('missed');
          else map.set('missed',{id:'missed',type:'pen',color:'#123456',width:3,points:[{x:500,y:300}]});
        } finally { provider.doc.on('update', handler); }
      };`
    await route.fulfill({response,body:body.replace(marker,hook)})
  })
  await page.goto('/')
  await page.getByRole('button',{name:'Share board',exact:true}).click()
  const invite=await page.getByLabel('Board invite',{exact:true}).inputValue()
  await page.getByRole('button',{name:'Close sharing'}).click()
  native.send({type:'join',invite})
  await expect.poll(()=>native.state().connected,{timeout:30000}).toBe(1)
  await expect(page.getByTestId('connection-status')).toHaveText('1 peer connected')
  // Wait for the native's initial state-vector request/reply to complete.
  native.send({type:'put',element:{id:'seed',type:'pen',color:'#123456',width:3,points:[{x:100,y:100}]}})
  await expect(page.getByTestId('drawing-elements').locator(':scope > *')).toHaveCount(1)
  await page.evaluate(()=> (window as unknown as {meshboardMissedUpdate(remove:boolean):void}).meshboardMissedUpdate(false))
  await expect(page.getByTestId('drawing-elements').locator(':scope > *')).toHaveCount(2)
  await expect.poll(()=>native.state().elements.map(e=>e.id),{timeout:25000}).toEqual(['missed','seed'])
  await page.evaluate(()=> (window as unknown as {meshboardMissedUpdate(remove:boolean):void}).meshboardMissedUpdate(true))
  await expect.poll(()=>native.state().elements.map(e=>e.id),{timeout:25000}).toEqual(['seed'])
  expect(native.state().connected).toBe(1)
  await expect(page.getByTestId('connection-status')).toHaveText('1 peer connected')
}
