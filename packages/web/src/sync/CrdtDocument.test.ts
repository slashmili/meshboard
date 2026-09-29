import { expect, it } from 'vitest'
import * as Y from 'yjs'
import type { BoardElement, CrdtMessage } from '@meshboard/shared-protocol'
import { CrdtDocument } from './CrdtDocument'
import { encodeFrames, FrameReceiver } from './frames'

const shape = (id: string): BoardElement => ({ id, type: 'pen', color: '#123456', width: 3, points: [{ x: 12, y: 34 }] })
function sync(from: CrdtDocument, to: CrdtDocument) { to.receive(from.receive(to.snapshot())!) }
function malicious(change: (doc: Y.Doc) => void): CrdtMessage {
  const doc = new Y.Doc(); change(doc)
  const raw = String.fromCharCode(...Y.encodeStateAsUpdate(doc)); doc.destroy()
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
