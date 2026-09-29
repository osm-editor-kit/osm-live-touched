package org.openstreetmap.josm.plugins.livetouched;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;

import javax.swing.Timer;

import org.openstreetmap.josm.actions.upload.UploadHook;
import org.openstreetmap.josm.data.APIDataSet;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.Version;
import org.openstreetmap.josm.data.oauth.IOAuthToken;
import org.openstreetmap.josm.data.oauth.OAuth20Token;
import org.openstreetmap.josm.data.oauth.OAuthAccessTokenHolder;
import org.openstreetmap.josm.data.oauth.OAuthVersion;
import org.openstreetmap.josm.data.osm.BBox;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.OsmPrimitiveType;
import org.openstreetmap.josm.data.osm.SimplePrimitiveId;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MapFrame;
import org.openstreetmap.josm.gui.NavigatableComponent;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.io.OsmApi;
import org.openstreetmap.josm.plugins.livetouched.Model.LiveState;
import org.openstreetmap.josm.plugins.livetouched.Model.LocalObject;
import org.openstreetmap.josm.plugins.livetouched.Model.OsmType;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.tools.Logging;

/**
 * Connects JOSM to the session: reports the modified objects and the viewport, detects
 * successful uploads, and forwards state changes to the dialog and the map highlight.
 */
final class Controller implements UploadHook, NavigatableComponent.ZoomChangeListener {
    /** Pending uploads older than this are dropped (upload failed or was cancelled). */
    private static final long PENDING_UPLOAD_MAX_MS = 15 * 60_000;

    private final MapFrame map;
    private final LiveTouchedDialog dialog;
    private final HighlightPainter painter = new HighlightPainter();
    private final Session session;
    private final Timer tick;
    /** key ("w123") -> [base version, time the upload started]. */
    private final Map<String, long[]> pendingUpload = new ConcurrentHashMap<>();

    Controller(MapFrame map) {
        this.map = map;
        String editor = "JOSM " + Version.getInstance().getVersion();
        KvApi api = new KvApi(Settings.baseUrl(), Settings.project(), Settings.apiKey(), Settings.origin(),
                Version.getInstance().getAgentString() + " live-touched", editor, Controller::osmToken);
        this.session = new Session(api, Controller::loadedVersion, this::onState);
        this.dialog = new LiveTouchedDialog(this);
        map.mapView.addTemporaryLayer(painter);
        NavigatableComponent.addZoomChangeListener(this);
        // Poll the edit data set every 2 s; the session diffs it, so undo and discard need no extra events.
        this.tick = new Timer(2_000, e -> onTick());
        tick.start();
        // Stay on across restarts, but only with the current consent version.
        if (Settings.hasConsent() && Config.getPref().getBoolean(Settings.PREF_ENABLED, false)) {
            session.enable(message -> Logging.warn("live-touched: could not turn on: " + message));
        }
    }

    LiveTouchedDialog dialog() {
        return dialog;
    }

    Session session() {
        return session;
    }

    // --- JOSM -> session ------------------------------------------------------------

    /** The OAuth 2 bearer token JOSM uses for the OSM API, or null if not logged in. */
    static String osmToken() {
        IOAuthToken token = OAuthAccessTokenHolder.getInstance()
                .getAccessToken(OsmApi.getOsmApi().getServerUrl(), OAuthVersion.OAuth20);
        return token instanceof OAuth20Token ? ((OAuth20Token) token).getBearerToken() : null;
    }

    private static OsmPrimitiveType josmType(OsmType t) {
        return OsmPrimitiveType.fromApiTypeName(t.apiName);
    }

    private static OsmType ownType(OsmPrimitive p) {
        return OsmType.fromApiName(p.getType().getAPIName());
    }

