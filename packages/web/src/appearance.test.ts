import { expect, test } from 'vitest'
import fixture from '../../shared-protocol/fixtures/appearance.json'
import { CANVAS, MIN_DARK_CONTRAST, contrast, displayColor } from './appearance'

test('display colors match the shared cross-platform fixture', () => {
  expect(CANVAS).toEqual(fixture.canvas)
  expect(MIN_DARK_CONTRAST).toBe(fixture.minDarkContrast)
  for (const item of fixture.display) {
    expect(displayColor(item.color, false)).toBe(item.light)
    expect(displayColor(item.color, true)).toBe(item.dark)
  }
})

test('dark display colors are legible on the dark canvas and leave legible colors alone', () => {
  for (const item of fixture.display) {
    expect(contrast(displayColor(item.color, true), CANVAS.dark.paper)).toBeGreaterThanOrEqual(MIN_DARK_CONTRAST)
    if (contrast(item.color, CANVAS.dark.paper) >= MIN_DARK_CONTRAST) expect(displayColor(item.color, true)).toBe(item.color.toLowerCase())
  }
  expect(displayColor('not-a-color', true)).toBe('not-a-color')
})
