# priv-delegate

Delegate startup strategy module for Priv Kit.

Namespace and package root: `priv.kit.delegate`.

This module defines the Delegate startup transport boundary. It connects to an app-provided delegate executor and asks it to execute the shared server launch command built by `priv-runtime`.

Phase 1 contents:

- `PrivilegeDelegateExecutor`, the app-provided executor contract.
- `PrivilegeDelegateStarter`, the transport helper that checks executor availability and starts the command.
- `PrivilegeDelegateCommand`, `PrivilegeDelegateProcess`, and `PrivilegeDelegateStartResult` for startup command transport and diagnostics.

`priv-delegate` does not own token generation, pending handshakes, Binder handoff waiting, session creation, or owner-death behavior. Those remain in `priv-runtime`.

Shizuku can be one app-side delegate executor, but this module must not become a Shizuku wrapper, global delegate market, shared privilege daemon, or Android system operation router.
