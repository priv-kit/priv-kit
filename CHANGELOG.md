# What's Changed

<!--
Keep only the changes for the current release in this file.
Replace the content when preparing the next release; release history is preserved by GitHub Releases.
-->

- Rename the built-in UI's privileged service title for clearer host-app copy.
- Expose the persisted automatic-recovery intent through the read-only process-wide `PrivilegeUi.desiredEnabled` StateFlow.
- Move the server lifecycle Binder into each `PrivilegeServerInfo` connection snapshot and remove `Privilege.getServerLifecycleBinder()`.
- Make `PrivilegeServerInfo` a runtime-created connection identity instead of a publicly constructible data class.
