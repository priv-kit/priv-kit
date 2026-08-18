# What's Changed

<!--
Keep only the changes for the current release in this file.
Replace the content when preparing the next release; release history is preserved by GitHub Releases.
-->

- Let hosts configure the notification channel ID and two consecutive notification IDs used by the `priv-ui` ADB pairing flow.
- Keep the current notification owner active when a replacement cannot show notifications, and reject notification actions without an active ViewModel-owned service session.
- Add cancellable, bounded `PrivilegeFile.deleteRecursively()` execution without blocking Binder threads or following symbolic links.
