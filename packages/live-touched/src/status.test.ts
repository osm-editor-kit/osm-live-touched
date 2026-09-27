import { describe, expect, it } from 'vitest'
import { compareItems, hintFor, MINUTE, statusOf } from './status.js'

describe('statusOf', () => {
  it('follows the time windows', () => {
    expect(statusOf({ status: 'touched' }, 29 * MINUTE)).toBe('touched')
    expect(statusOf({ status: 'touched' }, 30 * MINUTE)).toBe('stale')
    expect(statusOf({ status: 'touched' }, 60 * MINUTE)).toBe('hidden')
    expect(statusOf({ status: 'saved' }, 119 * MINUTE)).toBe('saved')
    expect(statusOf({ status: 'saved' }, 120 * MINUTE)).toBe('hidden')
  })
})

describe('hintFor', () => {
  it('flags parallel edits and outdated versions', () => {
    expect(hintFor('touched', {}, { modifiedByMe: true })).toBe('parallel')
    expect(hintFor('stale', {}, { modifiedByMe: false })).toBe('none')
    expect(hintFor('saved', { newVersion: 4 }, { modifiedByMe: false, localVersion: 3 })).toBe(
      'outdated',
    )
    expect(hintFor('saved', { newVersion: 4 }, { modifiedByMe: true, localVersion: 4 })).toBe('ok')
    expect(hintFor('saved', { newVersion: 4 }, { modifiedByMe: false })).toBe('none')
  })
})

describe('compareItems', () => {
  it('sorts parallel, outdated, touched, stale, saved; newest first', () => {
    const items = [
      { name: 'saved', hint: 'ok', status: 'saved', ageMs: 1 },
      { name: 'stale', hint: 'none', status: 'stale', ageMs: 1 },
      { name: 'touched-old', hint: 'none', status: 'touched', ageMs: 9 },
      { name: 'touched-new', hint: 'none', status: 'touched', ageMs: 2 },
      { name: 'outdated', hint: 'outdated', status: 'saved', ageMs: 5 },
      { name: 'parallel', hint: 'parallel', status: 'touched', ageMs: 5 },
    ] as const
    expect([...items].sort(compareItems).map((i) => i.name)).toEqual([
      'parallel',
      'outdated',
      'touched-new',
      'touched-old',
      'stale',
      'saved',
    ])
  })
})
