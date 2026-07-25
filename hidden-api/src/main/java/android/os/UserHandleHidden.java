package android.os;

import li.songe.remap.RemapType;

/**
 * @noinspection unused
 */
@RemapType(UserHandle.class)
public class UserHandleHidden {
    public static UserHandle of(int userId) {
        throw new RuntimeException();
    }
}
