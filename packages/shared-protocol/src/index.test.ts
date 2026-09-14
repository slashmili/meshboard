import { describe, expect, it } from 'vitest'
import { boardMessageSchema, clientSignalSchema, elementSchema } from './index'
import fixtures from '../fixtures/board-messages.json'

describe('shared TypeScript/Kotlin fixtures', () => {
  for (const [index, message] of fixtures.valid.entries()) {
    it(`accepts valid fixture ${index}`, () => expect(boardMessageSchema.safeParse(message).success).toBe(true))
  }
  for (const [index, message] of fixtures.invalid.entries()) {
    it(`rejects invalid fixture ${index}`, () => expect(boardMessageSchema.safeParse(message).success).toBe(false))
  }
})

describe('protocol boundaries', () => {
  const element = { id: 'shape-1', type: 'pen', color: '#293b36', width: 3, points: [{ x: 1, y: 2 }] }
  it('accepts drawing data only on the board protocol', () => {
    const message = { v: 1, type: 'put', element }
    expect(boardMessageSchema.safeParse(message).success).toBe(true)
    expect(clientSignalSchema.safeParse(message).success).toBe(false)
  })
  it('rejects extra payloads smuggled into a signaling envelope', () => {
    expect(clientSignalSchema.safeParse({ v: 1, type: 'join', room: '123e4567-e89b-42d3-a456-426614174000', element }).success).toBe(false)
    expect(clientSignalSchema.safeParse({ v: 1, type: 'signal', to: '123e4567-e89b-42d3-a456-426614174000', payload: { description: { type: 'offer', sdp: 'v=0\r\n', element } } }).success).toBe(false)
  })
  it('rejects nonfinite coordinates, unknown shape types, and invalid colors', () => {
    for (const patch of [{ points: [{ x: Infinity, y: 0 }] }, { type: 'script' }, { color: 'url(https://example.org)' }, { type: 'rectangle' }]) {
      expect(elementSchema.safeParse({ ...element, ...patch }).success).toBe(false)
    }
  })
})
