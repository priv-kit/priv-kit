# What's Changed

<!--
Keep only the changes for the current release in this file.
Replace the content when preparing the next release; release history is preserved by GitHub Releases.
-->

- Restore `CURRENT_PROCESS` as the default for `fromSystemService()` and `hasSystemService()`, including when a UserService connection is supplied. Pass `REMOTE_PROCESS` explicitly when the service needs to be looked up by the privileged server or the supplied UserService.
- Add a `meminfo` Binder dump example to the Binder guide and simplify the Kotlin examples in the integration guides.
- Restore the root READMEs with links to the full documentation and playground.
