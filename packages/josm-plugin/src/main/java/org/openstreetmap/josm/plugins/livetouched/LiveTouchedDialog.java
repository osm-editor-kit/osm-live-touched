package org.openstreetmap.josm.plugins.livetouched;

import static org.openstreetmap.josm.tools.I18n.tr;
import static org.openstreetmap.josm.tools.I18n.trn;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Arrays;
import java.util.List;

import javax.swing.AbstractAction;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;

import org.openstreetmap.josm.actions.AutoScaleAction;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.DefaultNameFormatter;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.OsmPrimitiveType;
import org.openstreetmap.josm.data.osm.SimplePrimitiveId;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.SideButton;
import org.openstreetmap.josm.gui.dialogs.ToggleDialog;
import org.openstreetmap.josm.plugins.livetouched.Model.LiveItem;
import org.openstreetmap.josm.plugins.livetouched.Model.LiveState;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.tools.OpenBrowser;
import org.openstreetmap.josm.tools.Shortcut;

/** Side panel: on/off, the list of objects others are editing, privacy link, and "Delete my data". */
final class LiveTouchedDialog extends ToggleDialog {
    private static final long serialVersionUID = 1L;

    private final transient Controller controller;
    private final DefaultListModel<LiveItem> model = new DefaultListModel<>();
    private final JList<LiveItem> list = new JList<>(model);
    private final JLabel status = new JLabel();
    private final ToggleAction toggleAction = new ToggleAction();

