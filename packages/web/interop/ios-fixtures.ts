import { test as base, type Page } from '@playwright/test'
import { iosPeer, nativePeer } from './peers'

export const test = base.extend<{
  relay: boolean
  native: Awaited<ReturnType<typeof iosPeer>>
  mac: ReturnType<typeof nativePeer>
  third: Page
}>({
  relay: [false, { option: true }],
  // Explicit fixture timeouts keep setup/teardown outside the 60s board-test budget.
  // Allow two 30s simulator commands, 20s adapter startup, and failure cleanup.
  native: [async ({ relay }, use) => {
    const peer = await test.step('Start iOS simulator peer', () => iosPeer(relay))
    try { await use(peer) }
    finally { await test.step('Stop iOS simulator peer', () => peer.close()) }
  }, { timeout: 120_000 }],
  mac: [async ({}, use) => {
    const peer = await test.step('Start macOS peer', () => nativePeer())
    try { await use(peer) }
    finally { await test.step('Stop macOS peer', () => peer.close()) }
  }, { timeout: 20_000 }],
  third: [async ({ browser }, use) => {
    const context = await browser.newContext()
    try { await use(await context.newPage()) }
    finally { await context.close() }
  }, { timeout: 30_000 }],
})
