import { expect, it } from 'vitest'
import { LocalHistory } from './LocalHistory'
import type { BoardElement } from '@meshboard/shared-protocol'
it('bounds stored inverse values by bytes as well as action count', () => {
  const history = new LocalHistory()
  const element = (id:string): BoardElement => ({id,type:'pen',color:'#123456',width:3,points:Array.from({length:12000},(_,i)=>({x:i,y:i}))})
  for(let i=0;i<50;i++) history.record([], [element(String(i))])
  expect(history.undo.length).toBeGreaterThan(0)
  expect(history.undo.length).toBeLessThan(50)
  expect(new TextEncoder().encode(JSON.stringify(history.undo)).length).toBeLessThanOrEqual(8*1024*1024)
  expect(history.undo.at(-1)?.[0].id).toBe('49')
})
