import { describe, expect, it } from 'vitest'
import { bboxIntersects, lonLatToTile, queryTagsForViewport, tagsForBbox } from './tiles.js'

describe('tiles', () => {
  it('computes slippy tiles', () => {
    // Berlin, Brandenburger Tor
    expect(lonLatToTile(13.3777, 52.5163, 14)).toEqual([8800, 5373])
  })

  it('tags a small object with z14 tiles', () => {
    expect(tagsForBbox([13.3777, 52.5163, 13.3777, 52.5163])).toEqual(['z14/8800/5373'])
  })

  it('falls back to z10 for large objects and to nothing for huge ones', () => {
    const tags = tagsForBbox([13.0, 52.3, 13.8, 52.7])
    expect(tags.length).toBeGreaterThan(0)
    expect(tags.every((t) => t.startsWith('z10/'))).toBe(true)
    expect(tagsForBbox([-10, 35, 30, 60])).toEqual([])
  })

  it('queries viewport tiles plus z10 parents, or null when zoomed out', () => {
    const tags = queryTagsForViewport([13.37, 52.51, 13.39, 52.52])!
    expect(tags).toContain('z14/8800/5373')
    expect(tags).toContain('z10/550/335')
    expect(queryTagsForViewport([13.0, 52.3, 13.8, 52.7])).toBeNull()
  })

  it('an object tagged at z10 is found by a z14 viewport query', () => {
    const objectTags = tagsForBbox([13.0, 52.3, 13.8, 52.7])
    const query = queryTagsForViewport([13.37, 52.51, 13.39, 52.52])!
    expect(objectTags.some((t) => query.includes(t))).toBe(true)
  })

  it('checks bbox intersection', () => {
    expect(bboxIntersects([0, 0, 1, 1], [1, 1, 2, 2])).toBe(true)
    expect(bboxIntersects([0, 0, 1, 1], [1.1, 0, 2, 1])).toBe(false)
  })
})
