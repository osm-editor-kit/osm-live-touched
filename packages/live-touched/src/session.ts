import {
  createKvClient,
  KvError,
  type KvClient,
  type KvEntry,
} from '@osm-editor-kit/key-value-db-client'
import { nextPollDelay, type PollState } from './poller.js'
import { compareItems, hintFor, statusOf } from './status.js'
import { chunkOps, SyncState, type SyncOps } from './sync.js'
import { bboxIntersects, queryTagsForViewport } from './tiles.js'
import {
  TYPE_LETTER,
  type Bbox,
  type LiveItem,
  type LiveState,
  type LiveTouchedData,
  type LocalObject,
  type OsmType,
} from './types.js'

export const DEBOUNCE_MS = 2_000
const VIEWPORT_POLL_DELAY_MS = 500

type Timers = {
  setTimeout: (fn: () => void, ms: number) => unknown
  clearTimeout: (handle: unknown) => void
}

/** Page visibility, so polling pauses in background tabs. Defaults to `document`. */
export type Visibility = {
  isHidden(): boolean
  onChange(listener: () => void): () => void
}

export type LiveTouchedOptions = {
  baseUrl: string
  project: string
  apiKey: string
  getOsmToken: () => string | null | Promise<string | null>
  /** Goes into `data.editor`, e.g. "iD 2.43". */
  editor: string
  /** Version of an object the local editor has loaded, for the `outdated` / `ok` hints. */
  getLocalVersion?: (type: OsmType, id: number) => number | undefined
  /** For tests. */
  client?: KvClient<LiveTouchedData>
  timers?: Timers
  now?: () => number
  visibility?: Visibility | null
}

export type LiveTouchedSession = {
  enable(): Promise<void>
  disable(): Promise<void>
  /** Full set of locally modified objects (replaces the previous set). */
  setModified(objects: LocalObject[]): void
  /** After a successful upload. */
  markSaved(changesetId?: number): Promise<void>
  setViewport(bbox: Bbox): void
  /** Deletes all my entries in this project on the server and turns the feature off. */
  nukeMyData(): Promise<{ deleted: number }>
  getState(): LiveState
  subscribe(listener: (state: LiveState) => void): () => void
  /** Stops timers and listeners without touching the server. */
  destroy(): void
}

function defaultVisibility(): Visibility | null {
  if (typeof document === 'undefined') return null
  return {
    isHidden: () => document.visibilityState === 'hidden',
    onChange(listener) {
      document.addEventListener('visibilitychange', listener)
      return () => document.removeEventListener('visibilitychange', listener)
    },
  }
}

function isLiveTouchedData(v: unknown): v is LiveTouchedData {
  if (typeof v !== 'object' || v === null) return false
  const d = v as Record<string, unknown>
  return (
    d.schema === 1 &&
    (d.osm_type === 'node' || d.osm_type === 'way' || d.osm_type === 'relation') &&
    typeof d.osm_id === 'number' &&
    typeof d.base_version === 'number' &&
    (d.status === 'touched' || d.status === 'saved') &&
    Array.isArray(d.bbox) &&
    d.bbox.length === 4 &&
    d.bbox.every((n) => typeof n === 'number')
  )
}

