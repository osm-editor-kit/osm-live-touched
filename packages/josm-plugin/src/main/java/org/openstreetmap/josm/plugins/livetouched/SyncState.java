package org.openstreetmap.josm.plugins.livetouched;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openstreetmap.josm.plugins.livetouched.Model.LocalObject;
import org.openstreetmap.josm.plugins.livetouched.Model.OsmType;

/**
 * Diff-based writer, same rules as the npm package: the editor reports the full set of locally
 * modified objects; new or changed objects are put as "touched", touched objects that left the
 * set (undo, discard) are deleted. Saved entries stay until the server TTL removes them.
 */
final class SyncState {
    static final int MAX_BATCH_PUTS = 25;
    static final int MAX_BATCH_DELETES = 50;

    /** One entry to write. data mirrors the npm package's LiveTouchedData. */
    static final class Put {
        final String id;
        final OsmType type;
        final long osmId;
        final double[] bbox;
        final int baseVersion;
        final String status;
        final Integer newVersion;
        final Long changesetId;
        final String touchedAt;
        final List<String> tags;

        Put(String id, OsmType type, long osmId, double[] bbox, int baseVersion, String status, Integer newVersion,
                Long changesetId, String touchedAt, List<String> tags) {
            this.id = id;
            this.type = type;
            this.osmId = osmId;
            this.bbox = bbox;
            this.baseVersion = baseVersion;
            this.status = status;
            this.newVersion = newVersion;
            this.changesetId = changesetId;
            this.touchedAt = touchedAt;
            this.tags = tags;
        }

        String fingerprint() {
            return baseVersion + Arrays.toString(bbox);
        }
    }

    static final class Ops {
        final List<Put> put;
        final List<String> delete;

        Ops(List<Put> put, List<String> delete) {
            this.put = put;
            this.delete = delete;
        }

        boolean isEmpty() {
            return put.isEmpty() && delete.isEmpty();
        }
    }

    private final long uid;
    private final Map<String, Put> sent = new LinkedHashMap<>();

    SyncState(long uid) {
        this.uid = uid;
    }

    String entryId(OsmType type, long id) {
        return uid + "/" + type.letter + id;
    }

    private static boolean isShareable(LocalObject o) {
        if (o.id <= 0 || o.baseVersion <= 0 || o.bbox == null) return false;
        for (double d : o.bbox) {
            if (!Double.isFinite(d)) return false;
        }
        return true;
    }

    synchronized Ops plan(Collection<LocalObject> objects, String nowIso) {
        List<Put> put = new ArrayList<>();
        Set<String> current = new HashSet<>();
        for (LocalObject o : objects) {
            if (!isShareable(o)) continue;
            double[] bbox = Tiles.round(o.bbox);
            List<String> tags = Tiles.tagsForBbox(bbox);
            if (tags.isEmpty()) continue;
            String id = entryId(o.type, o.id);
            current.add(id);
            Put next = new Put(id, o.type, o.id, bbox, o.baseVersion, "touched", null, null, nowIso, tags);
            Put prev = sent.get(id);
            if (prev != null && "touched".equals(prev.status) && prev.fingerprint().equals(next.fingerprint())) {
                continue;
            }
            put.add(next);
        }
        List<String> delete = new ArrayList<>();
        for (Map.Entry<String, Put> e : sent.entrySet()) {
            if ("touched".equals(e.getValue().status) && !current.contains(e.getKey())) delete.add(e.getKey());
        }
        return new Ops(put, delete);
    }

    /** After a successful upload: the given objects ("w123" keys) become saved with version + 1. */
    synchronized Ops planSaved(Map<String, Long> changesetIdByKey, String nowIso) {
        List<Put> put = new ArrayList<>();
        for (Put s : sent.values()) {
            String key = String.valueOf(s.type.letter) + s.osmId;
            if (!"touched".equals(s.status) || !changesetIdByKey.containsKey(key)) continue;
            put.add(new Put(s.id, s.type, s.osmId, s.bbox, s.baseVersion, "saved", s.baseVersion + 1,
                    changesetIdByKey.get(key), nowIso, s.tags));
        }
        return new Ops(put, List.of());
    }

    /** On disable: remove entries that still say "touched". */
    synchronized Ops planDisable() {
        List<String> delete = new ArrayList<>();
        for (Map.Entry<String, Put> e : sent.entrySet()) {
            if ("touched".equals(e.getValue().status)) delete.add(e.getKey());
        }
        return new Ops(List.of(), delete);
    }

    synchronized void commit(Ops ops) {
        for (Put p : ops.put) sent.put(p.id, p);
        for (String id : ops.delete) sent.remove(id);
    }

    synchronized void reset() {
        sent.clear();
    }

    /** Split into batches the API accepts. */
    static List<Ops> chunk(Ops ops) {
        List<Ops> batches = new ArrayList<>();
        int p = 0;
        int d = 0;
        while (p < ops.put.size() || d < ops.delete.size()) {
            batches.add(new Ops(
                    ops.put.subList(p, Math.min(p + MAX_BATCH_PUTS, ops.put.size())),
                    ops.delete.subList(d, Math.min(d + MAX_BATCH_DELETES, ops.delete.size()))));
            p += MAX_BATCH_PUTS;
            d += MAX_BATCH_DELETES;
        }
        return batches;
    }
}
