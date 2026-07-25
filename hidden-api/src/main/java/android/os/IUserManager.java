package android.os;

import android.content.pm.UserInfo;

import java.util.List;

/**
 * @noinspection unused
 */
public interface IUserManager extends IInterface {
    abstract class Stub extends Binder implements IUserManager {
        public static IUserManager asInterface(IBinder obj) {
            throw new RuntimeException();
        }
    }

    // 9 - 10.0.0_r29, 10.0.0_r46 - 10, 16.0.0_r3 - 17
    List<UserInfo> getUsers(boolean excludeDying);

    // 10.0.0_r30 - 10.0.0_r45, 11 - 16.0.0_r2
    List<UserInfo> getUsers(boolean excludePartial, boolean excludeDying, boolean excludePreCreated);
}
