import { test, expect } from 'vitest'
import { elementSchema } from '@meshboard/shared-protocol'
import fixtures from '../../shared-protocol/fixtures/text-elements.json'
import { textElement, textLines } from './textElements'
import { hitsElement } from './board'

test('text schemas and wrapping match native fixtures', () => {
  for (const value of fixtures.valid) expect(elementSchema.parse(value)).toEqual(value)
  for (const value of fixtures.invalid) expect(elementSchema.safeParse(value).success).toBe(false)
  expect(elementSchema.safeParse({ ...fixtures.valid[0], text: '\ud800' }).success).toBe(false)
  for (const value of fixtures.layout) expect(textLines(value.text)).toEqual(value.lines)
})
test('notes size to their content and erase through their interior', () => {
  const note = textElement('note', 'sticky', { x: 10, y: 20 }, '#293b36', 'a\nb\nc')
  expect(note.points[1]).toEqual({ x: 298, y: 116 })
  expect(hitsElement(note, { x: 50, y: 50 }, { x: 50, y: 50 }, 0)).toBe(true)
  expect(hitsElement(note, { x: -20, y: 50 }, { x: 320, y: 50 }, 0)).toBe(true)
  expect(hitsElement(note, { x: -20, y: -20 }, { x: -10, y: -10 }, 0)).toBe(false)
})
