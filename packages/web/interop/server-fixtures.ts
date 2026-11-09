import { test as base } from '@playwright/test'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'

/** Keep signaling rooms and credential budgets independent between scenarios. */
export const test = base.extend<{ _isolatedServer: void }>({
  _isolatedServer: [async ({}, use) => {
    const server = await createServer({
      root: fileURLToPath(new URL('../', import.meta.url)),
      server: { host: '127.0.0.1', port: 5174, strictPort: true },
      clearScreen: false,
    })
    try {
      await server.listen()
      await use()
    } finally { await server.close() }
  }, { auto: true, timeout: 30_000 }],
})
