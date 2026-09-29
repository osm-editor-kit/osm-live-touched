package org.openstreetmap.josm.plugins.livetouched;

import java.util.Comparator;

import org.openstreetmap.josm.plugins.livetouched.Model.Hint;
import org.openstreetmap.josm.plugins.livetouched.Model.LiveItem;
import org.openstreetmap.josm.plugins.livetouched.Model.Status;

/** Status windows, conflict hints, and sort order (docs/concept.md). */
final class Rules {
    static final long MINUTE = 60_000;
    static final long TOUCHED_MS = 30 * MINUTE;
    static final long STALE_MS = 60 * MINUTE;
    static final long SAVED_MS = 120 * MINUTE;

    private Rules() {
    }

    /** ageMs = time since the entry's last write on the server clock. */
    static Status statusOf(String dataStatus, long ageMs) {
        if ("saved".equals(dataStatus)) return ageMs < SAVED_MS ? Status.SAVED : Status.HIDDEN;
        if (ageMs < TOUCHED_MS) return Status.TOUCHED;
        if (ageMs < STALE_MS) return Status.STALE;
        return Status.HIDDEN;
    }

    static Hint hintFor(Status status, Integer remoteNewVersion, boolean modifiedByMe, Integer localVersion) {
        if (status == Status.SAVED) {
            if (remoteNewVersion == null || localVersion == null) return Hint.NONE;
            return localVersion < remoteNewVersion ? Hint.OUTDATED : Hint.OK;
        }
        return modifiedByMe ? Hint.PARALLEL : Hint.NONE;
    }

    private static int hintRank(Hint h) {
        switch (h) {
            case PARALLEL:
                return 0;
            case OUTDATED:
                return 1;
            default:
                return 2;
        }
    }

    private static int statusRank(Status s) {
        switch (s) {
            case TOUCHED:
                return 0;
            case STALE:
                return 1;
            default:
                return 2;
        }
    }

    /** parallel, outdated, then touched, stale, saved; newest first within a group. */
    static final Comparator<LiveItem> ORDER = Comparator
            .<LiveItem>comparingInt(i -> hintRank(i.hint))
            .thenComparingInt(i -> statusRank(i.status))
            .thenComparingLong(i -> i.ageMs);
}
