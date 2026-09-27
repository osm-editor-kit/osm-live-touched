// Public entry of @osm-editor-kit/live-touched. See ../README.md and ../../../docs/concept.md.

export { createLiveTouchedSession, DEBOUNCE_MS } from './session.js'
export type { LiveTouchedOptions, LiveTouchedSession, Visibility } from './session.js'
export { compareItems, hintFor, statusOf, SAVED_MS, STALE_MS, TOUCHED_MS } from './status.js'
export { nextPollDelay, POLL_MS } from './poller.js'
export { bboxIntersects, queryTagsForViewport, tagsForBbox, tilesForBbox } from './tiles.js'
export { entryId } from './sync.js'
export type {
  Bbox,
  Hint,
  LiveItem,
  LiveState,
  LiveTouchedData,
  LocalObject,
  OsmType,
  OsmUser,
  Status,
} from './types.js'

/** Name of the key-value-db project this package talks to. */
export const KV_PROJECT = 'live-touched'

/** Production backend. The API key is public by design; it only selects the project. */
export const LIVE_TOUCHED_BACKEND = {
  baseUrl: 'https://key-value-store.fixmycity.workers.dev',
  project: KV_PROJECT,
  apiKey: 'kv_825ada0b89b3d63f541f5fd155dca104c2c0a229',
} as const

export const PRIVACY_URL = 'https://github.com/osm-editor-kit/osm-live-touched/blob/main/PRIVACY.md'

/** Bump when the consent text or privacy statement changes; editors ask again. */
export const CONSENT_VERSION = 1
