import type { Hint, LiveTouchedData, Status } from './types.js'

export const MINUTE = 60_000
export const TOUCHED_MS = 30 * MINUTE
export const STALE_MS = 60 * MINUTE
export const SAVED_MS = 120 * MINUTE

/**
 * Status of another user's entry. `ageMs` is the time since the entry's last write
 * (`updated_at`) on the server clock: the last local change, or the save.
 */
export function statusOf(data: Pick<LiveTouchedData, 'status'>, ageMs: number): Status {
  if (data.status === 'saved') return ageMs < SAVED_MS ? 'saved' : 'hidden'
  if (ageMs < TOUCHED_MS) return 'touched'
  if (ageMs < STALE_MS) return 'stale'
  return 'hidden'
}

/**
 * Conflict hint for another user's entry, compared with the local editor state.
 * `localVersion` is the version the local editor has loaded (undefined if unknown).
 */
export function hintFor(
  status: Exclude<Status, 'hidden'>,
  remote: { newVersion?: number },
  local: { modifiedByMe: boolean; localVersion?: number },
): Hint {
  if (status === 'saved') {
    if (remote.newVersion === undefined || local.localVersion === undefined) return 'none'
    return local.localVersion < remote.newVersion ? 'outdated' : 'ok'
  }
  return local.modifiedByMe ? 'parallel' : 'none'
}

const HINT_RANK: Record<Hint, number> = { parallel: 0, outdated: 1, ok: 2, none: 2 }
const STATUS_RANK: Record<Exclude<Status, 'hidden'>, number> = { touched: 0, stale: 1, saved: 2 }

/** Sort: parallel, outdated, then touched, stale, saved; newest first within a group. */
export function compareItems(
  a: { hint: Hint; status: Exclude<Status, 'hidden'>; ageMs: number },
  b: { hint: Hint; status: Exclude<Status, 'hidden'>; ageMs: number },
): number {
  return (
    HINT_RANK[a.hint] - HINT_RANK[b.hint] ||
    STATUS_RANK[a.status] - STATUS_RANK[b.status] ||
    a.ageMs - b.ageMs
  )
}
