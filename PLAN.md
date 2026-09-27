# osm-live-touched: implementation plan

Status: draft, 2026-09-27. This file supersedes the German handover plan as the working plan. It holds the goal, the repo layout, the review of `key-value-db` with the changes needed there, the TypeScript package design, the iD integration, and the JOSM plugin outline.

## 1. Goal

Mappers see **live** which OSM objects other mappers are editing near them, **before** those edits are uploaded. This helps avoid edit conflicts and shows what is happening in an area right now.

- Opt-in, off by default.
- Web editors (iD first, Rapid later) use **one npm package, `@osm-editor-kit/live-touched`**. It holds the editor-agnostic wiring: API client, sync, polling, state, and status logic. It has **no UI and no editor code**. Each editor builds its own UI and event hooks on top of it. For iD that code lives **inside iD** (Section 6).
- JOSM gets a Java plugin later. It follows the same spec in `docs/`.
- The backend is the existing **key-value-db** Worker ([FixMyBerlin/key-value-db](https://github.com/FixMyBerlin/key-value-db), local `/Users/tordans/Development/FMC/key-value-db`). This feature is a new **project** in it. We use its generic features plus a few small, generic additions (Section 4). We do not build a separate backend.
- Users can **delete all their data on the server** at any time from the editor UI ("Delete my data").

Order of work: key-value-db changes, then docs, then the TS package with iD integration. JOSM follows later.

## 2. Repo layout

```
osm-live-touched/
├── PLAN.md                       # this file
├── README.md                     # overview, links, how to develop
├── PRIVACY.md                    # privacy statement for this workflow; editors link to it (Section 7)
├── docs/
│   ├── concept.md                # flow, states, time windows, UI rules (editor-agnostic spec)
│   └── api.md                    # how we use key-value-db: project, entry shape, tags, requests
├── packages/                     # every artifact this repo ships, whatever the language
│   ├── live-touched/             # npm: @osm-editor-kit/live-touched (wiring only, no UI, no editor code)
│   │   ├── src/
│   │   └── test/
│   └── josm-plugin/              # JOSM plugin jar (Java, Gradle, built in Docker); later, M6
│       ├── package.json          # private; bun scripts that wrap the Docker Gradle build
│       ├── build.gradle.kts
│       └── src/main/java/…
├── .changeset/                   # changesets config (ignores the private josm-plugin)
├── package.json                  # bun workspaces: packages/*
└── .github/workflows/            # ci.yml (check + tests), release.yml (npm), josm.yml (later)
```

Decisions:

- **`packages/` holds every artifact the repo ships, not only JS.** The JOSM plugin sits next to the npm package as `packages/josm-plugin/`. It has a small **private** `package.json` (never published) whose scripts wrap the Docker Gradle build (`build`, `test`, `clean`). That way `bun run --filter '*' …` and CI treat it like any other package. The root `check` leaves out the Docker build, so a normal `bun run check` stays fast and needs no Docker. JOSM gets its own `josm-check` script and CI job. `packages` is the usual monorepo name even for mixed languages, so we keep it instead of inventing a new folder name.
- **npm scope and naming follow the existing `@osm-editor-kit` packages** (checked 2026-09-27 in `~/Development/OSM`). The scope is `@osm-editor-kit`, not `@editor-kit`. Names:
  - The older packages in `parking-lanes` use an `osm-` prefix (`osm-oauth`, `osm-changeset`, …).
  - The newer feature packages leave it out and name the feature, then a suffix for the part: `surface-smoothness-data`, `surface-smoothness-id-field`, `tree-taxonomy-data`, `street-imagery`.
  - The scope already says "OSM", so we follow the newer style: **`@osm-editor-kit/live-touched`**. The repo name stays `osm-live-touched` (like `osm-surface-smoothness-tagging`).
- **One package, wiring only.** The frontend (panel, indicator, highlight styles, consent dialog, texts) and the hooks into editor events are built **inside each editor**, in its own UI style and i18n. For iD that is the iD--radnetz-berlin worktree (Section 6). The package gives them a small, typed session API. There is no `live-touched-id` package.
- **Copy the setup from `osm-surface-smoothness-tagging`** (the closest existing iD integration):
  - bun workspaces, oxlint, oxfmt, vitest, TypeScript.
  - `tsc` builds ESM plus `.d.ts` to `dist/` (like `surface-smoothness-data`). No Vite, no IIFE bundle, and no sync script are needed, because iD bundles the package itself with esbuild.
  - `tsc` for the `.d.ts` files, and `attw` for `check-exports`.
  - **Bun for everything else, npm only for the publish step.** That is the same as the other editor-kit repos that already publish.
  - License `MIT` (Section 10).
  - Only runtime dependency: the key-value-db client (R10). The package uses no DOM APIs except `fetch`, `document.visibilityState` and `pagehide`, which can be injected for tests.
- **Release setup copied from `maplibre-editor-layer-index`** (`~/Development/OSM/maplibre-editor-layer-index`, the editor-kit repo that already publishes; `@osm-editor-kit/maplibre-editor-layer-index` is at 0.1.12). Without the ELI data and preview-Pages parts:
  - Changesets, with `.changeset/config.json` set to `access: public`, `baseBranch: main`, `commit: false`, and `ignore: ["josm-plugin"]`. Scripts: `version-packages` (`changeset version`) and `release` (`bun run build && changeset publish`).
  - One `.github/workflows/release.yml`, with no release PR:
    - It runs on push to `main` and publishes when the push contains a `.changeset/*.md`.
    - `workflow_dispatch` offers `version_bump` (none/patch/minor/major) and `publish_only` (retry a failed publish).
    - Steps: `oven-sh/setup-bun` → `bun install --frozen-lockfile` → lint, build, type-check, test, `check-exports` → `bun run version-packages` + `bun run format` → commit `chore: release [skip ci]` and push with `HUSKY=0` → `actions/setup-node` (registry npmjs) + `npm install -g npm@latest` → `bun run release`.
    - If it fails, it opens a `release-failure` issue.
  - **npm trusted publishing (OIDC)** with `permissions: id-token: write`. There is no `NPM_TOKEN` secret, and provenance comes automatically. The trusted publisher for `@osm-editor-kit/live-touched` → `osm-editor-kit/osm-live-touched` / `release.yml` has to be set up on npmjs.com once. Check whether npm needs the package to exist first; if so, the first `0.1.0` is published by hand.
  - Package `publishConfig: { access: public, provenance: true }`, `files: ["dist"]`, ESM only.
- **Use the published key-value-db client, no copies.** key-value-db publishes its client to npm (R10). `@osm-editor-kit/live-touched` depends on it with a normal semver range, and `kvApi.ts` is a thin layer on top. It adds the tile-query helper and the live-touched `data` type. That is its only runtime dependency. `docs/api.md` describes what we use and links to key-value-db's `docs/API.md` for the rest.
- **Link to the backend**: README and `docs/api.md` link to the key-value-db GitHub repo, the live Worker `https://key-value-store.fixmycity.workers.dev`, and its health URL. We do not use a git submodule.
- **Privacy**: `PRIVACY.md` in the repo root describes our workflow and copies the backend parts from key-value-db's `PRIVACY.md` (Section 7). The consent dialog and the panel footer link to it.

## 3. Review of key-value-db

What it is: a Cloudflare Worker (Hono) on D1 (SQLite, **EU jurisdiction**). Each SPA is a *project* with a public API key, an origin allowlist, `read_access` (`public` | `osm_user`) and `write_access` (`any_osm_user` | `allowlist`). Entries are `{ id, data (JSON ≤ 64 KB), tags (≤ 32), version, created_at/updated_at, created_by/updated_by }`. OSM login happens in the client. The Worker verifies the Bearer token against `api/0.6/user/details.json` and caches `sha256(token) → uid` for 1 h, purging after 24 h. A daily cron (`17 3 * * *`) cleans the token cache. Admin works through REST or MCP.

### 3.1 What we can use as it is

| Need                                      | key-value-db feature                                                                        |
| ----------------------------------------- | ------------------------------------------------------------------------------------------- |
| Separate namespace, CORS for iD origins   | Project `live-touched` (exists, Section 3.4) with its origin list                           |
| Writes only by verified OSM users         | `requireOsm` (OSM OAuth2 token, verified server-side, so nobody can post as another user)   |
| Reads only by logged-in OSM users         | `read_access = osm_user`                                                                    |
| Username in results                       | `created_by` / `updated_by` `{ osm_uid, display_name }`                                     |
| Upsert per object                         | `PUT /entries/{id}`                                                                         |
| Remove one object (undo or discard)       | `DELETE /entries/{id}`                                                                      |
| **BBox query**                            | **Tags as a tile index** (Section 5.2): `GET /entries?tag=…&tag=…&match=any` uses the existing `entry_tags` index |
| Delta polling (optional)                  | `updated_since`                                                                             |
| Rate limit on token verification          | `OSM_VERIFY_RL`                                                                             |

We **do not need a spatial index** in the DB. Tile tags on z14 (plus a coarse fallback) give an indexed "intersects viewport" query. This reuses tags as they exist today. Exact bbox filtering happens on the client.

### 3.2 Gaps and required changes (all generic, per-project opt-in)

These changes belong in the key-value-db repo. They are written so that other projects can use them too, and existing projects keep their behavior because every new setting defaults to today's behavior.

**Compatibility with existing consumers (hard rule).** Two production apps use key-value-db today (checked 2026-09-27):

| App | Local path | KV project | How it uses the API |
| --- | --- | --- | --- |
| knotenpunkte | `~/Development/FMC/knotenpunkte` | `knotenpunkte` | Public reads; `list` (tags, cursor), `put`, `remove`, `me` |
| parkraum-zaehlung | `~/Development/FMC/parkraum-zaehlung` | `parkraum-zaehlung` | Public reads; `list`, `put`, `remove`, `me` |

Both use a **vendored copy** of `@kv/client` (`src/shared/kv-client/`). It parses responses with zod envelope schemas.

What that means for M1:
- **Shared editing must keep working.** Both apps let any logged-in user overwrite and delete entries that others created (ratings, counts, and the `meta` entries per dataset). So R1 `write_scope` **defaults to `any`**, and `owner` is opt-in per project.
- **No new default behavior.** `entry_ttl_s` defaults to `NULL` (entries never expire). Existing projects keep today's rows and behavior without a data migration.
- **Responses change only by adding fields.** The vendored zod schemas are plain `z.object` (not `.strict()`), so extra fields such as `expires_at` are dropped silently. Existing fields keep their names, types, and meaning.
- **Only existing error codes on existing routes.** The vendored client maps unknown codes to `internal`. R1 uses the existing `forbidden_user` (403), and only for owner-scope projects.
- **New things are new routes**: `DELETE /me/entries` and `POST /batch`. Existing routes keep their paths and payloads.
- **Cleanup job**: expired-entry purge only touches rows with `expires_at` set, which is never the case for the two apps. The `osm_users` cleanup keeps every user who is referenced by any entry, so their names in the `/data` views stay.
- **Checks before deploy**: the existing API tests plus new regression tests for a project with default settings (a user overwrites and deletes someone else's entry; entries do not expire; the list response shape is unchanged). After deploy, open `/data` in both apps (public reads) and do one test write in a test dataset.

**R1: owner-only writes (security, required).**
Today any verified OSM user can `PUT` over or `DELETE` **any** entry in a project. `store/entries.ts` has no ownership check. In our case that would let anyone delete or fake other people's "touched" markers.
Change: add a project setting `write_scope: 'any' | 'owner'` (default `any`). With `owner`:
- the entry id must start with `<writer osm_uid>/`, otherwise `403 forbidden_user`. This stops anyone from claiming an id in another user's namespace first.
- `PUT` and `DELETE` only work on entries where `created_by_osm_uid = writer`. Add this to the `ON CONFLICT … WHERE` and the `DELETE … WHERE`.
Migration: `ALTER TABLE projects ADD COLUMN write_scope …`. Update the admin schema, the MCP tools, and the tests.

**R2: hard TTL per project (privacy, required).**
There is no expiry today. Change: add a project setting `entry_ttl_s INTEGER NULL` (default `NULL` = never), and an `entries.expires_at TEXT NULL` column with index `(project_id, expires_at)`.
- On **insert**, set `expires_at = created_at + ttl`. On update, keep `expires_at`, so the 3 h limit counts from the first touch. If an update hits a row that has already expired, reset it as a new entry (new `created_at`, new `expires_at`).
- All reads (`GET` one, list, tags) filter `expires_at IS NULL OR expires_at > now`. The limit is exact on read.
- A cron purge `DELETE FROM entries WHERE expires_at <= now` (tags cascade) runs **every 15 min**. Rows are physically deleted at most ~15 min after they expire.
- **One cleanup job for everything:** change the existing daily cron (`17 3 * * *`) to `*/15 * * * *`. It runs three cheap deletes in order: expired entries (R2), token cache rows older than 24 h (existing logic), and orphaned user records (R4). This is one cron, not two.
- For us: `entry_ttl_s = 10800` (3 h).

**R1 and R2 are admin-only project settings.** `write_scope` and `entry_ttl_s` are new columns on `projects`, like `read_access` and `write_access`. Only the admin can set them: admin REST (`POST`/`PATCH /admin/projects…` with the admin Bearer key) or the kv-admin MCP tools `create_project` / `update_project` in Cursor. `get_project` and `list_projects` show them. No `/v1` client route can read or change project settings, and a client cannot choose its own mode. The project key only selects the project.
What changing them later means (document in `docs/API.md`, and have `update_project` return a warning):
- `write_scope` `any` to `owner` on a project that already has data: old entries without the `<uid>/` prefix can no longer be changed or deleted by anyone except through admin. Only switch on empty projects or with a planned migration.
- `entry_ttl_s`: applies to **new** entries only. Existing rows keep their `expires_at`, or `NULL`. If needed, add an optional `apply_to_existing: true` flag on `update_project` that sets `expires_at = created_at + ttl` for existing rows.

**R3: "delete all my data" endpoint (required, the nuke button).**
New route `DELETE /v1/projects/{slug}/me/entries`: deletes every entry in this project with `created_by_osm_uid = me` (tags cascade) and returns `{ deleted: n }`. It needs `requireOsm`. The client then calls the existing `DELETE /me` to evict the token cache row.
Add `removeMine()` to the client (R10).
**Scope: this project only.** The SQL is `DELETE FROM entries WHERE project_id = ? AND created_by_osm_uid = ?`. The user's data in other projects (knotenpunkte ratings, parkraum counts, …) is not touched. Two follow-up steps work across projects because the tables are shared, and neither deletes data in another project:
- `DELETE /me` evicts **the token cache row for the token used in this request**. The token cache is not tied to a project. If the same OSM token is used in another project, that project's next request checks the token with OSM again (one extra call). The user is not logged out anywhere and nothing is lost.
- The `osm_users` record is only deleted when **no entry in any project** still refers to the user (R4). If the user also has data in another project, the record (OSM id and display name) stays as long as that data exists.
- A test covers this: one user with entries in two projects runs "delete my data" in one of them, and the other project's entries and the user record are still there.

**R4: clean up `osm_users` (privacy, strongly recommended).**
`osm_users (osm_uid, display_name, first_seen_at, last_seen_at)` is kept **forever** and shared across projects. For a privacy statement that says "nothing kept beyond 3 h" this is the one weak spot.
Change: the 15-min cleanup job (see R2) deletes `osm_users` rows that **no entry** (`created_by`/`updated_by`, any project) **and no `verified_tokens` row** references.
- Why the token condition: the token cache reads the display name through a join on `osm_users` (`getVerifiedToken`). If we deleted the user row while a cached token still exists, that user's new entries would show `'unknown'` as the name until the token is checked again (up to 1 h). Waiting for the token rows removes that problem, so no extra time rule is needed.
- Result: token rows are purged 24 h after the last check, so the user record goes away **at most ~24 h after the user's last request**, once their entries are gone. With "Delete my data" (R3 + `DELETE /me`) the token row is deleted immediately, so the user record is gone **within 15 min**. There are two exceptions: the user still has entries in another key-value-db project, or the user has a second cached token (for example from another device), which expires 24 h after its last use.
- `mapEntry` already falls back to `'unknown'` if a name is missing.

**R5: batch write (strongly recommended).**
iD can change dozens of objects at once (move a selection, paste). Today that means one request per object, and each `PUT` uses 3 D1 queries, with a limit of 50 per Worker invocation.
Change: `POST /v1/projects/{slug}/batch` (not `/entries:batch`: a `:` in a Hono path starts a parameter) with `{ put: [{id, data, tags}], delete: [id] }`, max ~15 puts per call (3 queries each). Put everything in one `db.batch()`. This is already listed as Phase 2 in key-value-db's PLAN.md.
Until R5 exists, the client falls back to single calls with a concurrency of 4. Add `batch()` to the client (R10).

**R6: read and write rate limits (recommended).**
Phase 2 of key-value-db already plans `READ_RL` and `WRITE_RL` bindings. With polling every 4 to 10 s, a limit like 30 reads per 60 s per IP+project is fine for real clients and stops runaway loops.

**R7: non-browser clients such as JOSM (later, needed for M6).**
`projectAuth` rejects requests without an `Origin` header. JOSM does not run on a web page, so it has no origin of its own and sends none by default. A desktop app can send **any** `Origin` value, though. The origin check only protects against other *websites* using the key from inside a browser. It does not protect against native clients. The OSM token is the real protection.
- **Option A (preferred, no backend change):** the plugin sends a fixed `Origin: https://josm.openstreetmap.de`, and Tobias adds exactly that value to the project through MCP. Java's old `HttpURLConnection` (and JOSM's default `HttpClient` wrapper based on it) blocks the `Origin` header unless `sun.net.http.allowRestrictedHeaders=true`. So the plugin makes its requests with `java.net.http.HttpClient`, which allows the header. Verify this in M6.
- Option B: a project setting `allow_originless: bool` in key-value-db. Use it only if A fails.
- We do **not** need a wildcard origin.

**R10: publish the client to npm instead of copying it (required).**
Today `packages/kv-client` (`@kv/client`) is workspace-only and exports raw `src/index.ts`. knotenpunkte and parkraum-zaehlung each carry a **copy** in `src/shared/kv-client/`, and the copies have already drifted. They added zod response validation (`schema.ts`) and a zod error-body parser, while the original has no validation. A third copy in this repo would make it worse.

Options considered:
- **Git dependency** (`github:FixMyBerlin/key-value-db#…`): rejected.
  - npm and bun cannot install a **subfolder** of a monorepo from git, and the client lives in `packages/kv-client`.
  - The package has no build step.
  - Our own npm package would carry a git URL as a runtime dependency for every consumer (iD), with no semver and no provenance.
- **Keep copying**: rejected. It is the drift we already see.
- **Publish to npm**: chosen.

Change in key-value-db:
- Rename the package to its public name (**npm scope to decide, Section 11**) and keep the demo on `workspace:*`.
- Build to ESM plus `.d.ts` (Vite library mode or `tsc`) with `files: ["dist"]`, and add `attw` `check-exports`.
- Merge the improvements from the copies back in: response validation, and the stricter error-body parsing.
  - Keep the client **dependency-free** with small hand-written type guards, instead of adding zod as a dependency. Our package ends up in iD's bundle, and knotenpunkte and parkraum can keep using zod for their own `data`.
  - If zod is preferred, make it a `peerDependency`, not a dependency.
- New methods for the new routes: `removeMine()` (R3) and `batch()` (R5).
- Release like `maplibre-editor-layer-index`: changesets, a `release.yml` on push to `main`, and npm trusted publishing (OIDC) from `FixMyBerlin/key-value-db`, so no `NPM_TOKEN` is needed. Tobias configures the trusted publisher on npmjs.com once.
- **License of the client: MIT** (decided). Add a `LICENSE` file (MIT) in `packages/kv-client`, set `"license": "MIT"` in its `package.json`, and add a line in the root README saying "the Worker and the demo are AGPL-3.0, `packages/kv-client` is MIT". Tobias is the only author of the client (git log), so relicensing needs nobody else's consent.
- Follow-up in the consumers (after M1, not blocking): knotenpunkte and parkraum-zaehlung replace `src/shared/kv-client/` with the npm package. Their own zod schemas for `data` stay.

**R8: platform privacy statement (small).**
key-value-db has no privacy document. Add `PRIVACY.md` to key-value-db. It covers the Cloudflare services used, with links, and the data the platform keeps for any project, with the per-project options. Each project's own statement links to it and copies the relevant parts. Content: Section 7.1.

**R9: LLM-readable docs of what the service can do (required).**
The current key-value-db docs are the `README.md` (155 lines, mostly setup, Cloudflare, and OAuth) and the `PLAN.md` (598 lines, the original design with Phase 1/2 plans). Neither is a short, current answer to "what can this API do, and how do I use it from a new app". The consumers each carry their own copied notes in their READMEs.
Change in key-value-db:
- **`docs/API.md`**: the reference, kept current with every API change. It is written so an LLM (or a person) can use the service from it alone. It covers:
  - Capabilities in one list.
  - Every route with method, auth, request, response, and error codes.
  - Project settings (`read_access`, `write_access`, `write_scope`, `entry_ttl_s`) and what each one changes.
  - Limits: id regex, 64 KB data, 32 tags, list limit, batch size, rate limits.
  - Short patterns: shared editing (knotenpunkte and parkraum style), per-user owned entries with a TTL (live-touched style), tag-based tile index for bbox queries, "delete my data".
  - Which projects exist and who uses them.
- **`AGENTS.md`** (and a `CLAUDE.md` pointing to it): what the repo is, where things live, the compatibility rules above, and "update `docs/API.md` and `PRIVACY.md` with every API change".
- **README**: a short "What it can do" block at the top that links to `docs/API.md`, `PRIVACY.md`, and `AGENTS.md`.
- **`PLAN.md`**: a note at the top that it is the original design, and that `docs/API.md` is the current truth.
- Consumers: their READMEs link to key-value-db's `docs/API.md` instead of repeating it. That is a small follow-up in knotenpunkte and parkraum-zaehlung, done later, not needed for M1.

### 3.3 Capacity check (Cloudflare free tier)

- **Workers requests: 100k/day on the free plan.** One active editor polling every ~8 s makes ~450 requests per hour. The free tier covers roughly 150 to 200 editing-hours per day for all projects together. That is fine for a pilot. **Decision: stay on the free plan and switch to Workers Paid (USD 5/month) when the numbers need it.** Watch the request count in the dashboard during the pilot.
- **D1 rows read: 5M/day.** A tile query reads the matched `entry_tags` rows plus the entries, the project row, and the token cache row, usually under 20 rows. That comes to ~9k rows per editing-hour, so it is fine.
- **D1 rows written: 100k/day.** One put is 1 entry row plus old and new tag rows, about 5 to 10 rows. With debouncing (Section 5.4) this is fine.

### 3.4 Project setup

**The live project exists** (created 2026-09-27 by Tobias via MCP in Cursor). These values are public config and are baked into the client:

```
VITE_KV_BASE_URL=https://key-value-store.fixmycity.workers.dev
VITE_KV_PROJECT=live-touched
VITE_KV_API_KEY=kv_825ada0b89b3d63f541f5fd155dca104c2c0a229
```

- Slug: **`live-touched`**. All API paths are `/v1/projects/live-touched/…`.
- Allowed origins: `http://127.0.0.1:*` and `https://fixmyberlin.github.io`.
- Tobias changes the project settings himself through the kv-admin MCP in Cursor. When this plan needs a change (origins, settings), list it here and ask him.

Origin notes:
- **Local iD dev** (`~/Development/OSM/iD`, `scripts/server.js`, port 8080) is covered by `http://127.0.0.1:*`. Open iD at `http://127.0.0.1:8080`, **not** `localhost:8080`, because `localhost` is not allowlisted.
- `https://fixmyberlin.github.io` covers a demo or iD build on GitHub Pages under the FixMyBerlin org.
- **To add later**: `https://www.openstreetmap.org` (userscript on osm.org/edit, M7) and the JOSM value (R7, M6).

Settings to apply once key-value-db has the new features (ask Tobias to set them through MCP):

```json
{
  "read_access": "osm_user",
  "write_scope": "owner",
  "entry_ttl_s": 10800
}
```

`read_access = osm_user` can be set today. Check the current value with MCP `get_project`. `write_scope` (R1) and `entry_ttl_s` (R2) exist only after M1.

For local development against `bun run dev-api` in key-value-db, create a `live-touched` project on local D1 (curl as in key-value-db's README). Its key is different from the live key. The package takes `baseUrl`, `project` and `apiKey` as options, so the iD dev setup chooses local or live.

## 4. Data model (on top of key-value-db entries)

**Entry id**: `<osm_uid>/<type><id>`, for example `123456/w789`. We use the uid, not the username. Usernames change and can contain characters that the id regex `[A-Za-z0-9._:/-]` does not allow. With R1 the uid prefix is enforced.

**data**:

```ts
type LiveTouchedData = {
  schema: 1
  osm_type: 'node' | 'way' | 'relation'
  osm_id: number                    // positive only; new objects (negative ids) are not sent in v1
  bbox: [minLon, minLat, maxLon, maxLat]  // rounded to 6 decimals; a node's bbox is a point
  base_version: number              // version the user is editing on
  status: 'touched' | 'saved'
  new_version?: number              // set with status "saved" (= base_version + 1)
  changeset_id?: number             // set with status "saved" if known
  touched_at: string                // client ISO time of the last local change
  editor: string                    // e.g. "iD 2.43", "JOSM 19xxx"; helps with debugging
}
```

No tags, no names, no geometry beyond the bbox. The list shows names from the reader's **own** loaded data.

**tags (tile index)**: slippy tiles that intersect the bbox.
- If the bbox covers ≤ 16 tiles at **z14**, tag `z14/x/y` for each.
- Otherwise tag the covering tiles at **z10** (`z10/x/y`, capped at 16). If it is still too large, send nothing (for example huge boundary relations), and log that on the client.

**Query**: the viewport tiles at z14 plus their z10 parents, with `match=any` and `limit=500`. Poll only when the viewport covers ≤ 36 z14 tiles, which is about iD zoom ≥ 15 on a normal screen. Otherwise show "Zoom in to see live edits". After loading, the client filters exact bbox intersection, removes its own uid, and computes the status.

## 5. TypeScript package `@osm-editor-kit/live-touched`

### 5.1 Modules (`packages/live-touched/src`)

| Module              | Responsibility                                                                                                                                                   |
| ------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `config.ts`         | `baseUrl`, `project`, `apiKey`, `getOsmToken()`, timings (overridable for tests)                                                                                 |
| `kvApi.ts`          | Thin layer over the published key-value-db client (R10): `me()`, `listByTiles(tiles)`, `putBatch(puts, deletes)` (single-call fallback until R5), `deleteMine()`, `forget()`. Errors are the client's `KvError` |
| `tiles.ts`          | lon/lat to tile, bbox to tiles (z14/z10 rule), viewport to query tiles                                                                                           |
| `status.ts`         | Remote entry plus `now` to `touched` / `stale` / `saved` / `hidden`, using the windows in Section 5.5. Uses the server `Date` header to correct clock skew         |
| `conflicts.ts`      | Compare remote entries with local state: `{ sameObjectTouchedByMe, localVersion }` to `ok` / `outdated` / `parallel`                                             |
| `sync.ts`           | **Diff-based writer**: the editor reports the full set of locally modified objects. The core compares it with what was sent last, then upserts new or changed objects and deletes removed ones. Undo and discard are handled without special events |
| `poller.ts`         | Adaptive polling (8 s start, 4 s when others are present, 10 s after 3 empty responses). Pauses when the tab is hidden, the feature is off, or the user is zoomed out. Backs off on 429 or 5xx |
| `session.ts`        | Wires everything together. This is the **public API** for editors (Section 5.2)                                                                                  |

`nukeMyData()` stops the poller and sync, calls `DELETE /me/entries` then `DELETE /me`, clears local state and consent, and turns the feature off. It returns the number of deleted entries. The editor UI asks for confirmation first and shows the result.

`disable()` also deletes the user's current `touched` entries (best effort) before it stops.

### 5.2 Public API (what an editor calls)

```ts
const session = createLiveTouchedSession({
  baseUrl, project, apiKey,       // from Section 3.4
  getOsmToken: () => string | null,
  editor: 'iD 2.43',              // goes into data.editor
})

// input from the editor
session.setModified(objects)      // full set of locally modified objects: { type, id, baseVersion, bbox }
session.markSaved(changesetId, objects?) // after a successful upload
session.setViewport(bbox, zoom)   // on map move

// lifecycle
session.enable()                  // after consent
session.disable()                 // removes my touched entries (best effort), stops
session.nukeMyData(): Promise<{ deleted: number }>

// output for the editor UI
session.subscribe((state) => …)   // returns unsubscribe
state = {
  enabled, zoomedOutTooFar, othersNearby,          // othersNearby drives the pulsing indicator
  items: Array<{ type, id, user, status, ageMs, newVersion?, hint }>,  // sorted, see 5.5
  error?: { code, message },
}
```

Also exported: `PRIVACY_URL`, the consent key and version (`CONSENT_VERSION`), and the pure helpers (`statusOf`, `tilesForBbox`, `compareVersions`) for editors that need them.

The package has **no texts**. `hint` is a code (`ok`, `outdated`, `parallel`) with the numbers, and the editor turns it into a sentence in its own i18n. `docs/concept.md` has the English and German reference texts and the UI rules (panel content, sort order, indicator, consent content, colors per status). Every editor follows these rules.

### 5.3 Requests used

| Purpose           | Request                                                                    |
| ----------------- | -------------------------------------------------------------------------- |
| Who am I          | `GET /v1/projects/live-touched/me`                                          |
| Poll              | `GET …/entries?tag=z14/…&tag=z10/…&match=any&limit=500`                    |
| Send changes      | `POST …/batch` (R5), fallback `PUT` / `DELETE …/entries/{id}`      |
| Mark saved        | same as send, with `status: 'saved'`, `new_version`, `changeset_id`        |
| Delete my data    | `DELETE …/me/entries` (R3), then `DELETE …/me`                             |

All requests send `X-Api-Key` and `Authorization: Bearer <OSM token>`.

### 5.4 Write rules

- Debounce local changes by 2 s, then send one batch. Send immediately on save, on disable, and on `pagehide` (best effort with `fetch(…, { keepalive: true })`).
- Only objects with a positive id and a known `base_version` are sent.
- `touched_at` changes only on real edits. Re-sending an unchanged object is skipped.

### 5.5 States and time windows (as in the handover plan)

| Status    | Condition                                   | Shown                                                         |
| --------- | ------------------------------------------- | ------------------------------------------------------------- |
| `touched` | not saved, last change < 30 min ago         | "X is editing this right now"                                 |
| `stale`   | not saved, 30 to 60 min                     | "X edited this recently; unclear if saved or discarded"       |
| hidden    | not saved, > 60 min                         | not shown                                                     |
| `saved`   | saved, < 2 h after the save                 | "X edited and saved this (v4)", plus a version comparison     |
| deleted   | 3 h after the first touch (server TTL, R2)  | gone from the DB                                              |

Version hint for `saved`: if the local version equals `new_version`, "Your version is current". If the local version is lower, "Careful: you are on v3, X saved v4. Save soon to avoid conflicts". For `touched` on an object I have also changed, the hint "X is editing this object at the same time" is shown first.

### 5.6 Tests

- Unit tests (vitest) for tiles, status windows, conflicts, the sync diff, the poller schedule with fake timers, and the session API with a fake `fetch`.
- An API contract test against local `wrangler dev` of key-value-db. It is optional and skipped when the local API is not running.

## 6. iD integration (inside `~/Development/OSM/iD--radnetz-berlin`)

iD has no plugin API. The frontend and the event hooks are built **directly in iD**, in the `iD--radnetz-berlin` worktree (branch `radnetz-berlin`, fork `tordans/iD`). **That work happens in a separate chat.** This repo delivers the package and a guide.

**How iD gets the package**: iD bundles its JS with esbuild (`config/esbuild.config.js`). During development, iD--radnetz-berlin adds `"@osm-editor-kit/live-touched": "file:../osm-live-touched/packages/live-touched"` and imports it like any other dependency. Running `bun run dev` here rebuilds `dist/`, and iD's `build:js:watch` picks it up (check that esbuild watches the linked package; otherwise re-run the iD build). After M5 the dependency switches to the npm version.

**What gets built in iD** (for the other chat, following iD's module layout and i18n):
- **Wiring** (e.g. `modules/live_touched/` or `modules/services/live_touched.js`):
  - Create the session with the config from Section 3.4.
  - `context.history().on('change')`: compute the modified and deleted entities with positive ids from `history.difference()`, with version and extent, and pass them to `session.setModified(…)`.
  - Upload success (`context.uploader()` events; check the names in 2.43): `session.markSaved(changesetId)`.
  - `context.map().on('move')`: `session.setViewport(…)`.
  - Token: from iD's OSM connection (check how iD 2.43 exposes the OAuth2 token first).
- **Map highlight**: after each redraw, set classes (`live-touched-touched`, `-stale`, `-saved`, `-conflict`) on the SVG elements of the listed entities. iD gives them the entity id as a class, e.g. `.w789`. Use own colors (orange or violet halo), styled like iD's selection halo, in iD's CSS.
- **Panel**: an iD pane or sidebar section with the list, following `docs/concept.md`. Clicking a row selects and zooms with iD's own functions. The footer has the privacy link and **"Delete my data on the server"** (confirm, then `session.nukeMyData()`, then show the count).
- **Indicator**: a pulsing dot on the map-control button when `state.othersNearby`.
- **Consent dialog** on first enable, using iD's modal. Store the consent version in iD's preferences (`prefs`).
- **Texts** in iD's `data/core.yaml` (English). German comes through iD's translation flow, or locally first.

**What this repo delivers for that chat**:
- The package with a clear README (`packages/live-touched/README.md`): the API from 5.2, a copy-paste iD wiring example, the iD events and classes to hook into, and how to test with two browser profiles.
- `docs/concept.md` with the UI rules and reference texts.

Local dev: iD runs on `http://127.0.0.1:8080`, which the allowlist covers. If iD--radnetz-berlin is deployed somewhere (e.g. a PR preview or staging host), Tobias adds that origin through MCP.

Later: a PR to iD upstream, or an osm.org userscript. Both need their own UI code, because the UI lives in the editor. Rapid is the same: its own UI and hooks on top of the same package.

## 7. Privacy (two documents)

The privacy text is split the same way as the system:

1. **key-value-db `PRIVACY.md`** (in the key-value-db repo, R8): describes the **platform**. That means the Cloudflare services it uses, with links, and the generic data it keeps for every project. key-value-db supports different workflows per project (TTL or not, owner-scope or not, public or login-only reads), so this document describes the options and does not promise anything project-specific.
2. **This repo's `PRIVACY.md`** (repo root): describes **our workflow**. It covers what the editors send, when, for how long, who sees it, and "Delete my data". Its Cloudflare/backend section **copies the relevant parts** from key-value-db's `PRIVACY.md` so a reader has everything in one place, and **links** to that document as the source.

Both are written in M1 (key-value-db) and M2 (this repo) and checked before the pilot. The editors' consent dialog and panel footer link to this repo's `PRIVACY.md`. Use the rendered GitHub URL of the file, or GitHub Pages if we set that up.

### 7.1 key-value-db `PRIVACY.md` (platform, R8)

**Cloudflare services used**, each with a link to Cloudflare's docs (check the links and the numbers while writing):

| Service                        | What it does here                                                                  | Link                                                                                 |
| ------------------------------ | ---------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------ |
| Workers (on `workers.dev`)     | Runs the API at Cloudflare's edge worldwide; sees the IP address of each request   | https://developers.cloudflare.com/workers/                                           |
| D1, **EU jurisdiction**        | Database; data at rest stays in the EU                                              | https://developers.cloudflare.com/d1/configuration/data-location/                    |
| Rate Limiting binding          | Counts requests per IP **in memory** per location; nothing is stored               | https://developers.cloudflare.com/workers/runtime-apis/bindings/rate-limit/          |
| Workers Logs (observability)   | Request logs; retention depends on the plan (check the exact days)                 | https://developers.cloudflare.com/workers/observability/logs/workers-logs/           |
| Cron Triggers                  | Runs the cleanup job every 15 min                                                   | https://developers.cloudflare.com/workers/configuration/cron-triggers/               |
| Cloudflare as processor        | DPA and Cloudflare's own privacy policy                                             | https://www.cloudflare.com/cloudflare-customer-dpa/ , https://www.cloudflare.com/privacypolicy/ |

**Data kept for every project:**
- **Entries**: the JSON the project sends, tags, and created/updated by OSM user id with timestamps. How long they stay depends on the project: no TTL (default), or `entry_ttl_s` (R2), which hides entries on expiry and purges them within 15 min. Users can delete their own entries in a project at any time (R3).
- **User record** (`osm_users`): OSM user id, display name, first and last seen. The cleanup job deletes it once no entry and no token cache row refers to the user (R4).
- **Token cache**: the SHA-256 hash of the OSM access token plus the user id. The token itself is never stored. Purged 24 h after the last check, and evicted immediately by `DELETE /me`.
- **Logs**: method, path, status, duration, project, and the OSM user id after verification. Never the token.
- **IP address**: not stored by the app (see Workers and Rate Limiting above).
- **Controller**: **FixMyCity GmbH**. Take the address and privacy contact from FixMyCity's existing imprint and privacy page, and link to them. Do not retype them by hand.

### 7.2 This repo's `PRIVACY.md` (our workflow)

- **Opt-in**: nothing is sent before consent in the editor.
- **What is sent** per edited object: OSM user id, object type and id, bbox, base and new version, status (`touched`/`saved`), changeset id, timestamps, and editor name. No tags, geometry, or other content, and no history or archive.
- **How long**: the project uses `entry_ttl_s = 10800`, so every entry is **gone 3 h after the first touch at the latest** (hidden immediately, purged within 15 min). It can be earlier: when you undo or discard, or when you turn the feature off.
- **Who sees it**: every logged-in OSM user who queries the area through this project (`read_access = osm_user`). In practice that means other users of the feature, but it cannot be limited to people who opted in. Only you can change or delete your entries (`write_scope = owner`).
- **The new part**: edits are visible **before** upload. After upload, username and edits are public in OSM anyway.
- **Login token**: the editor sends your OSM token to the backend to confirm who you are. The token could also write to OSM. The backend only uses it to read your user details.
- **Delete my data**: this button deletes all your live-touched entries and your cached login check right away. It does not touch other apps that use the same backend. Your user record (OSM id and display name) is deleted within 15 min, unless another of those apps still stores data from you. In that case it stays as long as that data exists (details in the backend section).
- **Purpose and legal basis**: avoiding conflicts and coordinating mapping. Consent (GDPR Art. 6(1)(a)), which you can withdraw at any time with "Delete my data" plus turning the feature off.
- **Backend section**: a copy of the relevant parts of 7.1 (Cloudflare services with links, token cache, user record, logs, IP), with the line "Source: key-value-db PRIVACY.md" and a link.
- **Controller**: **FixMyCity GmbH**, with the same contact details as in key-value-db's `PRIVACY.md`.

### 7.3 Keeping them in sync

- The copied backend section in this repo's `PRIVACY.md` says which key-value-db commit or date it was copied from.
- When a key-value-db change affects privacy (new table, new Cloudflare service, other retention), that PR updates its `PRIVACY.md`. A note in key-value-db's `PRIVACY.md` lists the projects that copy from it (for now `live-touched`), so the copy here gets updated too.

## 8. JOSM plugin (`packages/josm-plugin/`, later: M6)

- Java plus Gradle with `org.openstreetmap.josm` gradle plugin. Target the Java version the current JOSM tested build requires (check it, probably 17+).
- **No local Java is installed.** Build in Docker with `docker run --rm -v "$PWD":/work -w /work -v live-touched-gradle:/home/gradle/.gradle gradle:jdk21 gradle build`. The private `packages/josm-plugin/package.json` wraps it as `bun run --filter josm-plugin build`, and the root has `josm-build` / `josm-check`. The named volume caches the Gradle home.
- License: MIT (Section 10). Put an MIT `LICENSE` in the plugin folder, and set the license in the plugin manifest/`build.gradle.kts`.
- Release (later): the jar goes on a GitHub Release, and the JOSM plugin list is updated separately. This is not part of the npm release workflow.
- Same spec as `docs/concept.md` and `docs/api.md`: a `DataSetListener` (or `DataSet#getModified()` diff) feeds the sync, `UploadHook` / upload listener marks saved, a `NavigatableComponent.ZoomChangeListener` gives the bbox, a `MapViewPaintable` draws highlights, and a `ToggleDialog` shows the list and the "Delete my data" button. The OAuth2 token comes from JOSM's `OAuthAccessTokenHolder`.
- Origin header: see R7.

## 9. Milestones

| #  | Where           | What                                                                                                                                                             | Done when                                                                        |
| -- | --------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------- |
| M0 | this repo       | Scaffold: README, bun workspace, configs copied from surface-smoothness, empty package, changesets and `release.yml` (from maplibre-editor-layer-index), CI running `check-ci` | `bun run check-ci` is green                                                      |
| M1 | key-value-db    | R1 owner scope, R2 TTL plus the daily cron changed to 15 min, R3 `DELETE /me/entries`, R4 `osm_users` cleanup, R5 batch. Each with tests, migration, and admin/MCP schema updates, keeping the compatibility rules (Section 3.2). R10: publish the client to npm with the new methods. Then R8, key-value-db's `PRIVACY.md` (Section 7.1), and R9, the docs (`docs/API.md`, `AGENTS.md`). Deploy, then Tobias applies the project settings (Section 3.4) and a local project is created | Tests green, deployed, knotenpunkte and parkraum-zaehlung checked, settings applied                                          |
| M2 | this repo       | `docs/concept.md`, `docs/api.md`, and root `PRIVACY.md` (Section 7.2)                                                                                              | Reviewed by you                                                                  |
| M3 | package         | Core: kvApi, tiles, status, conflicts, sync, poller, session, with unit tests                                                                                    | Tests green; a scripted node demo of two fake users sees each other on local API |
| M4 | package plus iD | Package README with the iD wiring example; `docs/concept.md` UI rules. The iD frontend and wiring are built in iD--radnetz-berlin (separate chat)                      | Two browser profiles on local iD see each other's edits live; nuke empties the DB |
| M5 | npm             | Set up the npm trusted publisher, then publish `@osm-editor-kit/live-touched` `0.1.0` through `release.yml`, and add the usage doc                                                | Installable from npm                                                             |
| M6 | josm            | JOSM plugin, R7 decided                                                                                                                                          | JOSM and iD see each other                                                       |
| M7 | all             | Polish: styles, texts, rate limits (R6), Workers Paid when needed, userscript for osm.org, and Rapid                                                                 | as needed                                                                        |

## 10. Decisions made (from the open questions in the handover)

- **Auth**: OSM OAuth2 through key-value-db's token verification. It is already there, and R1 makes it owner-only.
- **Status on server or client**: the **client** computes it. The server only enforces the hard TTL.
- **Detecting cancel or discard**: diff-based sync. When an object leaves the local modified set, its entry is deleted.
- **New objects (negative ids)**: ignored in v1.
- **Hosting**: the existing Worker `key-value-store.fixmycity.workers.dev`.
- **key-value-db changes R1 to R10**: approved, including the 15-min cleanup job. Existing consumers must keep working (Section 3.2).
- **Workers Paid**: only when the numbers need it.
- **GitHub home**: the **`osm-editor-kit`** org (`github.com/osm-editor-kit/osm-live-touched`), like surface-smoothness. The feature is part of the general editor-kit idea. The README says that FixMyCity supports it (with dev time, as mentor or sponsor) and runs the backend. Set `repository`, `homepage` and `bugs` in `package.json` to that repo.
- **License: MIT for everything in this repo**, which covers the npm package, the JOSM plugin, and the docs. The key-value-db client is MIT too (R10). Only the key-value-db **server** stays AGPL-3.0.
  - Why: iD and Rapid are ISC and can only take permissive dependencies. MIT keeps an upstream iD PR possible.
  - The trade-off: nobody has to share their changes, and the package can end up in closed-source editors. We accept that because the value is in the backend, which stays AGPL.
  - JOSM is GPL-2.0-or-later. The JOSM plugin list asks for a GPL-compatible open-source license, and MIT is GPL-compatible, so an MIT plugin works. The plugin running inside JOSM is covered by the GPL as a whole, but the plugin's own source stays MIT.
  - Relicensing is free now because Tobias is the only author and nothing is published yet. Once others contribute, it would need their consent.
- **Privacy controller**: FixMyCity GmbH for both statements, because FixMyCity runs the backend, even though the repo lives in `osm-editor-kit`.
- **First iD integration**: iD--radnetz-berlin, in a separate chat (Section 6).
- **Naming rule**: use an `osm` prefix where the context does not already say OSM (git repos, folders), and leave it out where it does (npm scope `@osm-editor-kit`). So: repo `osm-live-touched`, package `@osm-editor-kit/live-touched` (wiring only, the UI lives in each editor), KV project `live-touched`.

## 11. Open questions for you

None right now. The client name is decided: `@osm-editor-kit/key-value-db-client`. Progress and touchpoints are in [WORKPLAN.md](WORKPLAN.md).
