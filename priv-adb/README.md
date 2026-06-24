# priv-adb

ADB startup strategy module for Priv Kit.

Namespace and package root: `priv.kit.adb`.

This module owns Wireless Debugging pairing/connect plus execution of the shared server launch command.
Pairing and connection ports are discovered through Android's ADB mDNS services by default.
Callers can customize `PrivilegeAdbIdentity`: `deviceName` is written to the ADB public key comment. The encrypted
RSA private key is persisted as `filesDir/.priv-kit/adbkey`, and the actual fingerprint shown by Android is derived
from that RSA key, not from a user-provided string.

`priv-adb` calls framework hidden APIs through compile-time stubs, including Conscrypt keying-material export used by
ADB pairing. The host app must install the required hidden API exemptions before using this module, for example from
`Application.attachBaseContext` on Android P+:

```kotlin
HiddenApiBypass.addHiddenApiExemptions("L")
```

It must not become a public ADB command library, shell helper API, or Android system operation wrapper.
