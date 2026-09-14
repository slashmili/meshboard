import { describe, expect, it } from 'vitest'
import type { BoardElement } from '@meshboard/shared-protocol'
import { BoardDocument } from './BoardDocument'

const shape = (id: string): BoardElement => ({ id, type: 'line', color: '#293b36', width: 3, points: [{ x: 10, y: 20 }, { x: 30, y: 40 }] })

describe('in-memory live document', () => {
  it('keeps concurrent inserts from both peers on snapshot exchange', () => {
    const a = new BoardDocument(), b = new BoardDocument()
    a.put(shape('a')); b.put(shape('b'))
    const first = a.snapshot(), second = b.snapshot()
    a.receive(second); b.receive(first)
    expect(a.getElements()).toEqual(b.getElements())
    expect(a.getElements()).toHaveLength(2)
  })
  it('does not resurrect erased shapes from a stale late-join snapshot', () => {
    const a = new BoardDocument(), b = new BoardDocument()
    a.put(shape('a'))
    const stale = a.snapshot()
    a.remove(['a'])
    a.receive(stale)
    b.receive(a.snapshot()); b.receive(stale)
    expect(a.getElements()).toEqual([])
    expect(b.getElements()).toEqual([])
  })
  it('clears known shapes without deleting a concurrent unseen stroke', () => {
    const a = new BoardDocument(), b = new BoardDocument()
    a.put(shape('a')); b.receive(a.snapshot()); b.put(shape('b'))
    a.clear(); b.receive(a.snapshot()); a.receive(b.snapshot())
    expect(a.getElements()).toEqual([shape('b')])
    expect(b.getElements()).toEqual([shape('b')])
  })
  it('does not echo remote messages back into the network', () => {
    const a = new BoardDocument()
    const outgoing: unknown[] = []
    a.onLocalMessage(message => outgoing.push(message))
    a.receive({ v: 1, type: 'put', element: shape('remote') })
    expect(outgoing).toHaveLength(0)
    a.put(shape('local'))
    expect(outgoing).toHaveLength(1)
  })
})
