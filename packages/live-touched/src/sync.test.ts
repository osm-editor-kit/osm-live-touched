import { describe, expect, it } from 'vitest'
import { chunkOps, SyncState } from './sync.js'
import type { LocalObject } from './types.js'

const way: LocalObject = {
  type: 'way',
  id: 789,
  baseVersion: 3,
  bbox: [13.3777, 52.5163, 13.378, 52.5165],
}
const node: LocalObject = {
  type: 'node',
  id: 1,
  baseVersion: 1,
  bbox: [13.3777, 52.5163, 13.3777, 52.5163],
}
const NOW = '2026-09-27T10:00:00.000Z'

describe('SyncState', () => {
  it('puts new objects, skips unchanged ones, deletes removed ones', () => {
    const sync = new SyncState(42, 'test')
    const first = sync.plan([way, node], NOW)
    expect(first.put.map((p) => p.id)).toEqual(['42/w789', '42/n1'])
    expect(first.put[0]!.data).toMatchObject({
      status: 'touched',
      base_version: 3,
      osm_type: 'way',
      schema: 1,
    })
    expect(first.put[0]!.tags).toEqual(['z14/8800/5373'])
    sync.commit(first)

    expect(sync.plan([way, node], NOW)).toEqual({ put: [], delete: [] })

    const moved = { ...way, bbox: [13.4, 52.5163, 13.41, 52.5165] as LocalObject['bbox'] }
    const second = sync.plan([moved], NOW)
    expect(second.put.map((p) => p.id)).toEqual(['42/w789'])
    expect(second.delete).toEqual(['42/n1'])
  })

  it('ignores new objects and invalid input', () => {
    const sync = new SyncState(42, 'test')
    const ops = sync.plan(
      [
        { ...node, id: -5 },
        { ...node, baseVersion: 0 },
      ],
      NOW,
    )
    expect(ops).toEqual({ put: [], delete: [] })
  })

  it('marks touched entries saved and keeps them when the modified set empties', () => {
    const sync = new SyncState(42, 'test')
    sync.commit(sync.plan([way], NOW))
    const saved = sync.planSaved(1234, NOW)
    expect(saved.put[0]!.data).toMatchObject({
      status: 'saved',
      new_version: 4,
      changeset_id: 1234,
    })
    sync.commit(saved)
    expect(sync.plan([], NOW)).toEqual({ put: [], delete: [] })
    expect(sync.planDisable()).toEqual({ put: [], delete: [] })
  })

  it('a saved object edited again becomes touched', () => {
    const sync = new SyncState(42, 'test')
    sync.commit(sync.plan([way], NOW))
    sync.commit(sync.planSaved(undefined, NOW))
    const again = sync.plan([{ ...way, baseVersion: 4 }], NOW)
    expect(again.put[0]!.data).toMatchObject({ status: 'touched', base_version: 4 })
  })

  it('disable deletes only touched entries', () => {
    const sync = new SyncState(42, 'test')
    sync.commit(sync.plan([way, node], NOW))
    expect(sync.planDisable().delete.sort()).toEqual(['42/n1', '42/w789'])
  })
})

describe('chunkOps', () => {
  it('splits into API-sized batches', () => {
    const put = Array.from({ length: 30 }, (_, i) => ({ id: `p${i}`, data: {} as never, tags: [] }))
    const del = Array.from({ length: 60 }, (_, i) => `d${i}`)
    const batches = chunkOps({ put, delete: del })
    expect(batches.map((b) => [b.put.length, b.delete.length])).toEqual([
      [25, 50],
      [5, 10],
    ])
  })
})
