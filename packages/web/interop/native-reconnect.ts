import { expect, test, type Page, type TestInfo } from '@playwright/test'
import type { nativePeer } from './peers'
import { signalingProxy } from './signaling-proxy'

type Peer = Pick<ReturnType<typeof nativePeer>, 'send' | 'state'>
const elements = (page: Page) => page.getByTestId('drawing-elements').locator(':scope > *')
const stroke = (id: string) => ({ id, type: 'pen', color: '#387c59', width: 3, points: [{ x: 500, y: 280 }, { x: 600, y: 360 }] })

/** The same scenario runs against the real desktop and Android controllers. */
export async function nativeReconnect(page: Page, isolated: Peer, healthy: Peer, testInfo: TestInfo) {
  const check = expect.configure({ timeout: 30_000 })
  const proxy = await signalingProxy()
  const state = () => {
    const value = isolated.state(true)
    expect([null, 'Reconnecting to the connection service…']).toContain(value.error)
    return value
  }
  try {
    for (const id of ['seed-online', 'seed-offline']) isolated.send({ type: 'put', element: stroke(id) })
    isolated.send({ type: 'share', origin: proxy.origin })
    await check.poll(() => state().signaling).toBe(true)
    const invite = state().invite
    const canonical = invite.replace(proxy.origin, 'http://127.0.0.1:5174')
    healthy.send({ type: 'join', invite: canonical })
    await page.goto(canonical)
    await check.poll(() => [state().connected, healthy.state().connected]).toEqual([2, 2])
    await check.poll(() => healthy.state().elements).toEqual(state().elements)
    await check(elements(page)).toHaveCount(2)

    await test.step('native data channels survive signaling loss', async () => {
      proxy.pause()
      await check.poll(() => state().signaling).toBe(false)
      const attempts = proxy.rejected()
      await check.poll(proxy.rejected).toBeGreaterThan(attempts)
      isolated.send({ type: 'put', element: stroke('during-outage') })
      await check.poll(() => healthy.state().elements.length).toBe(3)
      await check(elements(page)).toHaveCount(3)
      expect(state().connected).toBe(2)
    })

    for (let round = 1; round <= 2; round++) await test.step(`native offline edits merge automatically (${round})`, async () => {
      proxy.pause()
      await check.poll(() => state().signaling).toBe(false)
      isolated.send({ type: 'close-channels' })
      await check.poll(() => [state().connected, healthy.state().connected]).toEqual([0, 1])
      await check(page.getByTestId('connection-status')).toHaveText('1 peer connected')
      const before = state().elements
      const onlineDelete = round === 1 ? 'seed-online' : 'isolated-1'
      const offlineDelete = round === 1 ? 'seed-offline' : 'healthy-1'
      isolated.send({ type: 'remove', ids: [offlineDelete] })
      isolated.send({ type: 'put', element: stroke(`isolated-${round}`) })
      healthy.send({ type: 'remove', ids: [onlineDelete] })
      healthy.send({ type: 'put', element: stroke(`healthy-${round}`) })
      await check.poll(() => state().elements.map(e => e.id)).toContain(`isolated-${round}`)
      await check.poll(() => healthy.state().elements.map(e => e.id)).toContain(`healthy-${round}`)
      await page.mouse.move(350, 300); await page.mouse.down()
      await page.mouse.move(450, 360, { steps: 8 }); await page.mouse.up()
      await check.poll(() => healthy.state().elements.length).toBe(before.length + 1)
      expect(state().elements).toHaveLength(before.length)
      expect(state().elements.map(e => e.id)).toContain(onlineDelete)
      expect(healthy.state().elements.map(e => e.id)).toContain(offlineDelete)
      const expected = [...healthy.state().elements.filter(e => e.id !== offlineDelete), ...state().elements.filter(e => e.id === `isolated-${round}`)]
        .sort((a, b) => a.id.localeCompare(b.id))

      proxy.resume()
      // No controller retry/leave/join, no process restart, no replacement document.
      await check.poll(() => [state().signaling, state().connected, healthy.state().connected]).toEqual([true, 2, 2])
      for (const peer of [isolated, healthy]) {
        await check.poll(() => peer.state().elements.slice().sort((a, b) => a.id.localeCompare(b.id))).toEqual(expected)
      }
      await check(elements(page)).toHaveCount(expected.length)
      expect(state().invite).toBe(invite)
    })
  } catch (error) {
    const peers = [isolated, healthy].map(peer => {
      try { const { elements, previews, ...value } = peer.state(true); return { ...value, ids: elements.map(e => e.id), previews: previews.length } }
      catch (error) { return { error: String(error) } }
    })
    await testInfo.attach('native-reconnect-state', { body: JSON.stringify({ peers, rejectedConnections: proxy.rejected() }, null, 2), contentType: 'application/json' })
    throw error
  } finally { await proxy.close() }
}
