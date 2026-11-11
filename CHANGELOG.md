# What's Changed

<!--
Keep only the changes for the current release in this file.
Replace the content when preparing the next release; release history is preserved by GitHub Releases.
-->

- Add `PrivilegeUi.getLastStartMethod()` to synchronously read the last successful UI startup method. The result distinguishes Root, wireless ADB, static TCP ADB, and external providers by ID. It returns `null` when no valid record is available.
