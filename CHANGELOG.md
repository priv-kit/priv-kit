# What's Changed

<!--
Keep only the changes for the current release in this file.
Replace the content when preparing the next release; release history is preserved by GitHub Releases.
-->

- Allow restarting a connected Privileged Server from the built-in UI after confirmation.
- Stop the existing server before replacement and report a clear failure when the starter
  does not have permission to terminate it.
- Scope server discovery and process names to the application's Android user while keeping
  primary-user manual starter commands free of unnecessary arguments.
