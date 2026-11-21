# priv-ui-m3

Default Material 3 startup page. This module exposes `priv-ui` transitively.

```kotlin
implementation("io.github.priv-kit:priv-ui-m3:$privKitVersion")
```

```kotlin
import priv.kit.ui.m3.PrivilegeM3Page

MaterialTheme {
    PrivilegeM3Page()
}
```

The page supplies its ViewModel, permissions, pairing and confirmations. Pass `onBack` to connect app navigation, or a `PrivilegeM3ViewModel` when using custom startup configuration.

- [Integration guide](../priv-website/guide/priv-ui.md)
- [Browser preview](https://priv-kit.pages.dev/playground/)
