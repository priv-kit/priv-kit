# What's Changed

<!--
Keep only the changes for the current release in this file.
Replace the content when preparing the next release; release history is preserved by GitHub Releases.
-->

- Move the server lifecycle Binder into each `PrivilegeServerInfo` connection snapshot and remove `Privilege.getServerLifecycleBinder()`.
- Make `PrivilegeServerInfo` a runtime-created connection identity instead of a publicly constructible data class.
- Add the built-in `PrivilegeFile` proxy for common file operations, reliable-pipe content I/O, metadata, errno-preserving atomic replacement, and streaming directory scans without an app-defined UserService.
