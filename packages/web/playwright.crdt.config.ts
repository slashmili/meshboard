import { defineConfig } from '@playwright/test'
import base from './playwright.config'

process.env.MESHBOARD_CRDT_PREVIEW = '1'
export default defineConfig({
  ...base, testDir: './interop', testMatch: 'crdt.spec.ts', workers: 1, timeout: 90_000,
  use: { ...base.use, baseURL: 'http://127.0.0.1:5174' },
  webServer: { command: 'pnpm dev --port 5174', url: 'http://127.0.0.1:5174', reuseExistingServer: !process.env.CI },
})
