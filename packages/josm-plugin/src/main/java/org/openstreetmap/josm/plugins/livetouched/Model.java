package org.openstreetmap.josm.plugins.livetouched;

import java.util.Collections;
import java.util.List;

/** Plain data types shared by the logic and the UI (same model as the npm package). */
final class Model {
    private Model() {
    }

    enum OsmType {
        NODE("node", 'n'), WAY("way", 'w'), RELATION("relation", 'r');

        final String apiName;
        final char letter;

        OsmType(String apiName, char letter) {
            this.apiName = apiName;
            this.letter = letter;
        }

        static OsmType fromApiName(String name) {
            for (OsmType t : values()) {
                if (t.apiName.equals(name)) return t;
            }
            return null;
        }
    }

    enum Status { TOUCHED, STALE, SAVED, HIDDEN }

    /**
     * PARALLEL: someone else is touching an object I have also changed.
     * OUTDATED: someone saved a newer version than the one I have loaded.
     * OK: someone saved and my version is current. NONE: no hint.
     */
    enum Hint { PARALLEL, OUTDATED, OK, NONE }

    /** An object the local user has changed. bbox = [minLon, minLat, maxLon, maxLat]. */
    static final class LocalObject {
        final OsmType type;
        final long id;
        final int baseVersion;
        final double[] bbox;

        LocalObject(OsmType type, long id, int baseVersion, double[] bbox) {
            this.type = type;
            this.id = id;
            this.baseVersion = baseVersion;
            this.bbox = bbox;
        }

        String key() {
            return String.valueOf(type.letter) + id;
        }
    }

    /** One object of another user, ready for the list and the map. */
    static final class LiveItem {
        final OsmType type;
        final long id;
        final long userId;
        final String userName;
        final Status status;
        final long ageMs;
        final double[] bbox;
        final int baseVersion;
        final Integer newVersion;
        final Long changesetId;
        final Hint hint;
        final Integer localVersion;

        LiveItem(OsmType type, long id, long userId, String userName, Status status, long ageMs, double[] bbox,
                int baseVersion, Integer newVersion, Long changesetId, Hint hint, Integer localVersion) {
            this.type = type;
            this.id = id;
            this.userId = userId;
            this.userName = userName;
            this.status = status;
            this.ageMs = ageMs;
            this.bbox = bbox;
            this.baseVersion = baseVersion;
            this.newVersion = newVersion;
            this.changesetId = changesetId;
            this.hint = hint;
            this.localVersion = localVersion;
        }

        String key() {
            return String.valueOf(type.letter) + id;
        }
    }

    static final class LiveState {
        final boolean enabled;
        final boolean zoomedOutTooFar;
        final List<LiveItem> items;
        final String error;

        LiveState(boolean enabled, boolean zoomedOutTooFar, List<LiveItem> items, String error) {
            this.enabled = enabled;
            this.zoomedOutTooFar = zoomedOutTooFar;
            this.items = Collections.unmodifiableList(items);
            this.error = error;
        }

        boolean othersNearby() {
            return !items.isEmpty();
        }

        static final LiveState OFF = new LiveState(false, false, List.of(), null);
    }
}
