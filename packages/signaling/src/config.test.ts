import { createHmac } from 'node:crypto'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { rtcConfigSchema } from '@meshboard/shared-protocol'
import { rtcConfigurationProvider } from './config.ts'

const secret = '1234567890abcdef'.repeat(4)
const env = { MESHBOARD_TURN_SECRET: secret, MESHBOARD_TURN_URLS: 'turns:turn.example.com:5349?transport=tcp' }
afterEach(() => vi.useRealTimers())

describe('temporary TURN credentials', () => {
  it('issues Coturn REST HMAC-SHA1 credentials with fresh identities and bounded expiry', () => {
    vi.useFakeTimers(); vi.setSystemTime(new Date('2026-01-01T00:00:00Z'))
    const provide = rtcConfigurationProvider({ ...env, MESHBOARD_TURN_TTL_SECONDS: '3600' })
    const first = rtcConfigSchema.parse(provide())
    const ice = first.iceServers[0]
    expect(ice.username?.split(':')[0]).toBe(String(Date.now() / 1000 + 3600))
    expect(ice.credential).toBe(createHmac('sha1', secret).update(ice.username!).digest('base64'))
    expect(first.localDevelopment).toBe(false)
    expect(JSON.stringify(first)).not.toContain(secret)
    vi.advanceTimersByTime(2_000)
    const second = provide().iceServers[0]
    expect(second.username).not.toBe(ice.username)
    expect(Number(second.username?.split(':')[0])).toBe(Date.now() / 1000 + 3600)
    expect(second.credential).not.toBe(ice.credential)
  })

  it('fails closed for invalid or ambiguous configuration', () => {
    for (const ttl of ['0', '599', '86401', 'NaN', '1.5']) {
      expect(() => rtcConfigurationProvider({ ...env, MESHBOARD_TURN_TTL_SECONDS: ttl })).toThrow('lifetime')
    }
    expect(() => rtcConfigurationProvider({ ...env, MESHBOARD_TURN_SECRET: 'changeme' })).toThrow('64 hex')
    expect(() => rtcConfigurationProvider({ ...env, MESHBOARD_TURN_USERNAME: 'static' })).toThrow('not both')
    expect(() => rtcConfigurationProvider({ MESHBOARD_TURN_SECRET: secret }, true)).toThrow('Configure TURN')
  })

  it('preserves local development and explicitly configured static external TURN', () => {
    expect(rtcConfigurationProvider({}, true)().localDevelopment).toBe(true)
    const config = rtcConfigurationProvider({ MESHBOARD_TURN_URLS: 'turn:turn.example.com:3478', MESHBOARD_TURN_USERNAME: 'user', MESHBOARD_TURN_CREDENTIAL: 'pass' })()
    expect(config.iceServers[0].username).toBe('user')
  })
})
