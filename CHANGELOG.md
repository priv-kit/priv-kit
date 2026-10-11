# What's Changed

<!--
Keep only the changes for the current release in this file.
Replace the content when preparing the next release; release history is preserved by GitHub Releases.
-->

- Prevent Android 17 ADB service discovery from repeatedly opening the system network device picker.
- Suspend passive wireless ADB discovery while local network permission is missing and resume it after permission is granted, while keeping static TCP available.
