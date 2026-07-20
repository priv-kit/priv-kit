# Third-party notices

## Shizuku-Fixed ADB startup logic

The `:priv-core` wireless ADB implementation is derived from the ADB pairing,
connection, key encoding, mDNS discovery, and TCP mode logic in
`C:\Users\lisonge\Documents\Project\Shizuku-Fixed`.

Shizuku-Fixed is distributed under the Apache License, Version 2.0. The copied
logic has been adapted to the `priv.kit.core.adb` package and narrowed to Priv Kit's
ADB startup boundary.

## BoringSSL and AOSP ADB pairing

The `:priv-adb-crypto` ADB pairing implementation is derived from BoringSSL's
SPAKE2-over-Edwards25519 behavior and AOSP's ADB pairing_auth AES-GCM/HKDF
boundary.

BoringSSL and AOSP are distributed under the Apache License, Version 2.0. The
copied logic has been adapted to the `priv.kit.adb.crypto.pairing` package and
narrowed to the ADB Wireless Debugging pairing boundary.
