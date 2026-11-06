# What's Changed

<!--
Keep only the changes for the current release in this file.
Replace the content when preparing the next release; release history is preserved by GitHub Releases.
-->

- Add UserService UID selection and Binder forwarding through the selected UserService connection. Replace `PrivilegeUserServiceSpec.embedded` with `process`; use `PrivilegeUserServiceProcess.EMBEDDED` for embedded services.
- Fix `PrivilegeScaffold` initialization when a localized Compose context no longer exposes the Activity result registry, while preserving host-provided registries.
- Add `PrivilegeUi.setLocale(Locale?)` to refresh pairing notification language without restarting pairing or clearing the entered code. Call it on the main thread; pass `null` to follow Android's application language configuration.
