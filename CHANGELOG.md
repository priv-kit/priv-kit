# What's Changed

<!--
Keep only the changes for the current release in this file.
Replace the content when preparing the next release; release history is preserved by GitHub Releases.
-->

- Run the native starter directly from an uncompressed APK on Android 10 and
  later, while preserving extracted native-library startup for Android 8 and 9.
- Cache decoded ADB key material across manager instances to avoid repeated
  key parsing and certificate setup.
- Improve Privilege UI initialization, status loading, and restoration of the
  previously successful startup mode.
