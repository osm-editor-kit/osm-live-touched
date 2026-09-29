package org.openstreetmap.josm.plugins.livetouched;

import org.openstreetmap.josm.actions.UploadAction;
import org.openstreetmap.josm.actions.upload.UploadHook;
import org.openstreetmap.josm.data.APIDataSet;
import org.openstreetmap.josm.gui.MapFrame;
import org.openstreetmap.josm.plugins.Plugin;
import org.openstreetmap.josm.plugins.PluginInformation;

/**
 * Live touched for JOSM: see which OSM objects other mappers are editing nearby, before they
 * upload. Opt-in; same backend and rules as the web editors (docs/concept.md).
 */
public class LiveTouchedPlugin extends Plugin implements UploadHook {
    private Controller controller;

    public LiveTouchedPlugin(PluginInformation info) {
        super(info);
        UploadAction.registerUploadHook(this);
    }

    @Override
    public void mapFrameInitialized(MapFrame oldFrame, MapFrame newFrame) {
        if (controller != null) {
            controller.shutdown();
            controller = null;
        }
        if (newFrame != null) {
            controller = new Controller(newFrame);
            newFrame.addToggleDialog(controller.dialog());
        }
    }

    @Override
    public boolean checkUpload(APIDataSet apiDataSet) {
        Controller c = controller;
        return c == null || c.checkUpload(apiDataSet);
    }
}
