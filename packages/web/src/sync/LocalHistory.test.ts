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
it('reclaims byte capacity after partially invalidating actions and moving history between stacks', () => {
  const history = new LocalHistory()
  const points = Array.from({length:12000},(_,i)=>({x:i,y:i}))
  const element = (id:string): BoardElement => ({id,type:'pen',color:'#123456',width:3,points})
  for(let i=0;i<20;i++) history.record([], [element(`a-${i}`),element(`b-${i}`)])
  const count = history.undo.length
  history.invalidate(new Set(Array.from({length:20},(_,i)=>`a-${i}`)))
  expect(history.undo).toHaveLength(count)
  expect(history.undo.every(action=>action.length===1)).toBe(true)
  const oldest = history.undo[0]
  history.redo.push(history.undo.pop()!)
  history.undo.push(history.redo.pop()!)
  history.record([], [element('new')])
  expect(history.undo[0]).toBe(oldest)
  expect(history.redo).toHaveLength(0)
  expect(new TextEncoder().encode(JSON.stringify(history.undo)).length).toBeLessThanOrEqual(8*1024*1024)
})
