---
'@osm-editor-kit/live-touched': major
---

First stable release. The package offers an editor-agnostic session for "live touched": `createLiveTouchedSession` syncs the objects a mapper has changed locally (diff-based, debounced), polls the current map view for objects other mappers are editing, and returns ready-to-render items with a status (`touched`, `stale`, `saved`) and a conflict hint (`parallel`, `outdated`, `ok`, `none`). It has no UI, plus `LIVE_TOUCHED_BACKEND`, `PRIVACY_URL`, `CONSENT_VERSION`, the status helpers, and the tile helpers.

Breaking changes (nothing else changes; `iD--radnetz-berlin` uses none of these and needs no change):

- Removed the export `DEBOUNCE_MS`. The 2 s debounce stays, but it is internal now.
- Removed the exports `POLL_MS` and `nextPollDelay`. The polling intervals stay, but they are internal now.
- Removed the export `entryId`. The entry id format `<osm_uid>/<type letter><osm_id>` is documented in `docs/api.md`.
- Removed the export `tilesForBbox`. Use `tagsForBbox` (tags for one entry) or `queryTagsForViewport` (tags for a viewport query).
- Removed the export `KV_PROJECT`. Use `LIVE_TOUCHED_BACKEND.project`.
