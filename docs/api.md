# How live-touched uses key-value-db

The backend is [key-value-db](https://github.com/FixMyBerlin/key-value-db), a shared API for OSM-authenticated apps. Its full reference is [`docs/API.md`](https://github.com/FixMyBerlin/key-value-db/blob/main/docs/API.md) in that repo. This file only describes what live-touched uses.

## Project

| | Value |
| --- | --- |
| Base URL | `https://key-value-store.fixmycity.workers.dev` ([health](https://key-value-store.fixmycity.workers.dev/v1/health)) |
| Project | `live-touched` |
| API key | `kv_825ada0b89b3d63f541f5fd155dca104c2c0a229` (public; it only selects the project) |
| Origins | `http://127.0.0.1:*`, `https://fixmyberlin.github.io` (the admin adds more) |
| Settings | `read_access = osm_user`, `write_scope = owner`, `entry_ttl_s = 10800` |

Every request sends `X-Api-Key`, the browser's `Origin`, and `Authorization: Bearer <OSM OAuth2 token>`.

## Entry

**id**: `<osm_uid>/<type-letter><osm_id>`, e.g. `123456/w789`. The server only accepts ids that start with the writer's own uid.

**data**:

```ts
type LiveTouchedData = {
  schema: 1
  osm_type: 'node' | 'way' | 'relation'
  osm_id: number // positive; new objects are not sent
  bbox: [number, number, number, number] // minLon, minLat, maxLon, maxLat; 6 decimals
  base_version: number // version the user is editing on
  status: 'touched' | 'saved'
  new_version?: number // with status "saved"
  changeset_id?: number // with status "saved", if known
  touched_at: string // client ISO time of the last local change
  editor: string // e.g. "iD 2.43"
}
```

No tags, names, or geometry beyond the bbox.

**tags** (tile index): the slippy-map tiles that intersect the bbox, as `z14/x/y`, when that is at most 16 tiles. Otherwise the covering `z10/x/y` tiles (at most 16). If the object is larger than that, it is not sent.

## Requests

| Purpose | Request |
| --- | --- |
| Who am I | `GET /v1/projects/live-touched/me` |
| Poll the map view | `GET …/entries?tag=z14/…&tag=z10/…&match=any&limit=500` (view tiles at z14 plus their z10 parents) |
| Send changes | `POST …/batch` with `{ put: [...], delete: [...] }` (max 25 puts and 50 deletes per call) |
| Mark saved | the same batch, with `status: 'saved'`, `new_version`, `changeset_id` |
| Delete my data | `DELETE …/me/entries`, then `DELETE …/me` |

The server hides an entry 3 h after its first write and deletes it within 15 min. Updates do not extend that time.

## Client

The package uses the key-value-db client (`packages/kv-client` in that repo) and does not copy it.
