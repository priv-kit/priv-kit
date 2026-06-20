package com.android.org.conscrypt;

import javax.net.ssl.SSLSocket;

/**
 * @noinspection unused
 */
public final class Conscrypt {
    private Conscrypt() {
    }

    public static byte[] exportKeyingMaterial(
            SSLSocket socket,
            String label,
            byte[] context,
            int length
    ) {
        throw new RuntimeException();
    }
}