    LiveTouchedDialog(Controller controller) {
        super(tr("Live edits nearby"), "live-touched", tr("See which objects other mappers are editing nearby"),
                Shortcut.registerShortcut("subwindow:livetouched", tr("Windows: {0}", tr("Live edits nearby")),
                        KeyEvent.CHAR_UNDEFINED, Shortcut.NONE),
                150);
        this.controller = controller;
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new ItemRenderer());
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && list.getSelectedValue() != null) zoomTo(list.getSelectedValue());
            }
        });
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(status, BorderLayout.NORTH);
        panel.add(new JScrollPane(list), BorderLayout.CENTER);
        createLayout(panel, false, Arrays.asList(
                new SideButton(toggleAction),
                new SideButton(new PrivacyAction()),
                new SideButton(new NukeAction())));
        render(LiveState.OFF);
    }

    void render(LiveState state) {
        model.clear();
        for (LiveItem item : state.items) model.addElement(item);
        toggleAction.putValue(javax.swing.Action.NAME, state.enabled ? tr("Turn off") : tr("Turn on"));
        String text;
        if (!state.enabled) {
            text = tr("Off. Turn on to see and share live edits.");
        } else if (state.error != null) {
            text = tr("Error: {0}", state.error);
        } else if (state.zoomedOutTooFar) {
            text = tr("Zoom in to see live edits");
        } else if (state.items.isEmpty()) {
            text = tr("Nobody else is editing here right now");
        } else {
            text = trn("{0} object edited by others nearby", "{0} objects edited by others nearby",
                    state.items.size(), state.items.size());
        }
        status.setText(text);
        setTitle(state.othersNearby() ? tr("Live edits nearby") + " ●" : tr("Live edits nearby"));
    }

    private static void zoomTo(LiveItem item) {
        DataSet ds = MainApplication.getLayerManager().getEditDataSet();
        OsmPrimitive p = ds == null ? null
                : ds.getPrimitiveById(new SimplePrimitiveId(item.id, OsmPrimitiveType.fromApiTypeName(item.type.apiName)));
        if (p != null) {
            ds.setSelected(p);
            AutoScaleAction.zoomTo(List.of(p));
        } else if (MainApplication.getMap() != null) {
            MainApplication.getMap().mapView.zoomTo(new Bounds(item.bbox[1], item.bbox[0], item.bbox[3], item.bbox[2]));
        }
    }

    private static String age(long ms) {
        return tr("{0} min ago", Math.max(0, ms / 60_000));
    }

    static String describe(LiveItem item) {
        String name = item.type.apiName + " " + item.id;
        DataSet ds = MainApplication.getLayerManager().getEditDataSet();
        OsmPrimitive p = ds == null ? null
                : ds.getPrimitiveById(new SimplePrimitiveId(item.id, OsmPrimitiveType.fromApiTypeName(item.type.apiName)));
        if (p != null) name = p.getDisplayName(DefaultNameFormatter.getInstance());
        String line;
        switch (item.status) {
            case TOUCHED:
                line = tr("{0} is editing this right now", item.userName);
                break;
            case STALE:
                line = tr("{0} edited this recently; unclear if saved or discarded", item.userName);
                break;
            default:
                line = tr("{0} edited and saved this (v{1})", item.userName,
                        item.newVersion == null ? "?" : item.newVersion);
        }
        String hint;
        switch (item.hint) {
            case PARALLEL:
                hint = tr("{0} is editing this object at the same time as you", item.userName);
                break;
            case OUTDATED:
                hint = tr("Careful: you are on v{0}, {1} saved v{2}. Save soon to avoid conflicts",
                        item.localVersion, item.userName, item.newVersion);
                break;
            case OK:
                hint = tr("Your version is current");
                break;
            default:
                hint = null;
        }
        return "<html><b>" + escape(name) + "</b> · " + escape(age(item.ageMs)) + "<br>" + escape(line)
                + (hint == null ? "" : "<br><i>" + escape(hint) + "</i>") + "</html>";
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static final class ItemRenderer extends DefaultListCellRenderer {
        private static final long serialVersionUID = 1L;

        @Override
        public Component getListCellRendererComponent(JList<?> l, Object value, int index, boolean selected,
                boolean focus) {
            return super.getListCellRendererComponent(l, describe((LiveItem) value), index, selected, focus);
        }
    }

    private final class ToggleAction extends AbstractAction {
        private static final long serialVersionUID = 1L;

        ToggleAction() {
            super(tr("Turn on"));
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            Session session = controller.session();
            if (session.isEnabled()) {
                session.disable();
                Config.getPref().putBoolean(Settings.PREF_ENABLED, false);
                return;
            }
            if (!Settings.hasConsent() && !askConsent()) return;
            Config.getPref().putBoolean(Settings.PREF_ENABLED, true);
            session.enable(message -> org.openstreetmap.josm.gui.util.GuiHelper.runInEDT(() ->
                    JOptionPane.showMessageDialog(MainApplication.getMainFrame(),
                            tr("Could not turn on live edits: {0}\nLog in to OpenStreetMap in the JOSM preferences"
                                    + " (Connection settings, OAuth 2.0).", message),
                            tr("Live edits nearby"), JOptionPane.WARNING_MESSAGE)));
        }

        private boolean askConsent() {
            int answer = JOptionPane.showOptionDialog(MainApplication.getMainFrame(),
                    "<html><body style='width:380px'>"
                            + tr("While this is on, other mappers who also use it can see which objects you are editing,"
                                    + " with your OSM username, <b>before you upload</b>. Only object ids, versions,"
                                    + " and a rough location are sent. Everything is deleted after 3 hours at the latest.")
                            + "<br><br>" + tr("Your OSM login is used to confirm who you are.") + "</body></html>",
                    tr("Live edits nearby"), JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null,
                    new Object[] {tr("Turn on"), tr("Privacy details"), tr("Cancel")}, tr("Turn on"));
            if (answer == 1) {
                OpenBrowser.displayUrl(Settings.PRIVACY_URL);
                return false;
            }
            if (answer != 0) return false;
            Settings.storeConsent();
            return true;
        }
    }

    private static final class PrivacyAction extends AbstractAction {
        private static final long serialVersionUID = 1L;

        PrivacyAction() {
            super(tr("Privacy"));
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            OpenBrowser.displayUrl(Settings.PRIVACY_URL);
        }
    }

    private final class NukeAction extends AbstractAction {
        private static final long serialVersionUID = 1L;

        NukeAction() {
            super(tr("Delete my data"));
            putValue(SHORT_DESCRIPTION, tr("Delete my data on the server"));
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            int ok = JOptionPane.showConfirmDialog(MainApplication.getMainFrame(),
                    tr("Delete all your live-edit entries on the server and turn this off?"),
                    tr("Delete my data on the server"), JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
            if (ok != JOptionPane.OK_OPTION) return;
            Config.getPref().putBoolean(Settings.PREF_ENABLED, false);
            controller.session().nukeMyData(
                    deleted -> org.openstreetmap.josm.gui.util.GuiHelper.runInEDT(() ->
                            JOptionPane.showMessageDialog(MainApplication.getMainFrame(),
                                    trn("Deleted {0} entry", "Deleted {0} entries", deleted, deleted))),
                    message -> org.openstreetmap.josm.gui.util.GuiHelper.runInEDT(() ->
                            JOptionPane.showMessageDialog(MainApplication.getMainFrame(),
                                    tr("Could not delete: {0}", message), tr("Delete my data on the server"),
                                    JOptionPane.ERROR_MESSAGE)));
        }
    }
}
