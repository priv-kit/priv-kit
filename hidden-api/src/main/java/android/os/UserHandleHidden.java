package android.os;

import li.songe.remap.RemapType;

/**
 * @noinspection unused
 */
@RemapType(UserHandle.class)
public final class UserHandleHidden {
    public static UserHandle of(int userId) {
        throw new RuntimeException();
    }
}
