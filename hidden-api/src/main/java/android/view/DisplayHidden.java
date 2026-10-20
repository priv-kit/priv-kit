package android.view;

import li.songe.remap.RemapType;

@RemapType(Display.class)
public class DisplayHidden {
    public boolean getDisplayInfo(DisplayInfo info) { throw new RuntimeException(); }
}
