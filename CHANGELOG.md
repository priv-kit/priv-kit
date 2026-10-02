# What's Changed

<!--
Keep only the changes for the current release in this file.
Replace the content when preparing the next release; release history is preserved by GitHub Releases.
-->

- Return `PERMISSION_GRANTED` directly from `Privilege.checkServerPermission()` for root servers (UID 0), skipping the system permission query while preserving connection validity checks.
