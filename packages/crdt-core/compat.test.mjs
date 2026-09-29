import { deepStrictEqual, ok, strictEqual } from 'node:assert'
import { spawnSync } from 'node:child_process'
import { createConnection } from 'node:net'
import { readFileSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import test from 'node:test'
import * as Y from 'yjs'

const root = fileURLToPath(new URL('.', import.meta.url))
const target = resolve(root, process.env.CARGO_TARGET_DIR || 'target')
const binary = join(target, 'debug', process.platform === 'win32' ? 'compat.exe' : 'compat')
const jvm = process.env.MESHBOARD_CRDT_JVM === '1'
  ? JSON.parse(readFileSync(new URL('../native/build/crdt/interop.json', import.meta.url), 'utf8')) : null
const fixtures = JSON.parse(readFileSync(new URL('../shared-protocol/fixtures/board-messages.json', import.meta.url), 'utf8'))
const strokes = fixtures.valid.filter(message => message.type === 'put').map(message => message.element)
const put = element => ({ type: 'put', id: element.id, element })
const remove = id => ({ type: 'remove', id })
const bytes = value => Array.from(value)
const snapshot = doc => bytes(Y.encodeStateAsUpdate(doc))
const vector = doc => bytes(Y.encodeStateVector(doc))
const apply = (doc, update) => Y.applyUpdate(doc, Uint8Array.from(update))
function web(t, id) {
  const doc = new Y.Doc()
  doc.clientID = id // Unique test replica IDs only; production must generate its own.
  t.after(() => doc.destroy())
  return doc
}
async function native(request) {
  if (process.env.MESHBOARD_CRDT_ANDROID_PORT) return new Promise((resolve, reject) => {
    const socket = createConnection({ host: '127.0.0.1', port: Number(process.env.MESHBOARD_CRDT_ANDROID_PORT) })
    let output = ''
    socket.setEncoding('utf8')
    socket.setTimeout(30_000, () => socket.destroy(new Error('Android CRDT response timed out')))
    socket.on('connect', () => socket.write(JSON.stringify(request) + '\n'))
    socket.on('error', reject)
    socket.on('data', data => {
      output += data
      if (output.length > 8 * 1024 * 1024) socket.destroy(new Error('Android CRDT response too large'))
    })
    socket.on('end', () => { try { resolve(JSON.parse(output)) } catch (error) { reject(error) } })
  })
  const ios = process.env.MESHBOARD_CRDT_IOS_BINARY
  if (ios) ok(process.env.MESHBOARD_IOS_SIMULATOR, 'iOS CRDT harness requires an explicit simulator UUID')
  const result = spawnSync(ios ? 'xcrun' : jvm ? jvm.java : binary,
    ios ? ['simctl', 'spawn', process.env.MESHBOARD_IOS_SIMULATOR, ios]
      : jvm ? ['-Xcheck:jni', `-Dmeshboard.crdt.library=${jvm.library}`, '-cp', jvm.classpath, 'meshboard.crdt.CrdtCompatKt'] : [],
    { input: JSON.stringify(request) + '\n', encoding: 'utf8', timeout: 30_000, maxBuffer: 8 * 1024 * 1024 })
  if (result.error) throw result.error
  strictEqual(result.status, 0, result.stderr)
  return JSON.parse(result.stdout)
}

test('Yjs board fixtures load in yrs; native changes and deletions return to Yjs', async t => {
  const doc = web(t, 1)
  const elements = doc.getMap('elements')
  for (const stroke of strokes) elements.set(stroke.id, stroke)
  const loaded = await native({ clientId: 2, updates: [snapshot(doc)] })
  deepStrictEqual(loaded.elements, elements.toJSON())
  const extra = { ...strokes[0], id: 'native-stroke', points: [{ x: -1.25, y: 2.75 }] }
  const changed = await native({ clientId: 2, updates: [loaded.update], operations: [put(extra), remove(strokes[1].id)], targetStateVector: vector(doc) })
  apply(doc, changed.update)
  deepStrictEqual(elements.toJSON(), changed.elements)
  deepStrictEqual(elements.get(extra.id), extra)
  strictEqual(elements.has(strokes[1].id), false)
})

test('a fresh Yjs peer loads a large native board and then receives only new changes', async t => {
  const large = { ...strokes[0], id: 'large', points: Array.from({ length: 12_000 }, (_, i) => ({ x: i / 8, y: 0 - i / 4 })) }
  const initial = await native({ clientId: 10, operations: [put(large)] })
  const newcomer = web(t, 11)
  apply(newcomer, initial.update)
  deepStrictEqual(newcomer.getMap('elements').get('large'), large)
  const changed = await native({ clientId: 10, updates: [initial.update], operations: [put(strokes[1])], targetStateVector: vector(newcomer) })
  ok(changed.update.length < initial.update.length / 10, 'state vector should avoid retransmitting the large stroke')
  apply(newcomer, changed.update)
  deepStrictEqual(newcomer.getMap('elements').toJSON(), changed.elements)
})

test('yrs accepts duplicate and out-of-order Yjs updates without resurrecting deletions', async t => {
  const doc = web(t, 20)
  const updates = []
  doc.on('update', update => updates.push(bytes(update)))
  doc.getMap('elements').set(strokes[0].id, strokes[0])
  doc.getMap('elements').set(strokes[1].id, strokes[1])
  doc.getMap('elements').delete(strokes[0].id)
  const received = await native({ clientId: 21, updates: [...updates.toReversed(), ...updates] })
  deepStrictEqual(received.elements, doc.getMap('elements').toJSON())
  strictEqual(Object.keys(received.elements).length, 1)
})

test('Yjs accepts duplicate and out-of-order yrs updates', async t => {
  const initial = await native({ clientId: 30, operations: [put(strokes[0])] })
  const changed = await native({ clientId: 30, updates: [initial.update], operations: [put(strokes[1])], targetStateVector: initial.stateVector })
  const doc = web(t, 31)
  for (const update of [changed.update, changed.update, initial.update, initial.update]) apply(doc, update)
  deepStrictEqual(doc.getMap('elements').toJSON(), changed.elements)
})

test('concurrent writes to the same element converge across both implementations', async t => {
  const doc = web(t, 40)
  doc.getMap('elements').set(strokes[0].id, strokes[0])
  const initial = snapshot(doc)
  doc.getMap('elements').set(strokes[0].id, { ...strokes[0], color: '#112233' })
  const rust = await native({ clientId: 41, updates: [initial], operations: [put({ ...strokes[0], color: '#aabbcc' })] })
  const merged = await native({ clientId: 41, updates: [rust.update, snapshot(doc)] })
  apply(doc, rust.update)
  deepStrictEqual(doc.getMap('elements').toJSON(), merged.elements)
})

test('delete-only changes synchronize even when the state vector has not advanced', async t => {
  const doc = web(t, 50)
  doc.getMap('elements').set(strokes[0].id, strokes[0])
  const initial = snapshot(doc)
  const before = vector(doc)
  const removed = await native({ clientId: 51, updates: [initial], operations: [remove(strokes[0].id)], targetStateVector: before })
  deepStrictEqual(removed.stateVector, before)
  apply(doc, removed.update)
  apply(doc, initial)
  deepStrictEqual(doc.getMap('elements').toJSON(), {})
})

test('a remaining peer can seed a newcomer after the original creator disappears', async t => {
  const creator = web(t, 60)
  creator.getMap('elements').set(strokes[0].id, strokes[0])
  const remaining = await native({ clientId: 61, updates: [snapshot(creator)], operations: [put(strokes[1])] })
  // The creator takes no further part. The newcomer reads only the remaining peer.
  const newcomer = web(t, 62)
  apply(newcomer, remaining.update)
  newcomer.getMap('elements').set(strokes[2].id, strokes[2])
  const delta = bytes(Y.encodeStateAsUpdate(newcomer, Uint8Array.from(remaining.stateVector)))
  const merged = await native({ clientId: 61, updates: [remaining.update, delta] })
  deepStrictEqual(merged.elements, newcomer.getMap('elements').toJSON())
  strictEqual(Object.keys(merged.elements).length, 3)
})
