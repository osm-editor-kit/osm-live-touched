package org.openstreetmap.josm.plugins.livetouched;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.livetouched.Model.LocalObject;
import org.openstreetmap.josm.plugins.livetouched.Model.OsmType;

class SyncStateTest {
    private static final String NOW = "2026-09-27T10:00:00Z";
    private static final LocalObject WAY =
            new LocalObject(OsmType.WAY, 789, 3, new double[] {13.3777, 52.5163, 13.378, 52.5165});
    private static final LocalObject NODE =
            new LocalObject(OsmType.NODE, 1, 1, new double[] {13.3777, 52.5163, 13.3777, 52.5163});

    private static List<String> ids(SyncState.Ops ops) {
        return ops.put.stream().map(p -> p.id).collect(Collectors.toList());
    }

    @Test
    void putsNewSkipsUnchangedDeletesRemoved() {
        SyncState sync = new SyncState(42);
        SyncState.Ops first = sync.plan(List.of(WAY, NODE), NOW);
        assertEquals(List.of("42/w789", "42/n1"), ids(first));
        assertEquals("touched", first.put.get(0).status);
        assertEquals(List.of("z14/8800/5373"), first.put.get(0).tags);
        sync.commit(first);

        assertTrue(sync.plan(List.of(WAY, NODE), NOW).isEmpty());

        LocalObject moved = new LocalObject(OsmType.WAY, 789, 3, new double[] {13.4, 52.5163, 13.41, 52.5165});
        SyncState.Ops second = sync.plan(List.of(moved), NOW);
        assertEquals(List.of("42/w789"), ids(second));
        assertEquals(List.of("42/n1"), second.delete);
    }

    @Test
    void ignoresNewAndInvalidObjects() {
        SyncState sync = new SyncState(42);
        assertTrue(sync.plan(List.of(
                new LocalObject(OsmType.NODE, -5, 1, NODE.bbox),
                new LocalObject(OsmType.NODE, 5, 0, NODE.bbox)), NOW).isEmpty());
    }

    @Test
    void savedEntriesStayWhenTheModifiedSetEmpties() {
        SyncState sync = new SyncState(42);
        sync.commit(sync.plan(List.of(WAY), NOW));
        Map<String, Long> saved = new HashMap<>();
        saved.put("w789", 1234L);
        SyncState.Ops ops = sync.planSaved(saved, NOW);
        assertEquals("saved", ops.put.get(0).status);
        assertEquals(4, ops.put.get(0).newVersion);
        assertEquals(1234L, ops.put.get(0).changesetId);
        sync.commit(ops);
        assertTrue(sync.plan(List.of(), NOW).isEmpty());
        assertTrue(sync.planDisable().isEmpty());
    }

    @Test
    void onlyUploadedObjectsBecomeSaved() {
        SyncState sync = new SyncState(42);
        sync.commit(sync.plan(List.of(WAY, NODE), NOW));
        Map<String, Long> saved = new HashMap<>();
        saved.put("n1", null);
        SyncState.Ops ops = sync.planSaved(saved, NOW);
        assertEquals(List.of("42/n1"), ids(ops));
        assertEquals(null, ops.put.get(0).changesetId);
    }

    @Test
    void disableDeletesTouchedEntries() {
        SyncState sync = new SyncState(42);
        sync.commit(sync.plan(List.of(WAY, NODE), NOW));
        List<String> deleted = new ArrayList<>(sync.planDisable().delete);
        Collections.sort(deleted);
        assertEquals(List.of("42/n1", "42/w789"), deleted);
    }

    @Test
    void chunksIntoApiSizedBatches() {
        List<SyncState.Put> put = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            put.add(new SyncState.Put("p" + i, OsmType.NODE, i + 1, NODE.bbox, 1, "touched", null, null, NOW, List.of()));
        }
        List<String> delete = new ArrayList<>();
        for (int i = 0; i < 60; i++) delete.add("d" + i);
        List<SyncState.Ops> batches = SyncState.chunk(new SyncState.Ops(put, delete));
        assertEquals(2, batches.size());
        assertEquals(25, batches.get(0).put.size());
        assertEquals(50, batches.get(0).delete.size());
        assertEquals(5, batches.get(1).put.size());
        assertEquals(10, batches.get(1).delete.size());
    }

    @Test
    void pollDelays() {
        assertEquals(Session.POLL_START_MS, Session.nextPollDelay(false, 0, 0));
        assertEquals(Session.POLL_OTHERS_MS, Session.nextPollDelay(true, 0, 0));
        assertEquals(Session.POLL_IDLE_MS, Session.nextPollDelay(false, 3, 0));
        assertEquals(16_000, Session.nextPollDelay(false, 0, 1));
        assertEquals(Session.POLL_MAX_BACKOFF_MS, Session.nextPollDelay(false, 0, 5));
    }
}
