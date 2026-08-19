# priv-shared

`priv-shared` is the internal Android implementation library used by `priv-core` and `priv-ui`.
Applications depend on those product modules instead. Its bytecode-public symbols live under
`priv.kit.shared` and carry no compatibility promise for direct consumers.

The module contains stateless invariants, low-level Android/JDK primitives, and hidden API
compatibility logic. Runtime signature detection handles Android maintenance-release differences;
narrow `IBinder` wrappers isolate hidden interfaces that are absent on part of the supported API
range. Framework declarations come from the compile-only `:hidden-api` module.

Resources, components, UI, startup policy, and transport orchestration belong to the product
modules.
