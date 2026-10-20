# priv-shared

Internal Android library used by `priv-core`, `priv-input`, `priv-evdev` and `priv-ui`. Applications access these utilities through those modules; `priv.kit.shared` is internal API.

Provides Android/JDK utilities and hidden API compatibility. Runtime signature checks handle maintenance-release differences; `IBinder` wrappers isolate interfaces missing on some versions. Framework declarations come from compile-only `:hidden-api`.

Remap automatically generates exact hidden-type `dontwarn` rules and exports them with the AAR as consumer rules. No separate rule file or generation script is needed.
