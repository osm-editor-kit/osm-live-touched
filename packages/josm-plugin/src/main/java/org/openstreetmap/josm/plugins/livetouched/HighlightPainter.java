package org.openstreetmap.josm.plugins.livetouched;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.util.List;

import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.SimplePrimitiveId;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MapView;
import org.openstreetmap.josm.gui.layer.MapViewPaintable;
import org.openstreetmap.josm.plugins.livetouched.Model.Hint;
import org.openstreetmap.josm.plugins.livetouched.Model.LiveItem;
import org.openstreetmap.josm.plugins.livetouched.Model.Status;

/** Draws a halo around objects other mappers are editing (colors per docs/concept.md). */
final class HighlightPainter implements MapViewPaintable {
    static final Color TOUCHED = new Color(232, 116, 12);
    static final Color SAVED = new Color(130, 70, 200);
    static final Color CONFLICT = new Color(210, 30, 30);

    private volatile List<LiveItem> items = List.of();

    void setItems(List<LiveItem> items) {
        this.items = items;
    }

    @Override
    public void paint(Graphics2D g, MapView mv, Bounds box) {
        List<LiveItem> current = items;
        if (current.isEmpty()) return;
        DataSet ds = MainApplication.getLayerManager().getEditDataSet();
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            for (LiveItem item : current) {
                Color color = item.status == Status.SAVED ? SAVED : TOUCHED;
                float alpha = item.status == Status.STALE ? 0.3f : 0.55f;
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
                g2.setColor(color);
                g2.setStroke(new BasicStroke(10f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                java.awt.Shape shape = shapeOf(item, ds, mv);
                if (shape == null) continue;
                g2.draw(shape);
                if (item.hint == Hint.PARALLEL || item.hint == Hint.OUTDATED) {
                    g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.9f));
                    g2.setColor(CONFLICT);
                    g2.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g2.draw(shape);
                }
            }
        } finally {
            g2.dispose();
        }
    }

    /** The object's geometry if it is loaded locally, else its bbox from the entry. */
    private static java.awt.Shape shapeOf(LiveItem item, DataSet ds, MapView mv) {
        OsmPrimitive p = ds == null ? null
                : ds.getPrimitiveById(new SimplePrimitiveId(item.id,
                        org.openstreetmap.josm.data.osm.OsmPrimitiveType.fromApiTypeName(item.type.apiName)));
        if (p instanceof Node && ((Node) p).isLatLonKnown()) {
            Point2D pt = mv.getPoint2D((Node) p);
            return new java.awt.geom.Ellipse2D.Double(pt.getX() - 7, pt.getY() - 7, 14, 14);
        }
        if (p instanceof Way && ((Way) p).getNodesCount() > 1) {
            Path2D path = new Path2D.Double();
            boolean first = true;
            for (Node n : ((Way) p).getNodes()) {
                if (!n.isLatLonKnown()) continue;
                Point2D pt = mv.getPoint2D(n);
                if (first) path.moveTo(pt.getX(), pt.getY());
                else path.lineTo(pt.getX(), pt.getY());
                first = false;
            }
            return first ? null : path;
        }
        Point2D a = mv.getPoint2D(new LatLon(item.bbox[3], item.bbox[0]));
        Point2D b = mv.getPoint2D(new LatLon(item.bbox[1], item.bbox[2]));
        double w = Math.max(10, b.getX() - a.getX());
        double h = Math.max(10, b.getY() - a.getY());
        return new java.awt.geom.Rectangle2D.Double(a.getX(), a.getY(), w, h);
    }
}
