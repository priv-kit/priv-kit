package android.view;

import li.songe.remap.RemapType;

@RemapType(ViewConfiguration.class)
public class ViewConfigurationHidden {
    public static int getDoubleTapMinTime() { throw new RuntimeException(); }
}
