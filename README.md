# osm-live-touched

Mappers see **live** which OpenStreetMap objects other mappers are editing near them, **before** those edits are uploaded. This helps avoid edit conflicts and shows what is happening in an area right now. Opt-in, off by default.

Part of the [`osm-editor-kit`](https://github.com/osm-editor-kit) family. [FixMyCity](https://fixmycity.de) supports the project with development time and runs the backend.

| Path | What |
| --- | --- |
| [`packages/live-touched`](packages/live-touched) | npm [`@osm-editor-kit/live-touched`](https://www.npmjs.com/package/@osm-editor-kit/live-touched): editor-agnostic wiring (API, sync, polling, status). No UI. |
| `packages/josm-plugin` | JOSM plugin (later) |
| [`PRIVACY.md`](PRIVACY.md) | Privacy statement (M2) |
| [`PLAN.md`](PLAN.md) | Implementation plan |
| [`WORKPLAN.md`](WORKPLAN.md) | Work plan: order, status, and touchpoints |

**Backend:** [key-value-db](https://github.com/FixMyBerlin/key-value-db), project `live-touched`, at `https://key-value-store.fixmycity.workers.dev` ([health](https://key-value-store.fixmycity.workers.dev/v1/health)).

## Develop

```bash
bun install
bun run check
bun run build
```

## License

[MIT](LICENSE). The key-value-db server is a separate project under AGPL-3.0.
