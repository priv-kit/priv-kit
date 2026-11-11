# What's Changed

<!--
Keep only the changes for the current release in this file.
Replace the content when preparing the next release; release history is preserved by GitHub Releases.
-->

- Replace `PrivilegeConfig.crashLogDirectory` with the ordered `crashLogDirectories` list. When writes to the configured directories fail, save the crash log under `/data/local/tmp/priv-kit/<appId>/<userId>/crashes/`.
- Add the synchronous `Privilege.listFallbackCrashLogFiles()` API. It uses the app's filesystem permissions and falls back to the filename index if directory listing fails.
