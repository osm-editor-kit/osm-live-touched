# Work plan: running PLAN.md locally

Living document. Claude updates it while working: status, what was done, and what is waiting for Tobias. [PLAN.md](PLAN.md) has the *what* and *why*. This file has the *order*, the *status*, and the **touchpoints** where Tobias has to act or decide.

Legend: 🤖 Claude does it locally · 🧑 needs Tobias · ✅ done · ⏳ in progress · ⏸ waiting for Tobias · ⬜ not started

## Ground rules

- Claude only changes **local code**. There is **no** push, PR, issue, comment, npm publish, deploy, or message to anyone without Tobias's explicit OK for that specific action.
- Each repo is worked on in its own local feature branch. **Local commits are OK (T1). Push only after asking, and then give the repo link.**
- Anything that needs a login (OSM, npm, Cloudflare, GitHub web UI) or a secret is a 🧑 touchpoint.
- Local end-to-end tests run against `wrangler dev` (local D1) with a **stub OSM user endpoint**, so no real OSM accounts are needed until the iD test (T10).

## Touchpoints for Tobias (overview)

| #   | When                    | What Tobias does                                                                                                        | Status |
| --- | ----------------------- | ----------------------------------------------------------------------------------------------------------------------- | ------ |
| T1  | now                     | Allow **local commits** on feature branches, never pushed, in osm-live-touched, key-value-db, and surface-smoothness | ✅ OK. Ask before each push |
| T2  | before M1 R10           | Pick the npm name for the key-value-db client                                                                            | ✅ `@osm-editor-kit/key-value-db-client` |
| T3  | any time                | surface-smoothness branch `license-mit`: review, then commit and push                                                    | ✅ On `main` (`0d55e45`, fast-forward). Branch deleted on GitHub and locally |
| T4  | after M0                | Create the GitHub repo `osm-editor-kit/osm-live-touched`, then OK the first push, and set up the repo (see "Repo settings") | ✅ Pushed `main`. CI stays red until the client is on npm (T8), then the dependency switches to `^0.1.0` |
| T5  | after M1 + T8           | Merge PR [FixMyBerlin/key-value-db#2](https://github.com/FixMyBerlin/key-value-db/pull/2) to `main`. **This deploys the Worker and runs the remote D1 migration** (`deploy-api.yml`) | ⬜ |
| T6  | after T5                | One test write each in knotenpunkte and parkraum-zaehlung (needs an OSM login). Claude checks health and the public `/data` pages read-only | ⬜ |
| T7  | after T5                | MCP in Cursor: set `live-touched` → `read_access: osm_user`, `write_scope: owner`, `entry_ttl_s: 10800`. Claude checks the behavior afterwards | ⬜ |
| T8  | after T5                | npm: trusted publisher for the kv-client package → `FixMyBerlin/key-value-db` `release.yml`. First publish by hand if npm needs the package to exist first | ⬜ |
| T9  | after M2                | Review `PRIVACY.md` (both) and give the FixMyCity imprint and privacy URLs                                               | ⬜ |
| T10 | after M4                | Run the iD--radnetz-berlin chat, then test with two browser profiles and two real OSM accounts                          | ⬜ |
| T11 | before M5               | npm: trusted publisher for `@osm-editor-kit/live-touched` → `osm-editor-kit/osm-live-touched` `release.yml`, and merge the release changeset | ⬜ |

## npm trusted publishing: steps for Tobias 🧑

npm requires the package to exist before a trusted publisher can be added, and `npm trust` needs your login with 2FA. So each package gets one manual placeholder publish (`0.0.0`); after that, CI publishes with provenance. Order: client first, then live-touched.

**T8: `@osm-editor-kit/key-value-db-client`** (before merging PR #2)

```bash
cd ~/Development/FMC/key-value-db && git switch live-touched-support && cd packages/kv-client
npm login
npm publish --access public --provenance=false
npm trust github @osm-editor-kit/key-value-db-client --repo FixMyBerlin/key-value-db --file release-client.yml --allow-publish
npm trust list @osm-editor-kit/key-value-db-client
```

- `npm publish` runs the build through `prepublishOnly`. `--provenance=false` is needed because provenance only works inside CI.
- Then merge PR #2. `release-client.yml` publishes `0.1.0` with provenance. Check with `npm view @osm-editor-kit/key-value-db-client versions`.
- Optional: `npm deprecate @osm-editor-kit/key-value-db-client@0.0.0 "placeholder, use >=0.1.0"`.

**T11: `@osm-editor-kit/live-touched`** (after the client `0.1.0` is out; Claude first switches the dependency to `^0.1.0` and pushes)

```bash
cd ~/Development/OSM/osm-live-touched/packages/live-touched
npm publish --access public --provenance=false
npm trust github @osm-editor-kit/live-touched --repo osm-editor-kit/osm-live-touched --file release.yml --allow-publish
```

Then Claude adds a `minor` changeset, and the push to `main` publishes `0.1.0` via `release.yml`.

## Repo settings (osm-editor-kit/osm-live-touched) 🧑

- **About**: description, topics (`openstreetmap`, `osm`, `id-editor`, `josm`), and website (npm page, later the demo).
- **General → Pull requests**: allow **rebase merging** (review-dependabot merges with rebase). Optionally allow auto-merge.
- **Branch protection / rulesets for `main`**: `release.yml` pushes a `chore: release [skip ci]` commit to `main`. Either do not require PRs on `main`, or add **GitHub Actions** to the ruleset's bypass list. Otherwise releases fail.
- **Actions → General**: keep defaults. The workflows request their own permissions (`contents: write`, `issues: write`, `id-token: write`).
- **Code security**: enable Dependabot alerts and Dependabot security updates. Version updates come from `.github/dependabot.yml` (first Friday of the month). The dependency graph is on by default for public repos; the CI dependency review needs it.
- **npm** (T11): trusted publisher for `@osm-editor-kit/live-touched` → repo `osm-editor-kit/osm-live-touched`, workflow `release.yml`.
- Same for key-value-db (T8): trusted publisher for `@osm-editor-kit/key-value-db-client` → repo `FixMyBerlin/key-value-db`, workflow `release-client.yml`. If `main` there is protected, allow the release commit too.

## Steps

### M0: scaffold this repo 🤖 ✅ (uncommitted, T1/T4)

- ✅ Root: `package.json` (bun workspaces `packages/*`), `tsconfig.base.json`, oxlint and oxfmt configs, `.gitignore`, `.nvmrc`, `LICENSE` (MIT), `README.md`. Configs are copied from surface-smoothness.
- ✅ `packages/live-touched`: `package.json` (`@osm-editor-kit/live-touched`, ESM, `files: ["dist"]`, `publishConfig`), a `tsc` build like `surface-smoothness-data` (PLAN.md updated: `tsc` instead of Vite), vitest, and a placeholder `src/index.ts` with a test.
- ✅ `.changeset/` (config from maplibre-editor-layer-index), plus `.github/workflows/ci.yml` and `release.yml` (adapted, and not run until T4).
- ✅ `bun install`, then `bun run check` green, plus `build` and `check-exports` green (bun 1.4.0).
- `packages/josm-plugin` is created in M6. A folder without a `package.json` would break the workspace glob.

### M1: key-value-db (branch `live-touched-support`) 🤖 ⏳, then T5–T8

- ✅ Migration `0002_owner_scope_ttl.sql`: `projects.write_scope`, `projects.entry_ttl_s`, `entries.expires_at`, plus indexes for expiry, author lookups, and `verified_tokens.osm_uid`.
- ✅ R1 owner scope (uid prefix plus owner check on PUT and DELETE).
- ✅ R2 TTL: set on insert, reset when expired, read filter, cron every 15 min.
- ✅ R3 `DELETE /me/entries` (this project only).
- ✅ R4 `osm_users` cleanup in the cron.
- ✅ R5 batch as **`POST /v1/projects/{slug}/batch`** (not `/entries:batch`: a `:` in a Hono path starts a route parameter). At most 25 puts and 50 deletes per call, atomic.
- ✅ Admin REST and MCP: the new settings, and the warnings on change.
- ✅ Tests (35 API tests green, 13 new): new features, **compatibility regression** (defaults behave like today), and cross-project delete.
- ✅ R10 client, code part: `batch()`, `removeMine()`, `expires_at` in the type, and dependency-free response checks (`validate.ts`, replacing the copies' zod schemas). 9 client tests green.
- ✅ R10 client, packaging part: renamed to **`@osm-editor-kit/key-value-db-client`**. It is built with `tsc` to `dist`, has an MIT `LICENSE`, a README, and exports that pass node16 and bundler checks. It is released with changesets through `.github/workflows/release-client.yml`, with a `minor` changeset for 0.1.0. The workspace demo uses the TS source through a `source` export condition, so it needs no build step.
- ✅ Committed locally on `live-touched-support` (`bdb5e91` API, `44479e8` client). Not pushed.
- ✅ R8 `PRIVACY.md` (Cloudflare facts checked on the docs pages: Workers Logs 3 days free / 7 days paid; D1 EU jurisdiction runs and stores data in the EU; rate-limit counters live in a per-location cache). R9: `docs/API.md`, `AGENTS.md` (including the compatibility rules), `CLAUDE.md`, a "What it can do" block in the README, and a "historical" note in `PLAN.md`.
- ✅ Found while documenting: `write_access = allowlist` is accepted but **not enforced** (no code uses it). This is now documented as "not enforced yet" in `docs/API.md`; nothing else changed.
- ✅ `bun run check-ci` green (after R1–R5).
- ✅ **Found and fixed an existing bug:** a PUT rejected by `If-Match` (409) still replaced the entry's tag index with the new tags, because the tag statements in the batch ran unconditionally. They now only run when the upsert actually wrote. There is a regression test.
- ✅ Local smoke run: `wrangler dev` plus a stub OSM server (scratchpad `smoke.ts`, 14 scenarios: shared editing, owner scope, `osm_user` reads, batch, TTL, saved, nuke only in this project). The cron runs via `/__scheduled`. A local `live-touched` project exists in local D1.
- ✅ Two more fixes from the smoke run:
  - `deleted` counts were wrong, because D1's `meta.changes` also counts cascaded tag rows. They are now counted with `RETURNING`.
  - Expected 4xx errors were logged with `console.error`, and the owner-scope message included the user id. The app now logs only 5xx, and the message no longer contains the id. `PRIVACY.md` is updated to match.
- 🧑 T5 merge/deploy → 🤖 check `/v1/health` → 🧑 T6 test writes → 🧑 T7 project settings → 🤖 check behavior → 🧑 T8 npm publisher.

### M2: docs in this repo 🤖 ✅ (draft), then T9

- ✅ `docs/concept.md` (flow, states, conflict hints, polling, UI rules, reference texts in English and German).
- ✅ `docs/api.md`.
- ✅ `PRIVACY.md` (Section 7.2 of PLAN.md, with the backend part copied from key-value-db). The FixMyCity imprint link is still missing (T9).

### M3: package `@osm-editor-kit/live-touched` 🤖 ✅ (uncommitted)

- ✅ Modules: `tiles`, `status` (status windows, hints, sort order), `sync` (diff writer, saved, disable, batching), `poller`, and `session` (public API). The client is used directly, so no separate `kvApi.ts` is needed. 20 unit tests, including session tests with a fake server and fake timers. `check`, `build`, and `check-exports` (node16 + bundler) are green.
- ⏸ Until the client is on npm (T8), the package depends on `@osm-editor-kit/key-value-db-client` via `file:../../../../FMC/key-value-db/packages/kv-client`. That path only exists on this machine, so **CI on GitHub fails until the client is published**. Then switch to `^0.1.0`.
- ✅ Local end-to-end run (scratchpad `e2e.ts`): two real sessions (stub OSM users 301/302) against local `wrangler dev`, 6 checks all pass: see each other, `parallel` hint, `saved` v8 plus `outdated` hint, nuke deletes, the other user no longer sees it.
- Known limits (v1): server clock skew is learned from our own write responses (the client does not expose the `Date` header). There is no `keepalive` send on `pagehide`, because the client does not support it.

### M4: hand-off to the iD chat 🤖 ✅, then T10

- ✅ `packages/live-touched/README.md`: the API and the **iD integration guide**, checked against the iD--radnetz-berlin source: `history().difference().summary()`, `uploader()` `resultSuccess`, `map()` `move`/`drawn`, entity classes `.w789`. The token getter is private in `services/osm.js`, so the guide includes a small patch that adds `getAccessToken()`. Open items to check in the iD chat are listed there.
- For the iD chat: point it to `packages/live-touched/README.md` (section "iD integration guide") and `docs/concept.md`.

### M5: release 🧑 T4 + T11, 🤖 prepares

- ⬜ Changeset for `0.1.0` and the release workflow check. Publishing only happens after T11.

### M6: JOSM plugin (later)

## Log

- 2026-09-27: surface-smoothness `license-mit` landed on `main`. key-value-db PR [#2](https://github.com/FixMyBerlin/key-value-db/pull/2) opened (not merged). osm-live-touched `main` pushed. Next: Tobias does T8, then merges #2 (T5).
- 2026-09-27: T1–T3 done. The client is renamed and publishable. This repo has Dependabot (monthly) and a CI dependency review. Waiting: OK for the first push to osm-live-touched, and for pushing key-value-db `live-touched-support` (then review/merge = T5).
- 2026-09-27: M2 docs (draft), M3 package, and the M4 guide are done locally. The e2e run against the local backend is green. **Everything is uncommitted and waits for Tobias (T1–T3, T9).**
- 2026-09-27: M1 code, docs, and the local smoke run are done in key-value-db (uncommitted, branch `live-touched-support`, `check-ci` green: 35 API + 9 client + 4 demo tests). R10 packaging waits for T2. Next: M2 docs and M3 package.
- 2026-09-27: M1 R1–R5 done in key-value-db (uncommitted, branch `live-touched-support`). Next: R10 client, R8/R9 docs, local smoke run.
- 2026-09-27: M0 done locally (uncommitted). Next: M1 in key-value-db on branch `live-touched-support`.
- 2026-09-27: PLAN.md finished. surface-smoothness relicensed to MIT on the local branch `license-mit` (uncommitted, T3). Work plan created.
