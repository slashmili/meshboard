import { defineConfig } from '@playwright/test'
import base from './playwright.config'

export default defineConfig({ ...base, testDir: './interop', testMatch: 'android.spec.ts', workers: 1, timeout: 90_000 })
