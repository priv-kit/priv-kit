package android.content;

import android.os.Bundle;
import android.os.Build;
import android.os.IInterface;

import androidx.annotation.DeprecatedSinceApi;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

/**
 * @noinspection unused
 */
public interface IContentProvider extends IInterface {
    @DeprecatedSinceApi(api = Build.VERSION_CODES.Q)
    Bundle call(String callingPackage, String method, @Nullable String argument, @Nullable Bundle extras);

    @RequiresApi(Build.VERSION_CODES.Q)
    @DeprecatedSinceApi(api = Build.VERSION_CODES.R)
    Bundle call(
            String callingPackage,
            String authority,
            String method,
            @Nullable String argument,
            @Nullable Bundle extras
    );

    @RequiresApi(Build.VERSION_CODES.R)
    @DeprecatedSinceApi(api = Build.VERSION_CODES.S)
    Bundle call(
            String callingPackage,
            @Nullable String attributionTag,
            String authority,
            String method,
            @Nullable String argument,
            @Nullable Bundle extras
    );

    @RequiresApi(Build.VERSION_CODES.S)
    Bundle call(
            AttributionSource attributionSource,
            String authority,
            String method,
            @Nullable String argument,
            @Nullable Bundle extras
    );
}
