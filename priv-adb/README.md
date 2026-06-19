# priv-adb

ADB startup strategy module for Priv Kit.

Namespace and package root: `priv.kit.adb`.

This module owns Wireless Debugging pairing/connect plus execution of the shared server launch command.
Pairing and connection ports are discovered through Android's ADB mDNS services by default.
Callers can customize `PrivilegeAdbIdentity`: `deviceName` is written to the ADB public key comment, while
`signature` selects a separate persisted RSA key identity. The actual fingerprint shown by Android is derived from
that RSA key, not from a user-provided string.

It must not become a public ADB command library, shell helper API, or Android system operation wrapper.
