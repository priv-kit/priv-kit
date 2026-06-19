# priv-root

Root startup strategy module for Priv Kit.

Namespace and package root: `priv.kit.root`.

Phase 1 contents:

- `PrivilegeRootStarter` for root availability detection and `su` execution of a runtime-built shared server launch command.
- `PrivilegeRootCommand`, `PrivilegeRootProcess`, and `PrivilegeRootStartResult` for minimal startup diagnostics and result transport.

`priv-root` does not own token generation, pending handshakes, Binder handoff waiting, or session creation. Those remain in `priv-runtime`.

This module does not expose shell helpers, package/input/settings/app-ops/activity APIs, or other reusable privileged operations.
