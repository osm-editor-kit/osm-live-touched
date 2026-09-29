package org.openstreetmap.josm.plugins.livetouched;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.livetouched.Model.Hint;
import org.openstreetmap.josm.plugins.livetouched.Model.LiveItem;
import org.openstreetmap.josm.plugins.livetouched.Model.OsmType;
import org.openstreetmap.josm.plugins.livetouched.Model.Status;

class RulesTest {
    @Test
    void statusWindows() {
        long m = Rules.MINUTE;
        assertEquals(Status.TOUCHED, Rules.statusOf("touched", 29 * m));
        assertEquals(Status.STALE, Rules.statusOf("touched", 30 * m));
        assertEquals(Status.HIDDEN, Rules.statusOf("touched", 60 * m));
        assertEquals(Status.SAVED, Rules.statusOf("saved", 119 * m));
        assertEquals(Status.HIDDEN, Rules.statusOf("saved", 120 * m));
    }

    @Test
    void hints() {
        assertEquals(Hint.PARALLEL, Rules.hintFor(Status.TOUCHED, null, true, null));
        assertEquals(Hint.NONE, Rules.hintFor(Status.STALE, null, false, null));
        assertEquals(Hint.OUTDATED, Rules.hintFor(Status.SAVED, 4, false, 3));
        assertEquals(Hint.OK, Rules.hintFor(Status.SAVED, 4, true, 4));
        assertEquals(Hint.NONE, Rules.hintFor(Status.SAVED, 4, false, null));
    }

    private static LiveItem item(long id, Status status, Hint hint, long ageMs) {
        return new LiveItem(OsmType.WAY, id, 1, "u", status, ageMs, new double[4], 1, null, null, hint, null);
    }

    @Test
    void sortOrder() {
        List<LiveItem> items = new ArrayList<>(List.of(
                item(1, Status.SAVED, Hint.OK, 1),
                item(2, Status.STALE, Hint.NONE, 1),
                item(3, Status.TOUCHED, Hint.NONE, 9),
                item(4, Status.TOUCHED, Hint.NONE, 2),
                item(5, Status.SAVED, Hint.OUTDATED, 5),
                item(6, Status.TOUCHED, Hint.PARALLEL, 5)));
        items.sort(Rules.ORDER);
        assertEquals(List.of(6L, 5L, 4L, 3L, 2L, 1L), items.stream().map(i -> i.id).collect(Collectors.toList()));
    }
}
