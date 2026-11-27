# What's Changed

<!--
Keep only the changes for the current release in this file.
Replace the content when preparing the next release; release history is preserved by GitHub Releases.
-->

- Rework UserService lifecycle APIs: replace `startUserService` with `bindUserService`, add `peekUserService` to reconnect without creating or replacing an instance, and make `stopUserService` destroy the service and invalidate every connection. Move `daemon` from the service spec to the bind argument and allow updates without recreating the instance.
- Default UserServices to `EMBEDDED`, rename `DEDICATED` to `INHERIT`, and use typed process options for supported UIDs. Make `tag` nullable and remove the connection's `uid` field.
- Add `connection.isConnected` to observe connection validity independently of Binder process death. Forward Binder calls directly through the UserService process and preserve death-listener removal after unbinding.
- Unify unavailable-channel errors under `PrivilegeUnavailableException` and preserve remote Binder call failures.
- Upgrade both native libraries to C++20, modularize the UserService launcher, and relax unnecessary startup checks.
- Update dependencies, integration examples, and design documentation. Add real-device coverage for binding, unbinding, daemon reconnection, and Binder death callbacks.