export function createLiveTouchedSession(options: LiveTouchedOptions): LiveTouchedSession {
  const client =
    options.client ??
    createKvClient<LiveTouchedData>({
      baseUrl: options.baseUrl,
      project: options.project,
      apiKey: options.apiKey,
      getOsmToken: options.getOsmToken,
    })
  const timers: Timers = options.timers ?? {
    setTimeout: (fn, ms) => globalThis.setTimeout(fn, ms),
    clearTimeout: (h) => globalThis.clearTimeout(h as ReturnType<typeof setTimeout>),
  }
  const now = options.now ?? (() => Date.now())
  const visibility = options.visibility === undefined ? defaultVisibility() : options.visibility

  let state: LiveState = { enabled: false, zoomedOutTooFar: false, othersNearby: false, items: [] }
  const listeners = new Set<(s: LiveState) => void>()

  let uid: number | null = null
  let sync: SyncState | null = null
  let modified: LocalObject[] = []
  let viewport: Bbox | null = null
  /** Server time minus local time, learned from our own writes' `updated_at`. */
  let skewMs = 0
  let poll: PollState = { othersNearby: false, emptyStreak: 0, errorStreak: 0 }
  let pollTimer: unknown = null
  let flushTimer: unknown = null
  let polling = false
  /** Writes run one after the other. */
  let writeChain: Promise<void> = Promise.resolve()
  let unsubscribeVisibility: (() => void) | null = null

  const serverNow = () => now() + skewMs

  function setState(patch: Partial<LiveState>) {
    state = { ...state, ...patch }
    for (const listener of listeners) listener(state)
  }

  function errorOf(err: unknown) {
    if (err instanceof KvError) return { code: err.code, message: err.message }
    return { code: 'network', message: err instanceof Error ? err.message : String(err) }
  }

  // --- writes ------------------------------------------------------------------

  async function write(ops: SyncOps) {
    if (!sync) return
    for (const batch of chunkOps(ops)) {
      const sentAt = now()
      const result = await client.batch(batch)
      const newest = result.put.at(-1)
      if (newest) skewMs = Date.parse(newest.updated_at) - Math.round((sentAt + now()) / 2)
      sync.commit(batch)
    }
  }

  function enqueue(makeOps: () => SyncOps | null): Promise<void> {
    const run = writeChain.then(async () => {
      const ops = makeOps()
      if (!ops || (ops.put.length === 0 && ops.delete.length === 0)) return
      try {
        await write(ops)
        if (state.error) setState({ error: undefined })
      } catch (err) {
        setState({ error: errorOf(err) })
      }
    })
    writeChain = run
    return run
  }

  function flushModified() {
    return enqueue(() => (sync ? sync.plan(modified, new Date(serverNow()).toISOString()) : null))
  }

  function scheduleFlush() {
    if (!state.enabled) return
    if (flushTimer !== null) timers.clearTimeout(flushTimer)
    flushTimer = timers.setTimeout(() => {
      flushTimer = null
      void flushModified()
    }, DEBOUNCE_MS)
  }

  // --- polling -----------------------------------------------------------------

  function stopPolling() {
    if (pollTimer !== null) timers.clearTimeout(pollTimer)
    pollTimer = null
  }

  function schedulePoll(ms: number) {
    stopPolling()
    if (!state.enabled || visibility?.isHidden()) return
    pollTimer = timers.setTimeout(() => {
      pollTimer = null
      void pollOnce()
    }, ms)
  }

  function toItem(entry: KvEntry<LiveTouchedData>, myKeys: Set<string>): LiveItem | null {
    if (!isLiveTouchedData(entry.data)) return null
    if (entry.created_by.osm_uid === uid) return null
    const d = entry.data
    if (viewport && !bboxIntersects(d.bbox, viewport)) return null
    const ageMs = Math.max(0, serverNow() - Date.parse(entry.updated_at))
    const status = statusOf(d, ageMs)
    if (status === 'hidden') return null
    const localVersion = options.getLocalVersion?.(d.osm_type, d.osm_id)
    const hint = hintFor(
      status,
      { newVersion: d.new_version },
      { modifiedByMe: myKeys.has(`${TYPE_LETTER[d.osm_type]}${d.osm_id}`), localVersion },
    )
    return {
      type: d.osm_type,
      id: d.osm_id,
      user: entry.created_by,
      status,
      ageMs,
      bbox: d.bbox,
      baseVersion: d.base_version,
      ...(d.new_version === undefined ? {} : { newVersion: d.new_version }),
      ...(d.changeset_id === undefined ? {} : { changesetId: d.changeset_id }),
      hint,
      ...(localVersion === undefined ? {} : { localVersion }),
    }
  }

  async function pollOnce() {
    if (!state.enabled || polling || !viewport) return
    const tags = queryTagsForViewport(viewport)
    if (!tags) {
      setState({ zoomedOutTooFar: true, items: [], othersNearby: false })
      return
    }
    polling = true
    try {
      const result = await client.list({ tags, match: 'any', limit: 500 })
      const myKeys = new Set(modified.map((o) => `${TYPE_LETTER[o.type]}${o.id}`))
      const items = result.items
        .map((e) => toItem(e, myKeys))
        .filter((i): i is LiveItem => i !== null)
        .sort(compareItems)
      const othersNearby = items.length > 0
      poll = {
        othersNearby,
        emptyStreak: othersNearby ? 0 : poll.emptyStreak + 1,
        errorStreak: 0,
      }
      setState({ items, othersNearby, zoomedOutTooFar: false, error: undefined })
    } catch (err) {
      poll = { ...poll, errorStreak: poll.errorStreak + 1 }
      setState({ error: errorOf(err) })
    } finally {
      polling = false
      schedulePoll(nextPollDelay(poll))
    }
  }

  function onVisibilityChange() {
    if (visibility?.isHidden()) stopPolling()
    else schedulePoll(0)
  }

  // --- public API --------------------------------------------------------------

  return {
    async enable() {
      if (state.enabled) return
      try {
        const me = await client.me()
        uid = me.user.osm_uid
      } catch (err) {
        setState({ error: errorOf(err) })
        throw err
      }
      sync = new SyncState(uid, options.editor)
      poll = { othersNearby: false, emptyStreak: 0, errorStreak: 0 }
      unsubscribeVisibility = visibility?.onChange(onVisibilityChange) ?? null
      setState({ enabled: true, error: undefined })
      void flushModified()
      schedulePoll(0)
    },

    async disable() {
      if (!state.enabled) return
      stopPolling()
      if (flushTimer !== null) timers.clearTimeout(flushTimer)
      flushTimer = null
      unsubscribeVisibility?.()
      unsubscribeVisibility = null
      await enqueue(() => sync?.planDisable() ?? null)
      sync = null
      setState({ enabled: false, items: [], othersNearby: false, zoomedOutTooFar: false })
    },

    setModified(objects) {
      modified = objects
      scheduleFlush()
    },

    async markSaved(changesetId) {
      if (!state.enabled) return
      if (flushTimer !== null) timers.clearTimeout(flushTimer)
      flushTimer = null
      // Send pending changes first, so every uploaded object is known before it becomes "saved".
      await flushModified()
      await enqueue(() => sync?.planSaved(changesetId, new Date(serverNow()).toISOString()) ?? null)
    },

    setViewport(bbox) {
      viewport = bbox
      const zoomedOutTooFar = queryTagsForViewport(bbox) === null
      if (zoomedOutTooFar !== state.zoomedOutTooFar) setState({ zoomedOutTooFar })
      if (state.enabled && !polling) schedulePoll(VIEWPORT_POLL_DELAY_MS)
    },

    async nukeMyData() {
      stopPolling()
      if (flushTimer !== null) timers.clearTimeout(flushTimer)
      flushTimer = null
      unsubscribeVisibility?.()
      unsubscribeVisibility = null
      await writeChain
      const result = await client.removeMine()
      await client.forget()
      sync?.reset()
      sync = null
      setState({ enabled: false, items: [], othersNearby: false, zoomedOutTooFar: false })
      return result
    },

    getState: () => state,

    subscribe(listener) {
      listeners.add(listener)
      return () => listeners.delete(listener)
    },

    destroy() {
      stopPolling()
      if (flushTimer !== null) timers.clearTimeout(flushTimer)
      flushTimer = null
      unsubscribeVisibility?.()
      unsubscribeVisibility = null
      listeners.clear()
    },
  }
}
