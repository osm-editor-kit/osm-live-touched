# Follow-up plan: never send a write-capable OSM token to key-value-db

Status: **parked** (2026-09-27). Not planned for now. This file keeps the problem, the facts we checked, and the options, so the work can start from here later. The idea came from the iD--radnetz-berlin session (see its `WORKDOC.md`, "live-touched token question").

## Problem

Today an editor sends **its own OSM access token** to key-value-db in `Authorization: Bearer …`. key-value-db calls `/api/0.6/user/details.json` with it to learn the user id and name.

- Editor tokens can **write to OSM**: iD requests `read_prefs write_prefs write_api read_gpx write_notes` (`modules/services/osm.js`).
- OSM access tokens **never expire** (`access_token_expires_in nil` in openstreetmap-website `config/initializers/doorkeeper.rb`), and OSM reuses them (`reuse_access_token`). A leaked token stays valid until the user revokes the app.
- key-value-db never stores the token (only a SHA-256 hash), never logs it, and no longer logs 4xx errors. But it still **receives** it. A bug, a malicious deploy, or unexpected Cloudflare request logging would expose tokens that can edit OSM.
- The existing key-value-db apps (knotenpunkte, parkraum-zaehlung, the demo) only request `read_prefs`, so their tokens cannot write. **The risk is specific to editors** such as iD, Rapid, and JOSM, which is exactly the live-touched case.

Goal: key-value-db still learns the verified OSM user id and display name, but **never receives a token that can write to OSM**. `PRIVACY.md` could then say exactly that.

## Facts checked on 2026-09-27

From `https://www.openstreetmap.org/.well-known/openid-configuration` and the openstreetmap-website source (local checkout `5cdde11`, `doorkeeper-openid_connect 1.9.0`):

| Fact | Value |
| --- | --- |
| OpenID Connect | supported. `openid` is in `scopes_supported` |
| Grant / response type | `authorization_code` / `code` only (no implicit flow) |
| PKCE | supported (`code_challenge_methods_supported`). osm-auth already uses PKCE |
| Client auth at the token endpoint | `client_secret_basic`, `client_secret_post` (confidential clients possible) |
| id_token signing | `RS256`. Public keys at `https://www.openstreetmap.org/oauth2/discovery/keys` (one RSA key, `kid` set) |
| Claims | `iss`, `sub`, `aud`, `exp`, `iat`, `preferred_username`, `email` (with `read_email` only) |
| `sub` | the OSM user id (`resource_owner.id`) |
| `preferred_username` | the OSM display name (in the id_token and in userinfo, scope `openid`) |
| `iss` | `https://www.openstreetmap.org` |
| id_token lifetime | not set in OSM's config, so the gem default applies (**120 s** in doorkeeper-openid_connect, *verify with a real token*) |
| Other endpoints | `userinfo`, `introspect`, and `revoke` exist. Userinfo still needs the access token, so it does not help here |
| Scope enforcement | `enforce_configured_scopes`: an app can only request scopes it was **registered** with |
| osm-auth 3.x | the token callback stores only `access_token` (`token("oauth2_access_token", access_token.access_token)`) and **drops `id_token`** |
| iD fork | uses iD's official OAuth apps (`config/id.js`) unless `ID_API_CONNECTION_CLIENT_ID` is set |

## Options

### A. key-value-db signs users in itself (its own OSM app, `openid` only)

1. key-value-db registers its own **confidential** OSM OAuth2 app with scope `openid` only. It needs no `read_prefs`: `sub` and `preferred_username` come in the id_token.
2. The editor opens a popup at `https://<kv-host>/v1/auth/start?project=…`. The Worker redirects to OSM `/oauth2/authorize` (with PKCE and `state`). OSM redirects back to `/v1/auth/callback`. The Worker exchanges the code with its client secret, verifies the id_token, and **discards the OSM access token right away**. With scope `openid` only, that token cannot do anything useful anyway.
3. The Worker issues its **own session token**, which is short-lived (e.g. 12 h), scoped to one project, stored hashed, and revocable. It hands the token to the editor through `postMessage` from the popup, the same pattern osm-auth uses.
4. The editor sends that session token as `Authorization: Bearer …`.

- Pros: works for any client, including JOSM and third-party builds. The OSM token never leaves OSM ↔ Worker.
- Cons: a second sign-in in each editor ("Sign in for live edits"). After the first approval OSM remembers the app, so it becomes one click. `skip_authorization` is a privileged scope and is not an option. The Worker gets a client secret (a Worker secret, like `ADMIN_KEYS_JSON`).
- **No cookies**: editors run on other sites (127.0.0.1, Netlify, openstreetmap.org), so a key-value-db cookie is third-party. Safari and Firefox block those, and partitioned cookies (CHIPS) would live in the popup's partition, not in the editor's. A bearer token via `postMessage` works everywhere and fits the existing origin allowlist.

