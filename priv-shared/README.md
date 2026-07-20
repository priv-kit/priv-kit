# priv-shared

Internal Android implementation library shared by `priv-core` and `priv-ui`.

Application code should depend on `priv-core` or `priv-ui`, not this artifact. Its symbols are
bytecode-public only because the consuming modules are compiled separately; they live under
`priv.kit.shared` and carry no compatibility guarantee for direct consumers.

This module is limited to stateless invariants and low-level Android/JDK primitives that both
runtime and UI already use, including local ADB endpoint defaults, port validation, and pairing-code
rules. It may query the host manifest and adapt an application `Context` to app-private storage, but
it must not add resources, manifest declarations or components, AndroidX, Compose, coroutines,
startup strategies, UI configuration, method-ID semantics, permission flows, or transport
orchestration.
