import type { Bbox } from './types.js'

// Tile index for area queries: entries carry the slippy-map tiles their bbox touches as tags
// (`z14/x/y`, or `z10/x/y` for large objects). A viewport query asks for its z14 tiles plus
// their z10 parents with `match=any`, then filters the exact bbox on the client.

export const FINE_ZOOM = 14
export const COARSE_ZOOM = 10
/** Max tiles an entry may carry per zoom level. */
export const MAX_ENTRY_TILES = 16
/** Max z14 tiles a viewport may cover before we stop polling ("zoom in"). */
export const MAX_VIEWPORT_TILES = 36

const MAX_LAT = 85.05112878

function clampLat(lat: number) {
  return Math.max(-MAX_LAT, Math.min(MAX_LAT, lat))
}

export function lonLatToTile(lon: number, lat: number, z: number): [number, number] {
  const n = 2 ** z
  const x = Math.floor(((lon + 180) / 360) * n)
  const rad = (clampLat(lat) * Math.PI) / 180
  const y = Math.floor(((1 - Math.log(Math.tan(rad) + 1 / Math.cos(rad)) / Math.PI) / 2) * n)
  return [Math.min(Math.max(x, 0), n - 1), Math.min(Math.max(y, 0), n - 1)]
}

/** Tiles at zoom `z` that intersect the bbox, or null if more than `max`. */
export function tilesForBbox(bbox: Bbox, z: number, max: number): string[] | null {
  const [minLon, minLat, maxLon, maxLat] = bbox
  const [x0, y0] = lonLatToTile(minLon, maxLat, z)
  const [x1, y1] = lonLatToTile(maxLon, minLat, z)
  const count = (x1 - x0 + 1) * (y1 - y0 + 1)
  if (count > max) return null
  const tiles: string[] = []
  for (let x = x0; x <= x1; x++) for (let y = y0; y <= y1; y++) tiles.push(`z${z}/${x}/${y}`)
  return tiles
}

/** Tags for an entry: z14 tiles, else z10 tiles, else none (object too large to share). */
export function tagsForBbox(bbox: Bbox): string[] {
  return (
    tilesForBbox(bbox, FINE_ZOOM, MAX_ENTRY_TILES) ??
    tilesForBbox(bbox, COARSE_ZOOM, MAX_ENTRY_TILES) ??
    []
  )
}

/** Tags to query for a viewport: its z14 tiles plus their z10 parents; null = zoom in. */
export function queryTagsForViewport(bbox: Bbox): string[] | null {
  const fine = tilesForBbox(bbox, FINE_ZOOM, MAX_VIEWPORT_TILES)
  if (!fine) return null
  const parents = new Set<string>()
  const shift = 2 ** (FINE_ZOOM - COARSE_ZOOM)
  for (const tile of fine) {
    const [, x, y] = tile.split('/').map(Number) as [number, number, number]
    parents.add(`z${COARSE_ZOOM}/${Math.floor(x / shift)}/${Math.floor(y / shift)}`)
  }
  return [...fine, ...parents]
}

export function bboxIntersects(a: Bbox, b: Bbox): boolean {
  return a[0] <= b[2] && a[2] >= b[0] && a[1] <= b[3] && a[3] >= b[1]
}

export function roundBbox(bbox: Bbox): Bbox {
  const r = (n: number) => Math.round(n * 1e6) / 1e6
  return [r(bbox[0]), r(bbox[1]), r(bbox[2]), r(bbox[3])]
}
