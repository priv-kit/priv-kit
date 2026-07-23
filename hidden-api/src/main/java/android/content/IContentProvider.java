package android.content;

import android.os.Bundle;
import android.os.IInterface;

import androidx.annotation.Nullable;

/**
 * @noinspection unused
 */
public interface IContentProvider extends IInterface {
    Bundle call(String callingPackage, String method, @Nullable String argument, @Nullable Bundle extras);
}
