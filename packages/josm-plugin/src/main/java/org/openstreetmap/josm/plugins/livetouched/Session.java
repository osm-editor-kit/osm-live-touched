package org.openstreetmap.josm.plugins.livetouched;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;

import org.openstreetmap.josm.plugins.livetouched.Model.Hint;
import org.openstreetmap.josm.plugins.livetouched.Model.LiveItem;
import org.openstreetmap.josm.plugins.livetouched.Model.LiveState;
import org.openstreetmap.josm.plugins.livetouched.Model.LocalObject;
import org.openstreetmap.josm.plugins.livetouched.Model.OsmType;
import org.openstreetmap.josm.plugins.livetouched.Model.Status;

/**
 * Editor-agnostic session, the Java counterpart of the npm package's createLiveTouchedSession:
 * debounced diff sync, adaptive polling, status and hints. All network work runs on one
 * background thread; the listener is called from that thread.
 */
final class Session {
    static final long DEBOUNCE_MS = 2_000;
    static final long POLL_START_MS = 8_000;
    static final long POLL_OTHERS_MS = 4_000;
    static final long POLL_IDLE_MS = 10_000;
    static final long POLL_MAX_BACKOFF_MS = 60_000;
    static final int EMPTY_STREAK_FOR_IDLE = 3;

