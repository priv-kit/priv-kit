# priv-shared

Internal Android implementation library shared by `priv-core` and `priv-ui`.

Application code should depend on `priv-core` or `priv-ui`, not this artifact. Its symbols are
bytecode-public only because the consuming modules are compiled separately; they live under
`priv.kit.shared` and carry no compatibility guarantee for direct consumers.

This module is limited to stateless invariants, low-level Android/JDK primitives, and internal hidden
API compatibility logic used by product modules. Hidden APIs that vary across Android maintenance
releases keep their runtime signature detection and dispatch in this module. When a hidden
interface is absent on part of the supported API range, a narrow compat wrapper accepts an
`IBinder` and isolates that interface from lower-level callers. The module uses `:hidden-api` as a
compile-only dependency for those declarations.

It may query the host manifest and adapt an application `Context` to app-private storage, but it
must not add resources, manifest declarations or components, AndroidX, Compose, coroutines, startup
strategies, UI configuration, method-ID semantics, permission flows, or transport orchestration.
