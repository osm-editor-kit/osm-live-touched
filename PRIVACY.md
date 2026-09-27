# Privacy: live touched

This statement covers the "live touched" feature (see live edits nearby) in editors that use `@osm-editor-kit/live-touched`, and its backend project `live-touched` on key-value-db.

**Controller:** FixMyCity GmbH. Contact details: see FixMyCity's imprint and privacy page (link to be added).

## In short

- **Off by default.** Nothing is sent before you turn the feature on and agree.
- **What is sent** per object you edit: your OSM user id, the object type and id, a bounding box, the version you started from, the status (`touched` or `saved`), after saving the new version and changeset id, timestamps, and the editor name. **No tags, no geometry, no other content.**
- **Who sees it:** anyone who is logged in with OSM and queries the same map area through this project. In practice that means other people using the feature. We cannot limit it to people who agreed. Only you can change or delete your entries.
- **What is new:** others see your edits **before** you upload. After upload, your username and edits are public in OSM anyway.
- **How long:** every entry is gone **3 hours after you first touched the object at the latest**. It is hidden right away and deleted from the database within 15 minutes. It can be earlier: when you undo, when you discard your changes, or when you turn the feature off.
- **Delete my data:** this button deletes all your live-touched entries and your cached login check right away, and turns the feature off. It does not touch other apps that use the same backend.
- **Purpose and legal basis:** avoiding edit conflicts and coordinating mapping, based on your consent (GDPR Art. 6(1)(a)). You can withdraw it any time with "Delete my data" and by turning the feature off.

## Your OSM login

The editor sends your OSM login token to the backend, so the backend can confirm who you are. The token could also be used to write to OSM. The backend only uses it to read your user id and display name from `api.openstreetmap.org`, and it never stores the token (only a hash of it, see below).

## Backend details

> Source: [key-value-db `PRIVACY.md`](https://github.com/FixMyBerlin/key-value-db/blob/main/PRIVACY.md), copied on 2026-09-27 (branch `live-touched-support`, not yet merged). Update this section when that file changes.

The backend is key-value-db on Cloudflare. Cloudflare acts as processor ([DPA](https://www.cloudflare.com/cloudflare-customer-dpa/), [privacy policy](https://www.cloudflare.com/privacypolicy/)).

| Service | What it does here |
| --- | --- |
| [Workers](https://developers.cloudflare.com/workers/) | Runs the API at Cloudflare's edge worldwide. Each request, including its IP address, is handled at the nearest Cloudflare location |
| [D1, EU jurisdiction](https://developers.cloudflare.com/d1/configuration/data-location/) | The database. It runs and stores its data only inside the EU |
| [Rate limiting](https://developers.cloudflare.com/workers/runtime-apis/bindings/rate-limit/) | Counts login checks per IP for 60 s in Cloudflare's rate-limit cache, not in our database |
| [Workers Logs](https://developers.cloudflare.com/workers/observability/logs/workers-logs/) | Request logs and server errors, kept 3 days (free plan) |
| [Cron Triggers](https://developers.cloudflare.com/workers/configuration/cron-triggers/) | Runs the cleanup job every 15 minutes |

| Data | Content | Kept |
| --- | --- | --- |
| Entries | See "What is sent" above | Max 3 h (this project's setting), or until you delete them |
| User record | OSM user id, display name, first and last seen | Deleted within 15 min once no entry in **any** key-value-db app and no cached login check refers to you. If you also use another app on the same backend and it still has data from you, the record stays as long as that data exists |
| Login check cache | SHA-256 hash of your OSM token, your user id, and the time of the check. Never the token itself | 24 h after the last check, or right away with "Delete my data" |
| Logs | Request metadata recorded by Cloudflare (method, URL path, status, duration) and server errors. Never the token | 3 days |

The app does not store IP addresses.
