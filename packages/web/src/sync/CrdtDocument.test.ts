import { expect, it } from 'vitest'
import * as Y from 'yjs'
import type { BoardElement, CrdtMessage } from '@meshboard/shared-protocol'
import { CrdtDocument } from './CrdtDocument'
import { encodeFrames, FrameReceiver } from './frames'

const shape = (id: string): BoardElement => ({ id, type: 'pen', color: '#123456', width: 3, points: [{ x: 12, y: 34 }] })
function sync(from: CrdtDocument, to: CrdtDocument) { to.receive(from.receive(to.snapshot())!) }
function malicious(change: (doc: Y.Doc) => void): CrdtMessage {
  const doc = new Y.Doc(); change(doc)
  const raw = Array.from(Y.encodeStateAsUpdate(doc), byte => String.fromCharCode(byte)).join(''); doc.destroy()
  return { v: 2, type: 'crdt', step: 'update', data: btoa(raw) }
}
it('synchronizes current state, concurrent edits and delete-only differences', () => {
  const a = new CrdtDocument(), b = new CrdtDocument()
  a.put(shape('a')); sync(a, b)
  a.clear(); b.put(shape('b'))
  sync(a, b); sync(b, a)
  expect(a.getElements()).toEqual([shape('b')])
  expect(b.getElements()).toEqual(a.getElements())
})
it('does not revive deletes from old snapshots or echo incoming updates', () => {
  const a = new CrdtDocument(), b = new CrdtDocument()
  a.put(shape('a')); const stale = a.fullState(); a.remove(['a'])
  const sent: unknown[] = []; b.onLocalMessage(message => sent.push(message))
  b.receive(a.fullState()); b.receive(stale)
  expect(b.getElements()).toEqual([]); expect(sent).toEqual([])
})
it('rejects invalid shapes, foreign roots and shared nested values without changing the board', () => {
  const doc = new CrdtDocument(); doc.put(shape('safe'))
  for (const update of [
    malicious(d => d.getMap('elements').set('bad', { ...shape('bad'), width: -1 })),
    malicious(d => d.getMap('elements').set('wrong-id', shape('other'))),
    malicious(d => d.getMap('foreign').set('secret', 'unexpected')),
    malicious(d => d.getMap('elements').set('nested', new Y.Map())),
    { v: 2, type: 'crdt', step: 'update', data: '/w==' } as CrdtMessage,
  ]) {
    expect(() => doc.receive(update)).toThrow()
    expect(doc.getElements()).toEqual([shape('safe')])
  }
  expect(doc.put(shape('after-rejection'))).toBe(true)
})
it('rejects over-limit updates and keeps local validation failures out of the document', () => {
  const doc = new CrdtDocument(); doc.put(shape('safe'))
  expect(doc.put({ ...shape('bad'), points: [] })).toBe(false)
  expect(() => doc.receive({ v: 2, type: 'crdt', step: 'update', data: 'AAAA'.repeat(700_000) })).toThrow()
  expect(doc.getElements()).toEqual([shape('safe')])
})
it('frames preview traffic but never accepts legacy drawing messages in CRDT mode', () => {
  const doc = new CrdtDocument(); doc.put(shape('a'))
  const receiver = new FrameReceiver(true)
  const message = doc.fullState()
  expect(encodeFrames(message).map(frame => receiver.accept(frame)).filter(Boolean)).toEqual([message])
  expect(() => new FrameReceiver().accept(encodeFrames(message)[0])).toThrow()
  expect(() => new FrameReceiver(true).accept(encodeFrames({ v: 1, type: 'remove', ids: [] })[0])).toThrow()
})

it('moves a group in one update, preserves fields and skips remotely deleted objects', () => {
  const a = new CrdtDocument(), b = new CrdtDocument()
  a.put({ ...shape('a'), points: [{ x: 12, y: 34, pressure: 0.5 }] }); a.put(shape('b'))
  sync(a, b); b.remove(['b']); sync(b, a)
  let updates = 0
  a.ydoc.on('update', () => updates++)
  a.move(['a', 'b', 'a'], { x: 10, y: -3 })
  expect(updates).toBe(1)
  expect(a.getElements()).toHaveLength(1)
  expect(a.getElements()[0].points).toEqual([{ x: 22, y: 31, pressure: 0.5 }])
  sync(a, b); expect(b.getElements()).toEqual(a.getElements())
  const before = a.getElements()
  a.move(['a'], { x: 1e8, y: 0 })
  expect(a.getElements()).toEqual(before)
})

