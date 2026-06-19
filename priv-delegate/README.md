# priv-delegate

Delegate startup strategy module for Priv Kit.

Namespace and package root: `priv.kit.delegate`.

This skeleton module declares the Delegate startup transport boundary only. Its future responsibility is connecting to an app-provided delegate executor and asking it to execute the shared server launch command.

Shizuku can be one app-side delegate executor, but this module must not become a Shizuku wrapper, global delegate market, shared privilege daemon, or Android system operation router.
