# What's Changed

<!--
Keep only the changes for the current release in this file.
Replace the content when preparing the next release; release history is preserved by GitHub Releases.
-->

- Add the optional `priv-external-shizuku` module for authorization and service startup with Shizuku API v13 or later. The Sample now uses this module.
- Register `PrivilegeExternalShizuku` in `PrivilegeUiConfig.externalStartProviders` to enable Shizuku in the built-in startup page. Apps with custom external startup providers must migrate from the previous UI interfaces to Core's `PrivilegeExternalStartProvider`.
- Streamline the integration guides and expand the phone and computer instructions in the startup tutorial.
