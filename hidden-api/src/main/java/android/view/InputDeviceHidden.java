package android.view;

import li.songe.remap.RemapType;

@RemapType(InputDevice.class)
public class InputDeviceHidden {
    public int getAssociatedDisplayId() { throw new RuntimeException(); }
}
