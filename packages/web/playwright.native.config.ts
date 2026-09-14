import { defineConfig } from '@playwright/test'
import base from './playwright.config'

export default defineConfig({ ...base, testDir: './interop', workers: 1, timeout: 60_000 })
