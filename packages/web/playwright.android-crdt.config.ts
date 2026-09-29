import { defineConfig } from '@playwright/test'
import base from './playwright.crdt.config'

export default defineConfig({
  ...base, testMatch: 'android-crdt.spec.ts', timeout: 120_000,
  expect: { timeout: 30_000 },
})
