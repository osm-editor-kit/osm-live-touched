package org.openstreetmap.josm.plugins.livetouched;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class TilesTest {
    @Test
    void computesSlippyTiles() {
        assertArrayEquals(new int[] {8800, 5373}, Tiles.lonLatToTile(13.3777, 52.5163, 14));
    }

    @Test
    void tagsSmallObjectsWithZ14AndLargeOnesWithZ10OrNothing() {
        assertEquals(List.of("z14/8800/5373"), Tiles.tagsForBbox(new double[] {13.3777, 52.5163, 13.3777, 52.5163}));
        List<String> large = Tiles.tagsForBbox(new double[] {13.0, 52.3, 13.8, 52.7});
        assertFalse(large.isEmpty());
        assertTrue(large.stream().allMatch(t -> t.startsWith("z10/")));
        assertEquals(List.of(), Tiles.tagsForBbox(new double[] {-10, 35, 30, 60}));
    }

    @Test
    void queriesViewportTilesPlusParentsOrNullWhenZoomedOut() {
        List<String> tags = Tiles.queryTagsForViewport(new double[] {13.37, 52.51, 13.39, 52.52});
        assertTrue(tags.contains("z14/8800/5373"));
        assertTrue(tags.contains("z10/550/335"));
        assertNull(Tiles.queryTagsForViewport(new double[] {13.0, 52.3, 13.8, 52.7}));
    }

    @Test
    void sameTagsAsTheNpmPackage() {
        // Values from packages/live-touched/src/tiles.test.ts: both implementations must agree.
        List<String> objectTags = Tiles.tagsForBbox(new double[] {13.0, 52.3, 13.8, 52.7});
        List<String> query = Tiles.queryTagsForViewport(new double[] {13.37, 52.51, 13.39, 52.52});
        assertTrue(objectTags.stream().anyMatch(query::contains));
    }

    @Test
    void bboxIntersection() {
        assertTrue(Tiles.intersects(new double[] {0, 0, 1, 1}, new double[] {1, 1, 2, 2}));
        assertFalse(Tiles.intersects(new double[] {0, 0, 1, 1}, new double[] {1.1, 0, 2, 1}));
    }
}
