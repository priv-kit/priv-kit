# What's Changed

<!--
Keep only the changes for the current release in this file.
Replace the content when preparing the next release; release history is preserved by GitHub Releases.
-->

- Filter permissions not defined by the current Android system from
  `Privilege.getDeniedServerPermissions()`, querying permission definitions only for denied permissions.
- Show restricted permission details in a selectable dialog with copy support, while displaying
  the restriction warning immediately and fetching the permission list in the background.
- Add a permission solutions button with an overridable `onViewPermissionSolutions` callback
  and English and Chinese guides for device-specific ADB restrictions.
- Add an ADB restriction toggle to the playground and an `adbRestricted` preview option.
