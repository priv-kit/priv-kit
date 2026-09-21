# What's Changed

<!--
Keep only the changes for the current release in this file.
Replace the content when preparing the next release; release history is preserved by GitHub Releases.
-->

- Add an animated local network permission card to the ADB tab, with permission requests
  and an app settings shortcut after permanent denial. Missing permission does not block
  ADB operations, including static loopback TCP and silent startup.
- Refresh permission state when the page resumes and retry active wireless discovery and
  status checks after permission is granted, waiting for cancelled work to finish cleanup.
  Existing connections and service startup commands are not replayed.
- Limit passive status checks to visible tabs, share concurrent refresh work, and simplify
  pairing resource ownership and ADB components. Remove unused UI state APIs and strings.
- Add battery optimization exemption and local network permission switches to the playground,
  keeping simulated authorization actions and scenario controls synchronized.
