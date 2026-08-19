# What's Changed

<!--
Keep only the changes for the current release in this file.
Replace the content when preparing the next release; release history is preserved by GitHub Releases.
-->

- Expose the Privileged Server's process-cached SELinux context through a diagnostic-friendly `PrivilegeServerInfo` data class while keeping construction and `copy` internal.
- Restrict construction and copying of Core result snapshots to the library while keeping configuration and input models caller-constructible.
