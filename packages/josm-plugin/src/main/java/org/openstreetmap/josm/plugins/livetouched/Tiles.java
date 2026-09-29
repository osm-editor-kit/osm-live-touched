package org.openstreetmap.josm.plugins.livetouched;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Tile index for area queries, same rules as the npm package: entries carry the z14 tiles
 * their bbox touches as tags (z10 for large objects); a viewport query asks for its z14
 * tiles plus their z10 parents with match=any.
 */
final class Tiles {
    static final int FINE_ZOOM = 14;
    static final int COARSE_ZOOM = 10;
    static final int MAX_ENTRY_TILES = 16;
    static final int MAX_VIEWPORT_TILES = 36;
    private static final double MAX_LAT = 85.05112878;

    private Tiles() {
    }

    static int[] lonLatToTile(double lon, double lat, int z) {
        int n = 1 << z;
        double clamped = Math.max(-MAX_LAT, Math.min(MAX_LAT, lat));
        int x = (int) Math.floor((lon + 180) / 360 * n);
        double rad = Math.toRadians(clamped);
        int y = (int) Math.floor((1 - Math.log(Math.tan(rad) + 1 / Math.cos(rad)) / Math.PI) / 2 * n);
        return new int[] {Math.min(Math.max(x, 0), n - 1), Math.min(Math.max(y, 0), n - 1)};
    }

    /** Tiles at zoom z that intersect the bbox, or null if more than max. */
    static List<String> tilesForBbox(double[] bbox, int z, int max) {
        int[] topLeft = lonLatToTile(bbox[0], bbox[3], z);
        int[] bottomRight = lonLatToTile(bbox[2], bbox[1], z);
        long count = (long) (bottomRight[0] - topLeft[0] + 1) * (bottomRight[1] - topLeft[1] + 1);
        if (count > max) return null;
        List<String> tiles = new ArrayList<>();
        for (int x = topLeft[0]; x <= bottomRight[0]; x++) {
            for (int y = topLeft[1]; y <= bottomRight[1]; y++) {
                tiles.add("z" + z + "/" + x + "/" + y);
            }
        }
        return tiles;
    }

    /** Tags for an entry: z14 tiles, else z10 tiles, else none (object too large to share). */
    static List<String> tagsForBbox(double[] bbox) {
        List<String> fine = tilesForBbox(bbox, FINE_ZOOM, MAX_ENTRY_TILES);
        if (fine != null) return fine;
        List<String> coarse = tilesForBbox(bbox, COARSE_ZOOM, MAX_ENTRY_TILES);
        return coarse != null ? coarse : List.of();
    }

    /** Tags to query for a viewport: its z14 tiles plus their z10 parents; null = zoom in. */
    static List<String> queryTagsForViewport(double[] bbox) {
        List<String> fine = tilesForBbox(bbox, FINE_ZOOM, MAX_VIEWPORT_TILES);
        if (fine == null) return null;
        Set<String> parents = new LinkedHashSet<>();
        int shift = 1 << (FINE_ZOOM - COARSE_ZOOM);
        for (String tile : fine) {
            String[] parts = tile.split("/");
            int x = Integer.parseInt(parts[1]);
            int y = Integer.parseInt(parts[2]);
            parents.add("z" + COARSE_ZOOM + "/" + (x / shift) + "/" + (y / shift));
        }
        List<String> all = new ArrayList<>(fine);
        all.addAll(parents);
        return all;
    }

    static boolean intersects(double[] a, double[] b) {
        return a[0] <= b[2] && a[2] >= b[0] && a[1] <= b[3] && a[3] >= b[1];
    }

    static double[] round(double[] bbox) {
        double[] r = new double[4];
        for (int i = 0; i < 4; i++) r[i] = Math.round(bbox[i] * 1e6) / 1e6;
        return r;
    }
}