    private final KvApi api;
    /** (type, id) -> version loaded in the editor, or null. */
    private final BiFunction<OsmType, Long, Integer> localVersion;
    private final Consumer<LiveState> listener;
    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "live-touched");
        t.setDaemon(true);
        return t;
    });

    // All fields below are only touched on the executor thread.
    private boolean enabled;
    private long uid;
    private SyncState sync;
    private List<LocalObject> modified = List.of();
    private double[] viewport;
    private LiveState state = LiveState.OFF;
    private ScheduledFuture<?> pollTask;
    private ScheduledFuture<?> flushTask;
    private boolean othersNearby;
    private int emptyStreak;
    private int errorStreak;

    Session(KvApi api, BiFunction<OsmType, Long, Integer> localVersion, Consumer<LiveState> listener) {
        this.api = api;
        this.localVersion = localVersion;
        this.listener = listener;
    }

    static long nextPollDelay(boolean othersNearby, int emptyStreak, int errorStreak) {
        if (errorStreak > 0) {
            return Math.min(POLL_MAX_BACKOFF_MS, POLL_START_MS * (1L << Math.min(errorStreak, 10)));
        }
        if (othersNearby) return POLL_OTHERS_MS;
        if (emptyStreak >= EMPTY_STREAK_FOR_IDLE) return POLL_IDLE_MS;
        return POLL_START_MS;
    }

    private void publish(LiveState next) {
        state = next;
        listener.accept(next);
    }

    private void publishError(String message) {
        publish(new LiveState(state.enabled, state.zoomedOutTooFar, state.items, message));
    }

    private String nowIso() {
        return Instant.ofEpochMilli(api.serverNow()).toString();
    }

    // --- writes -------------------------------------------------------------------

    private void write(SyncState.Ops ops) {
        if (sync == null || ops.isEmpty()) return;
        try {
            for (SyncState.Ops batch : SyncState.chunk(ops)) {
                api.batch(batch);
                sync.commit(batch);
            }
        } catch (KvApi.KvException e) {
            publishError(e.getMessage());
        }
    }

    private void flushModified() {
        if (enabled && sync != null) write(sync.plan(modified, nowIso()));
    }

    // --- polling ------------------------------------------------------------------

    private void schedulePoll(long delayMs) {
        if (pollTask != null) pollTask.cancel(false);
        if (enabled) pollTask = exec.schedule(this::pollOnce, delayMs, TimeUnit.MILLISECONDS);
    }

    private static double[] bboxOf(JsonValue v) {
        if (!(v instanceof jakarta.json.JsonArray)) return null;
        jakarta.json.JsonArray a = v.asJsonArray();
        if (a.size() != 4) return null;
        double[] b = new double[4];
        for (int i = 0; i < 4; i++) {
            if (!(a.get(i) instanceof JsonNumber)) return null;
            b[i] = a.getJsonNumber(i).doubleValue();
        }
        return b;
    }

    private LiveItem toItem(JsonObject entry, Set<String> myKeys) {
        JsonObject createdBy = entry.getJsonObject("created_by");
        if (createdBy == null || createdBy.getJsonNumber("osm_uid").longValue() == uid) return null;
        JsonValue dataValue = entry.get("data");
        if (!(dataValue instanceof JsonObject)) return null;
        JsonObject d = (JsonObject) dataValue;
        if (d.getInt("schema", 0) != 1) return null;
        OsmType type = OsmType.fromApiName(d.getString("osm_type", ""));
        double[] bbox = bboxOf(d.get("bbox"));
        if (type == null || bbox == null || !d.containsKey("osm_id") || !d.containsKey("base_version")) return null;
        if (viewport != null && !Tiles.intersects(bbox, viewport)) return null;
        long osmId = d.getJsonNumber("osm_id").longValue();
        long updatedAt = Instant.parse(entry.getString("updated_at")).toEpochMilli();
        long ageMs = Math.max(0, api.serverNow() - updatedAt);
        Status status = Rules.statusOf(d.getString("status", ""), ageMs);
        if (status == Status.HIDDEN) return null;
        Integer newVersion = d.containsKey("new_version") ? d.getInt("new_version") : null;
        Long changesetId = d.containsKey("changeset_id") ? d.getJsonNumber("changeset_id").longValue() : null;
        Integer local = localVersion.apply(type, osmId);
        Hint hint = Rules.hintFor(status, newVersion, myKeys.contains(String.valueOf(type.letter) + osmId), local);
        return new LiveItem(type, osmId, createdBy.getJsonNumber("osm_uid").longValue(),
                createdBy.getString("display_name", "?"), status, ageMs, bbox, d.getInt("base_version"),
                newVersion, changesetId, hint, local);
    }

    private void pollOnce() {
        if (!enabled || viewport == null) return;
        List<String> tags = Tiles.queryTagsForViewport(viewport);
        if (tags == null) {
            publish(new LiveState(true, true, List.of(), null));
            return;
        }
        try {
            List<JsonObject> entries = api.list(tags);
            Set<String> myKeys = new HashSet<>();
            for (LocalObject o : modified) myKeys.add(o.key());
            List<LiveItem> items = new ArrayList<>();
            for (JsonObject e : entries) {
                try {
                    LiveItem item = toItem(e, myKeys);
                    if (item != null) items.add(item);
                } catch (RuntimeException ignored) {
                    // skip malformed entries
                }
            }
            items.sort(Rules.ORDER);
            othersNearby = !items.isEmpty();
            emptyStreak = othersNearby ? 0 : emptyStreak + 1;
            errorStreak = 0;
            publish(new LiveState(true, false, items, null));
        } catch (KvApi.KvException e) {
            errorStreak++;
            publishError(e.getMessage());
        } finally {
            schedulePoll(nextPollDelay(othersNearby, emptyStreak, errorStreak));
        }
    }

    // --- public API (thread-safe: everything is handed to the executor) ------------

    /** Turn on after consent. onError gets a message if the OSM login is missing or rejected. */
    void enable(Consumer<String> onError) {
        exec.execute(() -> {
            if (enabled) return;
            try {
                uid = api.me();
            } catch (KvApi.KvException e) {
                onError.accept(e.getMessage());
                return;
            }
            sync = new SyncState(uid);
            enabled = true;
            othersNearby = false;
            emptyStreak = 0;
            errorStreak = 0;
            publish(new LiveState(true, false, List.of(), null));
            flushModified();
            schedulePoll(0);
        });
    }

    void disable() {
        exec.execute(() -> {
            if (!enabled) return;
            if (pollTask != null) pollTask.cancel(false);
            if (flushTask != null) flushTask.cancel(false);
            if (sync != null) write(sync.planDisable());
            enabled = false;
            sync = null;
            publish(LiveState.OFF);
        });
    }

    /** Full set of locally modified objects (replaces the previous set). */
    void setModified(List<LocalObject> objects) {
        exec.execute(() -> {
            modified = objects;
            if (!enabled) return;
            if (flushTask != null) flushTask.cancel(false);
            flushTask = exec.schedule(this::flushModified, DEBOUNCE_MS, TimeUnit.MILLISECONDS);
        });
    }

    /** After a successful upload: key ("w123") -> changeset id (may be null). */
    void markSaved(Map<String, Long> changesetIdByKey) {
        exec.execute(() -> {
            if (!enabled || sync == null) return;
            flushModified();
            write(sync.planSaved(changesetIdByKey, nowIso()));
        });
    }

    void setViewport(double[] bbox) {
        exec.execute(() -> {
            viewport = bbox;
            boolean zoomedOut = Tiles.queryTagsForViewport(bbox) == null;
            if (zoomedOut != state.zoomedOutTooFar) {
                publish(new LiveState(state.enabled, zoomedOut, zoomedOut ? List.of() : state.items, state.error));
            }
            if (enabled) schedulePoll(500);
        });
    }

    /** Deletes all my entries in this project, turns off; result: deleted count or error message. */
    void nukeMyData(Consumer<Integer> onDone, Consumer<String> onError) {
        exec.execute(() -> {
            if (pollTask != null) pollTask.cancel(false);
            if (flushTask != null) flushTask.cancel(false);
            try {
                int deleted = api.removeMine();
                api.forget();
                enabled = false;
                if (sync != null) sync.reset();
                sync = null;
                publish(LiveState.OFF);
                onDone.accept(deleted);
            } catch (KvApi.KvException e) {
                onError.accept(e.getMessage());
            }
        });
    }

    boolean isEnabled() {
        return state.enabled;
    }

    /** Best-effort disable, then stop the thread. */
    void shutdown() {
        disable();
        exec.shutdown();
    }
}
