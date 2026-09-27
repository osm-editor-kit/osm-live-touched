export type OsmType = 'node' | 'way' | 'relation'

/** [minLon, minLat, maxLon, maxLat] */
export type Bbox = [number, number, number, number]

/** An object the local user has changed, as reported by the editor. */
export type LocalObject = {
  type: OsmType
  /** Positive OSM id. New objects (negative ids) are ignored. */
  id: number
  /** The version the user is editing on. */
  baseVersion: number
  bbox: Bbox
}

/** The `data` of a key-value-db entry in the `live-touched` project. */
export type LiveTouchedData = {
  schema: 1
  osm_type: OsmType
  osm_id: number
  bbox: Bbox
  base_version: number
  status: 'touched' | 'saved'
  new_version?: number
  changeset_id?: number
  touched_at: string
  editor: string
}

export type Status = 'touched' | 'stale' | 'saved' | 'hidden'

/**
 * - `parallel`: someone else is touching an object I have also changed.
 * - `outdated`: someone saved a newer version than the one I have loaded.
 * - `ok`: someone saved, and my version is current.
 * - `none`: no hint.
 */
export type Hint = 'parallel' | 'outdated' | 'ok' | 'none'

export type OsmUser = { osm_uid: number; display_name: string }

/** One object of another user, ready for the editor's list and map. */
export type LiveItem = {
  type: OsmType
  id: number
  user: OsmUser
  status: Exclude<Status, 'hidden'>
  /** Milliseconds since the other user's last change or save (server clock). */
  ageMs: number
  bbox: Bbox
  baseVersion: number
  newVersion?: number
  changesetId?: number
  hint: Hint
  /** The version the local editor has loaded, if known. */
  localVersion?: number
}

export type LiveState = {
  enabled: boolean
  /** The map view is too large to query; the UI should ask the user to zoom in. */
  zoomedOutTooFar: boolean
  /** At least one other user's object is in view (drives the pulsing indicator). */
  othersNearby: boolean
  items: LiveItem[]
  error?: { code: string; message: string }
}

export const TYPE_LETTER: Record<OsmType, string> = { node: 'n', way: 'w', relation: 'r' }
