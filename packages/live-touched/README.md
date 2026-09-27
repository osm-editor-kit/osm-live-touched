# @osm-editor-kit/live-touched

Editor-agnostic wiring for "live touched": mappers see which OSM objects other mappers are editing near them, **before** those edits are uploaded.

The package contains the API client usage, the diff-based sync, adaptive polling, status windows, and conflict hints. It has **no UI and no editor code**: each editor (iD, Rapid, …) hooks its events into the session and draws its own map highlight, panel, indicator, and consent dialog. The UI rules and reference texts (English and German) are in [docs/concept.md](../../docs/concept.md), and the privacy statement is in [PRIVACY.md](../../PRIVACY.md).

> Status: pre-release (not on npm yet). It uses the npm client [`@osm-editor-kit/key-value-db-client`](https://www.npmjs.com/package/@osm-editor-kit/key-value-db-client).

## API

```ts
import { createLiveTouchedSession, LIVE_TOUCHED_BACKEND } from '@osm-editor-kit/live-touched'

const session = createLiveTouchedSession({
  ...LIVE_TOUCHED_BACKEND, // baseUrl, project 'live-touched', public apiKey
  getOsmToken: () => token, // the user's OSM OAuth2 token
  editor: 'iD 2.43', // goes into the entry
  getLocalVersion: (type, id) => versionLoadedInEditor, // for the "outdated" / "ok" hints
})

// input from the editor
session.setModified(objects) // full set of modified objects: { type, id, baseVersion, bbox }
session.markSaved(changesetId) // after a successful upload
session.setViewport(bbox) // [minLon, minLat, maxLon, maxLat], on map move

// lifecycle
await session.enable() // after consent
await session.disable() // deletes my "touched" entries, stops
await session.nukeMyData() // → { deleted }: deletes all my entries on the server, turns off
session.destroy() // stop timers, no server calls

// output
session.subscribe((state) => render(state))
// state: { enabled, zoomedOutTooFar, othersNearby, items: LiveItem[], error? }
// LiveItem: { type, id, user, status, ageMs, bbox, baseVersion, newVersion?, changesetId?, hint, localVersion? }
```

- `status` is `touched`, `stale`, or `saved`. `hint` is `parallel`, `outdated`, `ok`, or `none`. `items` are already sorted: conflicts first, then by status, newest first.
- The package has no texts. Map `status` and `hint` to your editor's i18n with the keys in [docs/concept.md](../../docs/concept.md).
- Also exported: `PRIVACY_URL`, `CONSENT_VERSION` (store it with the user's consent, and ask again when it changes), and the pure helpers `statusOf`, `hintFor`, `compareItems`, `tagsForBbox`, `queryTagsForViewport`, and `bboxIntersects`.

Behavior (details in [docs/concept.md](../../docs/concept.md)):

- `setModified` is debounced for 2 s, then only the differences are sent: new or changed objects are written, and objects that left the set (undo, discard) are deleted. Saved entries stay until the server removes them (3 h).
- Polling runs every 8 s, every 4 s while others are in view, and every 10 s after 3 empty answers. It backs off on errors, pauses in background tabs, and stops when the view covers more than 36 z14 tiles (`zoomedOutTooFar`).

## iD integration guide

This is written for the iD--radnetz-berlin work (fork `tordans/iD`, iD 2.43). The frontend lives **inside iD**; the package only does the wiring. The names below were checked against the fork's source on 2026-09-27.

### 1. Dependency

In the iD worktree's `package.json`, during development:

```json
"@osm-editor-kit/live-touched": "file:../osm-live-touched/packages/live-touched"
```

Run `bun run build` (or `bun run --filter @osm-editor-kit/live-touched build`) in osm-live-touched after changes, then rebuild iD. Once the package is on npm, switch to the version range.

### 2. Expose the OSM token

iD's OSM service keeps its `osmAuth` instance private (`modules/services/osm.js`, `var oauth = new osmAuth(...)`). osm-auth 3 has `oauth.getAccessToken()`. Add a getter to the service's public object:

```js
getAccessToken: function() {
    return oauth.getAccessToken();
},
```

The token goes to the key-value-db backend, which uses it only to read the user's id and name. `PRIVACY.md` says so.

### 3. Wiring (e.g. `modules/services/live_touched.js` or `modules/ui/live_touched/`)

```js
import { createLiveTouchedSession, LIVE_TOUCHED_BACKEND } from '@osm-editor-kit/live-touched';

export function setupLiveTouched(context) {
  const session = createLiveTouchedSession({
    ...LIVE_TOUCHED_BACKEND,
    getOsmToken: () => context.connection()?.getAccessToken() ?? null,
    editor: `iD ${context.version}`,
    getLocalVersion: (type, id) => {
      const entity = context.hasEntity(type[0] + id);
      return entity?.version === undefined ? undefined : Number(entity.version);
    },
  });

  // Modified objects: history.difference().summary() gives ways, relations, and tagged nodes
  // (untagged vertices are folded into their parent ways).
  function reportModified() {
    const graph = context.graph();
    const base = context.history().base();
    const objects = [];
    for (const { entity, changeType } of context.history().difference().summary()) {
      if (changeType === 'created') continue;            // new objects are not shared (v1)
      const osmId = Number(entity.id.slice(1));
      if (!(osmId > 0) || entity.version === undefined) continue;
      const g = changeType === 'deleted' ? base : graph;
      const extent = entity.extent(g);                   // geoExtent [[minLon, minLat], [maxLon, maxLat]]
      if (!extent) continue;
      objects.push({
        type: entity.type,                                // 'node' | 'way' | 'relation'
        id: osmId,
        baseVersion: Number(entity.version),
        bbox: [extent[0][0], extent[0][1], extent[1][0], extent[1][1]],
      });
    }
    session.setModified(objects);
  }
  context.history().on('change.liveTouched', reportModified);

  // Upload success: coreUploader dispatches 'resultSuccess' with the changeset
  context.uploader().on('resultSuccess.liveTouched', (changeset) => {
    session.markSaved(Number(changeset?.id) || undefined);
  });

  // Viewport
  function reportViewport() {
    const e = context.map().extent();
    session.setViewport([e[0][0], e[0][1], e[1][0], e[1][1]]);
  }
  context.map().on('move.liveTouched', reportViewport);

  // Highlight: iD gives SVG elements the entity id as class (utilEntitySelector → '.w789')
  let lastState = session.getState();
  session.subscribe((state) => { lastState = state; applyHighlight(); /* + render panel/indicator */ });
  context.map().on('drawn.liveTouched', applyHighlight);
  function applyHighlight() {
    const surface = context.surface();
    surface.selectAll('.live-touched').classed('live-touched live-touched-touched live-touched-stale live-touched-saved live-touched-conflict', false);
    for (const item of lastState.items) {
      surface.selectAll('.' + item.type[0] + item.id)
        .classed('live-touched', true)
        .classed(`live-touched-${item.status}`, true)
        .classed('live-touched-conflict', item.hint === 'parallel' || item.hint === 'outdated');
    }
  }

  return session;
}
```

Check while building (not verified yet):

- whether `change` also fires on undo, redo, and restore, so `reportModified` sees them (the diff sync handles them either way);
- the changeset object in `resultSuccess` (is `changeset.id` set after the upload?);
- that `.w789`-style classes hit the drawn lines, areas, and points, not only labels.

### 4. UI in iD

Follow [docs/concept.md](../../docs/concept.md):

- **Map**: CSS for `.live-touched-*` in iD's CSS (orange `touched`, faded `stale`, violet `saved`, red outline for conflicts), styled like the selection halo.
- **Panel**: a pane or sidebar section listing `state.items`. Clicking a row runs `context.enter(modeSelect(context, [type[0] + id]))` and zooms to it. Show "Zoom in to see live edits" when `state.zoomedOutTooFar`. The footer has the privacy link (`PRIVACY_URL`) and **"Delete my data on the server"** (confirm → `session.nukeMyData()` → show `deleted`).
- **Indicator**: a pulsing dot on the map-control button while `state.othersNearby`.
- **Consent**: iD modal on first enable. Store `CONSENT_VERSION` in `prefs('live-touched-consent')`, and only then call `session.enable()`.
- **Texts**: add the keys from `docs/concept.md` to `data/core.yaml` under `live_touched`.

### 5. Test locally

- Open iD at **`http://127.0.0.1:8080`**, not `localhost` (only `127.0.0.1:*` is on the project's origin list).
- Use two browser profiles with two OSM accounts, both in the same area at zoom ≥ 15. Edit in one; the other sees it within ~10 s.
- Against a local backend: run key-value-db `bun run dev-api`, create a local `live-touched` project, and pass `baseUrl: 'http://localhost:8787'` plus the local `apiKey` instead of `LIVE_TOUCHED_BACKEND`.

## License

[MIT](LICENSE)
