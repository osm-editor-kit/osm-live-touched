# @osm-editor-kit/live-touched

## 1.0.0

### Major Changes

- 728147c: First stable release. The package offers an editor-agnostic session for "live touched": `createLiveTouchedSession` syncs the objects a mapper has changed locally (diff-based, debounced), polls the current map view for objects other mappers are editing, and returns ready-to-render items with a status (`touched`, `stale`, `saved`) and a conflict hint (`parallel`, `outdated`, `ok`, `none`). It has no UI, plus `LIVE_TOUCHED_BACKEND`, `PRIVACY_URL`, `CONSENT_VERSION`, the status helpers, and the tile helpers.

  Breaking changes (nothing else changes; `iD--radnetz-berlin` uses none of these and needs no change):
  - Removed the export `DEBOUNCE_MS`. The 2 s debounce stays, but it is internal now.
  - Removed the exports `POLL_MS` and `nextPollDelay`. The polling intervals stay, but they are internal now.
  - Removed the export `entryId`. The entry id format `<osm_uid>/<type letter><osm_id>` is documented in `docs/api.md`.
  - Removed the export `tilesForBbox`. Use `tagsForBbox` (tags for one entry) or `queryTagsForViewport` (tags for a viewport query).
  - Removed the export `KV_PROJECT`. Use `LIVE_TOUCHED_BACKEND.project`.

## 0.1.0

### Minor Changes

- 11b1e81: First release: editor-agnostic session API for "live touched" (diff-based sync, adaptive polling, status windows, conflict hints) on the key-value-db `live-touched` project. No UI; see the iD integration guide in the README.
