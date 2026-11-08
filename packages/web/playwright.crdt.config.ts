import { defineConfig } from '@playwright/test'
import base from './playwright.config'

process.env.MESHBOARD_CRDT_PREVIEW = '1'
export default defineConfig({
  ...base, testDir: './interop', testMatch: ['crdt.spec.ts', 'server-isolation.spec.ts'], workers: 1, timeout: 90_000,
  use: { ...base.use, baseURL: 'http://127.0.0.1:5174' },
  // An automatic test fixture owns a fresh Vite/signaling server per scenario.
  webServer: undefined,
})
