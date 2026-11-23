import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { browserProblem } from './browserSupport'

beforeEach(() => {
  vi.stubGlobal('isSecureContext', true)
  vi.stubGlobal('crypto', { getRandomValues() {}, randomUUID() {}, subtle: {} })
  for (const name of ['RTCPeerConnection', 'RTCDataChannel', 'WebSocket', 'PointerEvent',
    'SVGSVGElement', 'TextEncoder', 'TextDecoder', 'matchMedia', 'createImageBitmap', 'FileReader']) {
    vi.stubGlobal(name, function () {})
  }
})
afterEach(() => vi.unstubAllGlobals())

it('accepts the required capabilities without inspecting browser identity', () => {
  expect(browserProblem()).toBeNull()
})
it('explains insecure LAN HTTP before trying to access crypto', () => {
  vi.stubGlobal('isSecureContext', false)
  vi.stubGlobal('crypto', undefined)
  expect(browserProblem()).toContain('HTTPS')
})
it.each(['getRandomValues', 'randomUUID', 'subtle'])('blocks missing crypto %s', key => {
  vi.stubGlobal('crypto', { ...crypto, [key]: undefined })
  expect(browserProblem()).toContain('encryption')
})
it.each(['RTCPeerConnection', 'RTCDataChannel', 'WebSocket'])('blocks missing transport %s', key => {
  vi.stubGlobal(key, undefined)
  expect(browserProblem()).toContain('connect')
})
it.each(['PointerEvent', 'SVGSVGElement', 'TextEncoder', 'TextDecoder', 'matchMedia',
  'createImageBitmap', 'FileReader', 'File', 'Blob', 'URLSearchParams'])('blocks missing drawing/file API %s', key => {
  vi.stubGlobal(key, undefined)
  expect(browserProblem()).toContain('drawing or file')
})
it('blocks browsers whose URLSearchParams cannot validate invite entry counts', () => {
  vi.stubGlobal('URLSearchParams', class {})
  expect(browserProblem()).toContain('drawing or file')
})
