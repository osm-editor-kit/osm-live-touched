import type { KvClient, KvEntry } from '@osm-editor-kit/key-value-db-client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createLiveTouchedSession, DEBOUNCE_MS } from './session.js'
import type { LiveTouchedData, LocalObject } from './types.js'

const VIEW: [number, number, number, number] = [13.37, 52.51, 13.39, 52.52]
const way: LocalObject = {
  type: 'way',
  id: 789,
  baseVersion: 3,
  bbox: [13.3777, 52.5163, 13.378, 52.5165],
}

/** In-memory stand-in for the key-value-db API, shared by several fake users. */
function fakeServer() {
  const entries = new Map<string, KvEntry<LiveTouchedData>>()
  const names: Record<number, string> = { 1: 'alice', 2: 'bob' }
  function clientFor(uid: number): KvClient<LiveTouchedData> {
    const user = { osm_uid: uid, display_name: names[uid] ?? `u${uid}` }
    return {
      me: async () => ({ user, can_write: true }),
      async batch(ops) {
        const at = new Date().toISOString()
        const put = (ops.put ?? []).map((p) => {
          const prev = entries.get(p.id)
          const entry: KvEntry<LiveTouchedData> = {
            id: p.id,
            data: p.data,
            tags: p.tags ?? [],
            version: (prev?.version ?? 0) + 1,
            created_at: prev?.created_at ?? at,
            updated_at: at,
            created_by: prev?.created_by ?? user,
            updated_by: user,
            expires_at: null,
          }
          entries.set(p.id, entry)
          return entry
        })
        let deleted = 0
        for (const id of ops.delete ?? []) if (entries.delete(id)) deleted++
        return { put, deleted }
      },
      async list(params) {
        const tags = new Set(params?.tags ?? [])
        return {
          items: [...entries.values()].filter((e) => e.tags.some((t) => tags.has(t))),
          next_cursor: null,
        }
      },
      async removeMine() {
        let deleted = 0
        for (const [id, e] of entries)
          if (e.created_by.osm_uid === uid && entries.delete(id)) deleted++
        return { deleted }
      },
      forget: async () => {},
      get: async () => {
        throw new Error('unused')
      },
      put: async () => {
        throw new Error('unused')
      },
      remove: async () => {},
      tags: async () => ({ tags: [] }),
    }
  }
  return { entries, clientFor }
}

function session(
  server: ReturnType<typeof fakeServer>,
  uid: number,
  getLocalVersion?: () => number | undefined,
) {
  return createLiveTouchedSession({
    baseUrl: 'http://unused',
    project: 'live-touched',
    apiKey: 'kv_test',
    getOsmToken: () => 'token',
    editor: 'test',
    client: server.clientFor(uid),
    visibility: null,
    getLocalVersion,
  })
}

beforeEach(() => {
  vi.useFakeTimers()
  vi.setSystemTime(new Date('2026-09-27T10:00:00.000Z'))
})

afterEach(() => {
  vi.useRealTimers()
})

describe('session', () => {
  it('sends debounced changes, and another user sees them with a parallel hint', async () => {
    const server = fakeServer()
    const alice = session(server, 1)
    const bob = session(server, 2)
    await alice.enable()
    await bob.enable()
    alice.setViewport(VIEW)
    bob.setViewport(VIEW)

    alice.setModified([way])
    bob.setModified([way])
    expect(server.entries.size).toBe(0)
    await vi.advanceTimersByTimeAsync(DEBOUNCE_MS + 10)
    expect([...server.entries.keys()].sort()).toEqual(['1/w789', '2/w789'])

    await vi.advanceTimersByTimeAsync(8_000)
    const items = bob.getState().items
    expect(items).toHaveLength(1)
    expect(items[0]).toMatchObject({ type: 'way', id: 789, status: 'touched', hint: 'parallel' })
    expect(items[0]!.user.display_name).toBe('alice')
    expect(bob.getState().othersNearby).toBe(true)
    alice.destroy()
    bob.destroy()
  })

  it('undo deletes the entry; save marks it saved; the other user gets the outdated hint', async () => {
    const server = fakeServer()
    const alice = session(server, 1)
    const bob = session(server, 2, () => 3)
    await alice.enable()
    await bob.enable()
    bob.setViewport(VIEW)

    alice.setModified([way])
    await vi.advanceTimersByTimeAsync(DEBOUNCE_MS + 10)
    alice.setModified([])
    await vi.advanceTimersByTimeAsync(DEBOUNCE_MS + 10)
    expect(server.entries.size).toBe(0)

    alice.setModified([way])
    await alice.markSaved(555)
    expect(server.entries.get('1/w789')!.data).toMatchObject({
      status: 'saved',
      new_version: 4,
      changeset_id: 555,
    })

    await vi.advanceTimersByTimeAsync(8_000)
    expect(bob.getState().items[0]).toMatchObject({
      status: 'saved',
      hint: 'outdated',
      localVersion: 3,
      newVersion: 4,
    })
    alice.destroy()
    bob.destroy()
  })

  it('reports zoomed out and stops querying', async () => {
    const server = fakeServer()
    const s = session(server, 1)
    await s.enable()
    s.setViewport([13.0, 52.3, 13.8, 52.7])
    expect(s.getState().zoomedOutTooFar).toBe(true)
    s.destroy()
  })

  it('disable removes touched entries; nuke removes everything of mine', async () => {
    const server = fakeServer()
    const alice = session(server, 1)
    const bob = session(server, 2)
    await alice.enable()
    await bob.enable()
    bob.setModified([way])
    await vi.advanceTimersByTimeAsync(DEBOUNCE_MS + 10)

    alice.setModified([way, { ...way, id: 790 }])
    await vi.advanceTimersByTimeAsync(DEBOUNCE_MS + 10)
    await alice.markSaved()
    alice.setModified([{ ...way, id: 791 }])
    await vi.advanceTimersByTimeAsync(DEBOUNCE_MS + 10)
    await alice.disable()
    expect([...server.entries.keys()].sort()).toEqual(['1/w789', '1/w790', '2/w789'])

    // Re-enabling shares the still-modified w791 again.
    await alice.enable()
    await vi.advanceTimersByTimeAsync(10)
    expect(await alice.nukeMyData()).toEqual({ deleted: 3 })
    expect([...server.entries.keys()]).toEqual(['2/w789'])
    expect(alice.getState().enabled).toBe(false)
    bob.destroy()
  })
})
