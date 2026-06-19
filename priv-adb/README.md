# priv-adb

ADB startup strategy module for Priv Kit.

Namespace and package root: `priv.kit.adb`.

This skeleton module declares the ADB startup transport boundary only. Its future responsibility is Wireless Debugging pairing/connect plus execution of the shared server launch command.

It must not become a public ADB command library, shell helper API, or Android system operation wrapper.