    /** Version of an object in the edit data set (any thread, uses the read lock). */
    static Integer loadedVersion(OsmType type, Long id) {
        DataSet ds = MainApplication.getLayerManager().getEditDataSet();
        if (ds == null) return null;
        Lock lock = ds.getReadLock();
        lock.lock();
        try {
            OsmPrimitive p = ds.getPrimitiveById(new SimplePrimitiveId(id, josmType(type)));
            return p == null || p.isNew() ? null : p.getVersion();
        } finally {
            lock.unlock();
        }
    }

    private void onTick() {
        DataSet ds = MainApplication.getLayerManager().getEditDataSet();
        List<LocalObject> objects = new ArrayList<>();
        Map<String, Long> saved = new HashMap<>();
        if (ds != null) {
            for (OsmPrimitive p : ds.<OsmPrimitive>getPrimitives(p -> !p.isNew() && (p.isModified() || p.isDeleted()))) {
                OsmType type = ownType(p);
                BBox b = p.getBBox();
                if (type == null || b == null || !b.isValid()) continue;
                objects.add(new LocalObject(type, p.getUniqueId(), p.getVersion(),
                        new double[] {b.getMinLon(), b.getMinLat(), b.getMaxLon(), b.getMaxLat()}));
            }
            detectSaved(ds, saved);
        }
        session.setModified(objects);
        if (!saved.isEmpty()) session.markSaved(saved);
        reportViewport();
    }

    /** An uploaded object counts as saved once it is unmodified with a higher version, or gone (deleted). */
    private void detectSaved(DataSet ds, Map<String, Long> saved) {
        long now = System.currentTimeMillis();
        for (Iterator<Map.Entry<String, long[]>> it = pendingUpload.entrySet().iterator(); it.hasNext();) {
            Map.Entry<String, long[]> e = it.next();
            String key = e.getKey();
            OsmType type = key.charAt(0) == 'n' ? OsmType.NODE : key.charAt(0) == 'w' ? OsmType.WAY : OsmType.RELATION;
            long id = Long.parseLong(key.substring(1));
            OsmPrimitive p = ds.getPrimitiveById(new SimplePrimitiveId(id, josmType(type)));
            if (p == null) {
                saved.put(key, null);
                it.remove();
            } else if (!p.isModified() && !p.isDeleted() && p.getVersion() > e.getValue()[0]) {
                saved.put(key, p.getChangesetId() > 0 ? (long) p.getChangesetId() : null);
                it.remove();
            } else if (now - e.getValue()[1] > PENDING_UPLOAD_MAX_MS) {
                it.remove();
            }
        }
    }

    private void reportViewport() {
        if (map.mapView == null || map.mapView.getWidth() == 0) return;
        Bounds b = map.mapView.getRealBounds();
        session.setViewport(new double[] {b.getMinLon(), b.getMinLat(), b.getMaxLon(), b.getMaxLat()});
    }

    @Override
    public void zoomChanged() {
        reportViewport();
    }

    /** Remember what is being uploaded; after the upload these become "saved". */
    @Override
    public boolean checkUpload(APIDataSet apiDataSet) {
        long now = System.currentTimeMillis();
        List<OsmPrimitive> uploaded = new ArrayList<>(apiDataSet.getPrimitivesToUpdate());
        uploaded.addAll(apiDataSet.getPrimitivesToDelete());
        for (OsmPrimitive p : uploaded) {
            OsmType type = ownType(p);
            if (type != null && !p.isNew()) {
                pendingUpload.put(String.valueOf(type.letter) + p.getUniqueId(), new long[] {p.getVersion(), now});
            }
        }
        return true;
    }

    // --- session -> UI ------------------------------------------------------------

    private void onState(LiveState state) {
        painter.setItems(state.items);
        GuiHelper.runInEDT(() -> {
            dialog.render(state);
            map.mapView.repaint();
        });
    }

    void shutdown() {
        tick.stop();
        NavigatableComponent.removeZoomChangeListener(this);
        map.mapView.removeTemporaryLayer(painter);
        session.shutdown();
    }
}
