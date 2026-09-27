import { roundBbox, tagsForBbox } from './tiles.js'
import { TYPE_LETTER, type LiveTouchedData, type LocalObject } from './types.js'

export type PutOp = { id: string; data: LiveTouchedData; tags: string[] }
export type SyncOps = { put: PutOp[]; delete: string[] }

export const MAX_BATCH_PUTS = 25
export const MAX_BATCH_DELETES = 50

type Sent = {
  status: 'touched' | 'saved'
  fingerprint: string
  data: LiveTouchedData
  tags: string[]
}

export function entryId(uid: number, type: LocalObject['type'], id: number): string {
  return `${uid}/${TYPE_LETTER[type]}${id}`
}

function isShareable(o: LocalObject): boolean {
  return (
    Number.isInteger(o.id) &&
    o.id > 0 &&
    Number.isInteger(o.baseVersion) &&
    o.baseVersion > 0 &&
    o.bbox.every((n) => Number.isFinite(n))
  )
}

/**
 * Diff-based writer. The editor reports the full set of locally modified objects; the sync
 * compares it with what was written before. New or changed objects are put as `touched`,
 * touched objects that left the set (undo, discard) are deleted. Saved entries stay until
 * the server TTL removes them.
 */
export class SyncState {
  private sent = new Map<string, Sent>()

  constructor(
    private readonly uid: number,
    private readonly editor: string,
  ) {}

  plan(objects: LocalObject[], nowIso: string): SyncOps {
    const put: PutOp[] = []
    const current = new Set<string>()
    for (const o of objects) {
      if (!isShareable(o)) continue
      const bbox = roundBbox(o.bbox)
      const tags = tagsForBbox(bbox)
      if (tags.length === 0) continue
      const id = entryId(this.uid, o.type, o.id)
      current.add(id)
      const fingerprint = JSON.stringify([o.baseVersion, bbox])
      const prev = this.sent.get(id)
      if (prev?.status === 'touched' && prev.fingerprint === fingerprint) continue
      put.push({
        id,
        tags,
        data: {
          schema: 1,
          osm_type: o.type,
          osm_id: o.id,
          bbox,
          base_version: o.baseVersion,
          status: 'touched',
          touched_at: nowIso,
          editor: this.editor,
        },
      })
    }
    const del = [...this.sent]
      .filter(([id, s]) => s.status === 'touched' && !current.has(id))
      .map(([id]) => id)
    return { put, delete: del }
  }

  /** After a successful upload: every touched entry becomes `saved` with version + 1. */
  planSaved(changesetId: number | undefined, nowIso: string): SyncOps {
    const put: PutOp[] = []
    for (const [id, s] of this.sent) {
      if (s.status !== 'touched') continue
      put.push({
        id,
        tags: s.tags,
        data: {
          ...s.data,
          status: 'saved',
          new_version: s.data.base_version + 1,
          ...(changesetId === undefined ? {} : { changeset_id: changesetId }),
          touched_at: nowIso,
        },
      })
    }
    return { put, delete: [] }
  }

  /** On disable: remove the entries that still say "touched". */
  planDisable(): SyncOps {
    return {
      put: [],
      delete: [...this.sent].filter(([, s]) => s.status === 'touched').map(([id]) => id),
    }
  }

  /** Record ops the server accepted. */
  commit(ops: SyncOps) {
    for (const p of ops.put) {
      this.sent.set(p.id, {
        status: p.data.status,
        fingerprint: JSON.stringify([p.data.base_version, p.data.bbox]),
        data: p.data,
        tags: p.tags,
      })
    }
    for (const id of ops.delete) this.sent.delete(id)
  }

  /** Ids this client wrote and that are still live (for filtering and tests). */
  sentIds(): string[] {
    return [...this.sent.keys()]
  }

  reset() {
    this.sent.clear()
  }
}

/** Split ops into batches the API accepts (25 puts, 50 deletes). */
export function chunkOps(ops: SyncOps): SyncOps[] {
  const batches: SyncOps[] = []
  let p = 0
  let d = 0
  while (p < ops.put.length || d < ops.delete.length) {
    batches.push({
      put: ops.put.slice(p, p + MAX_BATCH_PUTS),
      delete: ops.delete.slice(d, d + MAX_BATCH_DELETES),
    })
    p += MAX_BATCH_PUTS
    d += MAX_BATCH_DELETES
  }
  return batches
}