it('rolls back the whole move when only one member exceeds the coordinate limit', () => {
  const doc = new CrdtDocument()
  doc.put(shape('a')); doc.put({ ...shape('b'), points: [{ x: 1e7, y: 0 }] })
  const before = doc.getElements()
  let updates = 0; doc.ydoc.on('update', () => updates++)
  doc.move(['a', 'b'], { x: 1, y: 0 })
  expect(doc.getElements()).toEqual(before)
  expect(updates).toBe(0)
})

it('undo and redo only local changes while retaining remote objects', () => {
  const a = new CrdtDocument(), b = new CrdtDocument()
  a.put(shape('a')); sync(a,b)
  expect(b.canUndo).toBe(false)
  b.put(shape('b')); sync(b,a)
  a.undo(); sync(a,b)
  expect(a.getElements().map(e=>e.id)).toEqual(['b'])
  expect(b.getElements()).toEqual(a.getElements())
  expect(a.canRedo).toBe(true)
  a.redo(); sync(a,b)
  expect(a.getElements().map(e=>e.id)).toEqual(['a','b'])
  expect(b.getElements()).toEqual(a.getElements())
})
it('remote writes invalidate history for that object even when its visible value is identical', () => {
  const a = new CrdtDocument(), b = new CrdtDocument()
  a.put(shape('same')); a.put(shape('other')); sync(a,b)
  b.put(shape('same')); sync(b,a)
  a.undo()
  expect(a.getElements()).toEqual([shape('same')])
  expect(a.canUndo).toBe(false)
  b.remove(['other']); sync(b,a)
  // A remote deletion of an already locally absent object may have no visible effect.
  a.redo()
  expect(a.getElements().some(e=>e.id === 'same')).toBe(true)
})
it('a group move and a clear each undo in one action; a new action discards redo', () => {
  const doc = new CrdtDocument()
  doc.put(shape('a')); doc.put(shape('b')); const before = doc.getElements()
  doc.move(['a','b'],{x:20,y:30}); doc.undo(); expect(doc.getElements()).toEqual(before)
  doc.redo(); expect(doc.getElements().every(e=>e.points[0].x === 32)).toBe(true)
  doc.clear(); doc.undo(); expect(doc.getElements()).toHaveLength(2)
  doc.put(shape('c')); expect(doc.canRedo).toBe(false)
})
it('undo rejects a restore that exceeds document limits without losing history', () => {
  const a = new CrdtDocument(), b = new CrdtDocument()
  a.receive(malicious(doc => { for(let i=0;i<2000;i++) doc.getMap('elements').set(`e-${i}`,shape(`e-${i}`)) }))
  a.remove(['e-0']); sync(a,b); b.put(shape('new')); sync(b,a)
  const before = a.getElements(); a.undo()
  expect(a.getElements()).toEqual(before); expect(a.canUndo).toBe(true); expect(a.canRedo).toBe(false)
})
it('bounds history to the newest 100 local actions', () => {
  const doc = new CrdtDocument()
  for(let i=0;i<101;i++) doc.put({...shape('a'), points:[{x:i,y:0}]})
  for(let i=0;i<100;i++) doc.undo()
  expect(doc.getElements()[0].points[0].x).toBe(0)
  expect(doc.canUndo).toBe(false)
})
it('invalid remote input preserves history and remote edits prune only one group member', () => {
  const a = new CrdtDocument(), b = new CrdtDocument()
  a.put(shape('a')); a.put(shape('b')); a.move(['a','b'],{x:20,y:0}); sync(a,b)
  expect(() => a.receive(malicious(d => d.getMap('elements').set('bad',{})))).toThrow()
  expect(a.canUndo).toBe(true)
  b.put({...shape('a'),color:'#abcdef'}); sync(b,a)
  a.undo()
  expect(a.getElements()).toEqual([{...shape('a'),color:'#abcdef'},shape('b')])
  b.put({...shape('b'),color:'#abcdef'}); sync(b,a)
  expect(a.canRedo).toBe(false)
})