### B. The editor sends only its OpenID Connect id_token (no second login)

1. The editor's OAuth app is registered with `openid` in addition to its scopes and requests it. OSM then returns an `id_token` (JWT) next to the access token.
2. The editor sends **only the id_token** to key-value-db (e.g. `POST /v1/auth/exchange`).
3. The Worker verifies it:
   - the RS256 signature, against the cached JWKS (WebCrypto `RSASSA-PKCS1-v1_5` / SHA-256, no dependency);
   - `iss = https://www.openstreetmap.org`;
   - `aud` is one of the **trusted editor client ids**, a list per project that only the admin can change, like `origins`;
   - `exp` and `iat`, with a little clock leeway.
4. The Worker then issues its own session token, as in A. The id_token lives only ~120 s, so it is used once.

- Pros: no extra login. An id_token cannot call the OSM API, so it is harmless if it leaks.
- Cons:
  - The editor's OAuth app must be **registered with `openid`**. For iD's official app that is up to the iD/OSMF maintainers. Our iD fork can use its own app via `ID_API_CONNECTION_CLIENT_ID`.
  - **osm-auth drops the id_token**. We would need a small patch or PR, e.g. storing `id_token` and adding `oauth.getIdToken()`.
  - The id_token is only available right after login, not on later page loads, because osm-auth reuses the stored access token without a new token request. So the editor must exchange it once and keep the key-value-db session token. When that expires, the user logs in to OSM again, or falls back to A.

### Recommendation

- **B as the main path for web editors we control** (our iD build, the TILDA tools): no second login.
- **A as the fallback** for everything else: JOSM, other people's builds, and editors without `openid`.
- Both end in the same **key-value-db session token**, so the rest of the API (owner scope, TTL, batch, delete my data) stays unchanged.

## Changes this would need

**key-value-db** (generic and opt-in per project, following the compatibility rules in `AGENTS.md`):

- New table `sessions`: `token_hash`, `osm_uid`, `project_id`, `created_at`, `expires_at`, and optionally `client`. The 15-min cleanup purges expired rows.
- New project settings (admin-only): `auth_modes` (`osm_token` = today, `session`), `trusted_client_ids` (for B), and `session_ttl_s`.
- Routes: `POST /v1/projects/{slug}/auth/exchange` (B), `GET /v1/auth/start` plus `GET /v1/auth/callback` (A, popup and `postMessage` to an allowlisted origin), and `DELETE /v1/projects/{slug}/me` also drops the session.
- `requireOsm` accepts a session token when the project allows it. For projects that only allow `session`, raw OSM tokens are rejected with `401`, so editors cannot fall back to sending write tokens.
- New secrets: the OSM app client secret (for A).
- JWKS caching (Workers cache, refresh on an unknown `kid`).
- Client: `exchangeIdToken()`, `startLogin()` (popup helper), and session handling.
- Docs: `docs/API.md` and `PRIVACY.md` ("we never receive a token that can edit OSM" for session-only projects).

**osm-live-touched**:

- `getOsmToken` becomes `getSessionToken`, or the session gets an `auth` option: `{ idToken }` for B or `{ popup: true }` for A.
- The consent flow can include the key-value-db sign-in (A).
- `PRIVACY.md` and `docs/api.md` updated.

**Editors**:

- iD fork: an own OAuth app with `openid` (env `ID_API_CONNECTION_CLIENT_ID`), `openid` added to the scope in `services/osm.js`, and osm-auth patched to keep the id_token. Also a "Sign in for live edits" button for fallback A.
- JOSM: A only. The plugin opens the key-value-db login in the system browser and needs a loopback redirect, or the user copies a code. To be designed in M6.

**Existing apps** (knotenpunkte, parkraum-zaehlung): no change needed. They keep `auth_modes = osm_token`. Their tokens are `read_prefs` only.

## Open checks before building

1. Get a real id_token (a test app with `openid`) and confirm the lifetime (expected 120 s), the claims, and that `aud` is the client id.
2. Can an iD build use its own OAuth app on openstreetmap.org without problems (redirect URIs for 127.0.0.1 and the deploy host)?
3. osm-auth: PR upstream to keep `id_token`, or a local patch in the fork?
4. Does OSM also return an id_token for PKCE public clients (no secret)? Expected yes, because the scope decides. Verify.
5. Session lifetime: long enough to avoid annoying re-logins (a mapping session), short enough to limit a leak. 12 h is a first guess.
6. Is it worth also offering B to the existing apps? Probably not: their tokens are read-only already.
