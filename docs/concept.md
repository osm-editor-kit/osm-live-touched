# Concept: live touched

This spec applies to every editor. iD, Rapid, and JOSM implement the same behavior; the npm package `@osm-editor-kit/live-touched` implements the non-UI part for web editors.

## Flow

1. **Opt-in.** The feature is off by default. The first time it is turned on, the editor shows the consent dialog (see "UI"). Nothing is sent before the user agrees.
2. **Send.** The editor reports the full set of objects the user has changed locally (positive ids only). The client compares it with what it sent before:
   - new or changed objects are written (`status: touched`),
   - objects that are no longer changed (undo, discard) are deleted.
   Writes are debounced for 2 s and sent as one batch.
3. **Receive.** At the same time the client polls the current map view and shows objects that **other** users are editing.
4. **Save.** After a successful upload the user's entries become `status: saved` with `new_version = base_version + 1` and the changeset id.
5. **Stop.** Turning the feature off deletes the user's `touched` entries (best effort). **"Delete my data"** deletes all the user's entries in this project, drops the token cache, and turns the feature off.

The server deletes every entry 3 h after it was first written, whatever happens on the client.

## States and time windows

The client computes the status from the entry's `data.status` and times. Times use the server clock (from the `Date` response header), so client clocks that are off do not matter.

| Status | Condition | Shown |
| --- | --- | --- |
| `touched` | not saved, last change < 30 min ago | yes |
| `stale` | not saved, last change 30–60 min ago | yes, muted |
| hidden | not saved, last change > 60 min ago | no |
| `saved` | saved < 2 h ago | yes |
| gone | 3 h after the first write | deleted by the server |

## Conflict hints

For each entry of another user the client compares it with the local state:

| Hint | When |
| --- | --- |
| `parallel` | Another user has `touched` an object that I have also changed. Highest priority |
| `outdated` | Another user `saved` a version newer than the one I loaded (`new_version` > my version) |
| `ok` | Another user `saved`, and my version is the same or newer |
| `none` | `touched` or `stale`, and I have not changed the object |

Sort order in the list: `parallel`, then `outdated`, then `touched`, `stale`, `saved`. Within a group, newest first.

## Polling

- 8 s at the start. 4 s while others are in view. 10 s after 3 empty answers in a row.
- Pause while the tab or editor is in the background, the feature is off, or the map is zoomed out too far. Stop sending too.
- On `429` or `5xx`: back off (double the interval, max 60 s), then return to normal after a success.
- Query area: the visible map, as z14 tiles plus their z10 parents (see `api.md`). If the view covers more than 36 z14 tiles, do not poll and show "Zoom in to see live edits".

## UI rules

Each editor builds this in its own UI style and i18n.

**Map**: objects of other users are highlighted like a selection, in their own color:

| Status | Style |
| --- | --- |
| `touched` | orange halo |
| `stale` | orange halo, dashed or faded |
| `saved` | violet halo |
| `parallel` / `outdated` | red outline in addition |

**List panel**: one row per object, with the object id and type, a name or short tag summary from the editor's own data, the username (linked to `https://www.openstreetmap.org/user/<name>`), the status, the age ("4 min ago"), and the hint. Clicking a row selects the object and zooms to it. The footer has the privacy link and the **"Delete my data on the server"** button (with a confirmation, and afterwards the number of deleted entries).

**Indicator**: a small pulsing dot on the feature's button while others are in view.

**Consent dialog**: shown before the first use, and again when the consent version changes.

## Reference texts

Editors translate these through their own i18n. Placeholders are in `{braces}`.

| Key | English | German |
| --- | --- | --- |
| `title` | Live edits nearby | Live-Bearbeitungen in der Nähe |
| `toggle` | Show and share live edits | Live-Bearbeitungen anzeigen und teilen |
| `consent.body` | While this is on, other mappers who also use it can see which objects you are editing, with your OSM username, **before you upload**. Only object ids, versions, and a rough location are sent. Everything is deleted after 3 hours at the latest. | Solange dies aktiv ist, sehen andere Mapper, die die Funktion ebenfalls nutzen, welche Objekte du **vor dem Hochladen** bearbeitest, mit deinem OSM-Benutzernamen. Übertragen werden nur Objekt-IDs, Versionen und eine grobe Position. Alles wird spätestens nach 3 Stunden gelöscht. |
| `consent.accept` | Turn on | Einschalten |
| `consent.privacy` | Privacy details | Details zum Datenschutz |
| `status.touched` | {user} is editing this right now | {user} bearbeitet das gerade |
| `status.stale` | {user} edited this recently; unclear if saved or discarded | {user} hat das kürzlich bearbeitet; unklar, ob gespeichert oder verworfen |
| `status.saved` | {user} edited and saved this (v{version}) | {user} hat das bearbeitet und gespeichert (v{version}) |
| `hint.parallel` | {user} is editing this object at the same time as you | {user} bearbeitet dieses Objekt gleichzeitig mit dir |
| `hint.outdated` | Careful: you are on v{local}, {user} saved v{remote}. Save soon to avoid conflicts | Vorsicht: Du arbeitest auf v{local}, {user} hat v{remote} gespeichert. Am besten bald speichern, um Konflikte zu vermeiden |
| `hint.ok` | Your version is current | Deine Version ist aktuell |
| `zoomIn` | Zoom in to see live edits | Zum Anzeigen näher heranzoomen |
| `empty` | Nobody else is editing here right now | Hier bearbeitet gerade niemand sonst |
| `nuke.button` | Delete my data on the server | Meine Daten auf dem Server löschen |
| `nuke.confirm` | Delete all your live-edit entries on the server and turn this off? | Alle deine Live-Einträge auf dem Server löschen und die Funktion ausschalten? |
| `nuke.done` | Deleted {count} entries | {count} Einträge gelöscht |
| `age` | {minutes} min ago | vor {minutes} Min. |
