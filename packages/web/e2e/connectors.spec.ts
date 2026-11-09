import {test,expect} from '@playwright/test'
async function drag(page:import('@playwright/test').Page,a:number[],b:number[]) {await page.mouse.move(a[0],a[1]);await page.mouse.down();await page.mouse.move(b[0],b[1]);await page.mouse.up()}
test('arrows attach, follow moved objects, reconnect and detach through endpoint dragging',async({page})=>{
 await page.goto('/');await page.keyboard.press('r')
 await drag(page,[400,300],[480,380]);await drag(page,[700,300],[780,380])
 await page.keyboard.press('a');await drag(page,[480,340],[700,340])
 const arrow=page.locator('[data-element-type="arrow"]')
 await expect(arrow).toHaveCount(1)
 const before=await arrow.getAttribute('points')
 await page.keyboard.press('v');await drag(page,[440,340],[490,390])
 await expect(arrow).not.toHaveAttribute('points',before!)
 await page.getByRole('button',{name:'Undo',exact:true}).click();await expect(arrow).toHaveAttribute('points',before!)
 await page.keyboard.press('a');await drag(page,[700,340],[900,500]);await expect(arrow).toHaveCount(1)
 await expect(arrow).toHaveAttribute('points',/900,/) // SVG points use canvas-local y.
 await drag(page,[900,500],[700,340]);await expect(arrow).toHaveAttribute('points',before!)
 await page.getByRole('button',{name:'Undo',exact:true}).click();await expect(arrow).toHaveAttribute('points',/900,/)
 await page.getByRole('button',{name:'Undo',exact:true}).click();await expect(arrow).toHaveAttribute('points',before!)
 await page.keyboard.press('e');await page.mouse.click(400,300);await expect(arrow).toHaveCount(0)
 await page.getByRole('button',{name:'Undo',exact:true}).click();await expect(arrow).toHaveCount(1)
})
