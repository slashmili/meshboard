import { expect } from '@playwright/test'
import { test } from './server-fixtures'

// Each scenario legitimately spends its own per-client budget. A shared server
// makes the second scenario fail depending on how quickly earlier tests ran.
for (const scenario of ['first', 'second']) {
  test(`${scenario} scenario has an independent configuration budget`, async ({ request }) => {
    for (let i = 0; i < 30; i++) {
      const response = await request.get('/api/rtc-config')
      expect(response.status(), `configuration request ${i + 1}`).toBe(200)
    }
    const limited = await request.get('/api/rtc-config')
    expect(limited.status()).toBe(429)
    expect(limited.headers()['retry-after']).toBe('60')
    expect((await request.get('/health')).status()).toBe(200)
  })
}
