# live-touched JOSM plugin

See live which OSM objects other mappers are editing near you, **before** they upload, and share your own edits the same way. It's opt-in. This is the JOSM counterpart of the npm package [`@osm-editor-kit/live-touched`](../live-touched), with the same backend (key-value-db project `live-touched`), the same data format, and the same rules ([docs/concept.md](../../docs/concept.md)). So JOSM and iD users see each other.

Status: 0.1.0, not in the JOSM plugin list yet. Install it by hand (see below).

## What it does

- **Side panel "Live edits nearby"** (Windows menu, or the toggle button on the right):
  - **Turn on / Turn off**. The first time you turn it on, a consent dialog explains what is shared.
  - A list of objects others are editing in the current map view, with a status line ("… is editing this right now", "… edited and saved this (v4)") and conflict hints. Double-click selects the object and zooms to it.
  - **Privacy** opens `PRIVACY.md`.
  - **Delete my data** deletes all your entries on the server and turns the feature off.
  - The panel title shows a dot (●) while others are editing in view.
- **Map highlight**: orange halo = being edited, faint orange = stale, violet = saved recently, red outline = conflict with your own changes or your version.
- **Sharing your edits**:
  - Every 2 s the plugin looks at the objects you modified or deleted in the active data layer (new objects are not shared) and sends the differences. Undo and discard remove your entries again.
  - After a successful upload, the uploaded objects are marked "saved" with the new version and the changeset id.
  - Polling runs every 4–10 s and stops when you zoom out further than about zoom 15.

It uses **JOSM's own OSM login** (OAuth 2.0 in Preferences → Connection Settings). You need to be logged in to openstreetmap.org.

## Install into your JOSM (macOS)

1. **Build** (needs only Docker; the first run downloads Gradle and JOSM 19481):

   ```bash
   cd ~/Development/OSM/osm-live-touched && bun run josm-install
   ```

   This builds `packages/josm-plugin/build/dist/live-touched.jar` and copies it to `~/Library/JOSM/plugins/live-touched.jar`, the folder JOSM reads plugins from on macOS. `bun run josm-build` only builds.

2. **Enable** it in JOSM: *Preferences* (⌘,) → *Plugins* → search `live-touched` → tick it → *OK*. The plugin can load without a restart; if the panel doesn't appear, restart JOSM.

3. **Log in** to OSM in JOSM if you aren't already: *Preferences* → *Connection Settings* → *OAuth 2.0* → *Authorize now*.

4. **Use** it: open *Windows* → *Live edits nearby*, click **Turn on**, accept the dialog, download some data and edit.

To update, run `bun run josm-install` again and restart JOSM. To remove, untick the plugin and delete `~/Library/JOSM/plugins/live-touched.jar`.

Without Docker: the CI workflow (`.github/workflows/josm.yml`) uploads the jar as the build artifact `live-touched-josm-plugin`. Download it and copy it to the same folder.

### Test against a local key-value-db

In JOSM, *Preferences* → *Advanced Preferences*, set:

| Key | Value |
| --- | --- |
| `live-touched.base-url` | `http://127.0.0.1:8787` |
| `live-touched.api-key` | the local project's `api_key` |
| `live-touched.origin` | `https://josm.openstreetmap.de` (must be in the local project's origins) |

Remove the keys to go back to production.

## Develop

```bash
bun run josm-build
```

This runs `gradle build` in the Docker image `gradle:8.14-jdk21`, which compiles against JOSM 19481 (Java 11 target) and runs the unit tests. With a local JDK and Gradle you can also run `gradle build` or `gradle runJosm` (an isolated JOSM with only this plugin) in this folder.

- Code: `src/main/java/org/openstreetmap/josm/plugins/livetouched/`
  - `Tiles`, `Rules`, and `SyncState` are ports of the npm package's logic, tested in `src/test`.
  - `KvApi` is the HTTP client. It uses `java.net.http`, because JOSM's own HTTP client cannot set the `Origin` header.
  - `Session` covers sync, polling, and state.
  - `Controller` is the bridge to JOSM: modified objects, viewport, and the upload hook.
  - `LiveTouchedDialog` and `HighlightPainter` are the UI.
- Contract test against a local backend (skipped unless configured). Start key-value-db `wrangler dev` with `OSM_API_BASE` pointing to a stub that accepts `tok-<uid>` tokens, then run:

  ```bash
  docker run --rm -e LT_KV_BASE_URL=http://host.docker.internal:8787 -e LT_KV_API_KEY=kv_… \
    -v "$PWD":/home/gradle/project -w /home/gradle/project -v live-touched-gradle:/home/gradle/.gradle \
    gradle:8.14-jdk21 gradle --no-daemon test --tests '*LocalBackendContractTest*'
  ```

- The version is set in `gradle.properties`. It is not taken from `git describe`, which would pick up the npm tags of this monorepo.

## Known limits (0.1.0)

- Only the active data layer is watched. Objects in other layers are neither shared nor highlighted.
- "Saved" is detected when an uploaded object comes back unmodified with a higher version, or disappears (deleted). Closing a layer right after uploading may skip that.
- JOSM sends its OSM token (which can write to OSM) to the backend, like the web editors do. See [docs/followup-token-auth.md](../../docs/followup-token-auth.md).
- Texts are English and use `tr()`, so they are ready for JOSM translations.

## License

[MIT](LICENSE)
