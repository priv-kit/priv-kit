# What's Changed

<!--
Keep only the changes for the current release in this file.
Replace the content when preparing the next release; release history is preserved by GitHub Releases.
-->

- Retry system permission requests after a previous denial instead of immediately treating the permission as permanently denied.
- Debounce the permission explanation banner by 500 ms to avoid flashing when the system request returns immediately.
