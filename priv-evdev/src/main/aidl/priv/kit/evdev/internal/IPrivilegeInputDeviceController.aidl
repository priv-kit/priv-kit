package priv.kit.evdev.internal;
import priv.kit.evdev.internal.PrivilegeInputStatusResult;
import priv.kit.evdev.internal.PrivilegeInputDeviceListResult;
import priv.kit.evdev.internal.PrivilegeInputOpenResult;
import priv.kit.evdev.internal.PrivilegeInputNameResult;
import priv.kit.evdev.internal.PrivilegeInputIntArrayResult;
import priv.kit.evdev.internal.PrivilegeInputAxisResult;
import priv.kit.evdev.internal.PrivilegeInputWriteResult;
import android.os.IBinder;
import priv.kit.evdev.internal.IPrivilegeInputCancellation;
import priv.kit.evdev.internal.PrivilegeInputTouchDevicesResult;
import priv.kit.evdev.PrivilegeEvdevTouchMapping;

/** Internal same-version wire contract. Never expose numeric device handles publicly. */
interface IPrivilegeInputDeviceController {
    PrivilegeInputOpenResult openTouchscreen(int displayId);
    PrivilegeInputTouchDevicesResult queryTouchDevices();
    PrivilegeInputOpenResult openTouchDevice(String path, int displayId);
    PrivilegeInputStatusResult attach(IBinder owner);
    PrivilegeInputDeviceListResult listDevices();
    PrivilegeInputOpenResult openDevice(String path, boolean writable);
    PrivilegeInputNameResult queryName(int device);
    PrivilegeInputIntArrayResult queryCapabilities(int device, int type);
    PrivilegeInputAxisResult queryAxis(int device, int axis);
    PrivilegeInputIntArrayResult queryTrackingIds(int device);
    PrivilegeInputWriteResult writeEvents(int device, in int[] events);
    PrivilegeInputStatusResult closeDevice(int device);
    PrivilegeInputStatusResult close();
    PrivilegeInputStatusResult configureTouch(int device, in PrivilegeEvdevTouchMapping mapping);
    IPrivilegeInputCancellation createTouchCancellation();
    PrivilegeInputStatusResult touch(int device, float startX, float startY, float endX, float endY, long durationMillis, long holdMillis, IPrivilegeInputCancellation cancellation);
}
