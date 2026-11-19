import { describe, expect, it } from 'vitest'
import { signalPayloadSchema, elementSchema } from './index'
import fixtures from '../fixtures/board-elements.json'

describe('shared TypeScript/Kotlin fixtures', () => {
  for (const [index, message] of fixtures.valid.entries()) {
    it(`accepts valid fixture ${index}`, () => expect(elementSchema.safeParse(message).success).toBe(true))
  }
  for (const [index, message] of fixtures.invalid.entries()) {
    it(`rejects invalid fixture ${index}`, () => expect(elementSchema.safeParse(message).success).toBe(false))
  }
})

describe('protocol boundaries', () => {
  const element = { id: 'shape-1', type: 'pen', color: '#293b36', width: 3, points: [{ x: 1, y: 2 }] }
  it('rejects drawing data on the signaling protocol', () => {
    const message = { v: 1, type: 'put', element }
    expect(elementSchema.safeParse(element).success).toBe(true)
    expect(signalPayloadSchema.safeParse(message).success).toBe(false)
  })
  it('rejects extra payloads smuggled into a signaling envelope', () => {
    expect(signalPayloadSchema.safeParse({ element }).success).toBe(false)
    expect(signalPayloadSchema.safeParse({ description: { type: 'offer', sdp: 'v=0\r\n', element } }).success).toBe(false)
  })
  it('rejects nonfinite coordinates, unknown shape types, and invalid colors', () => {
    for (const patch of [{ points: [{ x: Infinity, y: 0 }] }, { type: 'script' }, { color: 'url(https://example.org)' }, { type: 'rectangle' }]) {
      expect(elementSchema.safeParse({ ...element, ...patch }).success).toBe(false)
    }
  })
})

import images from '../fixtures/image-elements.json'
describe('embedded raster images', () => {
  for (const [i,element] of images.valid.entries()) it(`accepts image ${i}`, () => expect(elementSchema.safeParse(element).success).toBe(true))
  for (const [i,element] of images.invalid.entries()) it(`rejects image ${i}`, () => expect(elementSchema.safeParse(element).success).toBe(false))
  it('rejects oversized encoded images', () => expect(elementSchema.safeParse({...images.valid[0],image:'data:image/png;base64,'+'A'.repeat(700000)}).success).toBe(false))
})

import connectors from '../fixtures/connectors.json'
describe('connector attachments',()=>{
 for(const [i,e] of connectors.valid.entries()) it(`accepts connector ${i}`,()=>expect(elementSchema.safeParse(e).success).toBe(true))
 for(const [i,e] of connectors.invalid.entries()) it(`rejects connector ${i}`,()=>expect(elementSchema.safeParse(e).success).toBe(false))
})
