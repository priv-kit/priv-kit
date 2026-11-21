# What's Changed

<!--
Keep only the changes for the current release in this file.
Replace the content when preparing the next release; release history is preserved by GitHub Releases.
-->

- Split the default Material 3 UI into the new `priv-ui-m3` module. Use `PrivilegeM3Page()` for the default startup page, or build custom components with the theme-independent `priv-ui` module and `PrivilegeUiHost`.
- Rework authorization around a shared coordinator with lifecycle-aware platform bindings, cancellation, and support for custom access handlers.
- Keep ADB and external-provider checks active while the page is resumed, independently of the selected tab. Let `startupModes` configure defaults and automatic startup order without restricting explicit startup actions.
- Update the sample app and playground for the new UI modules, refresh sample launcher icons, and revise the integration guides.
- Upgrade Gradle to 9.9.2, Android Gradle Plugin to 9.5.1, compile SDK to 37.1, and Compose and other dependencies.
