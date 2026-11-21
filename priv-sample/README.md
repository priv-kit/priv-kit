# priv-sample

Android examples for startup, Binder, files, commands, UserService, and input. See the [integration guides](../priv-website/guide/getting-started.md).

| Variant | Minimum Android | Starter packaging |
| --- | --- | --- |
| `legacy` | Android 8 / API 26 | Extracted native libraries |
| `api29` | Android 10 / API 29 | Load from the APK |

Build with `:priv-sample:assembleLegacyDebug` or `:priv-sample:assembleApi29Debug`. For minified validation, use `:priv-sample:assembleLegacyRelease`.

The Debug pages test Binder forwarding, permissions, input, UserService, and Shizuku. To test crash reports, bind the embedded UserService and select **Crash Privileged Process**, then open **Crash Logs**.

The startup page in `PrivilegeSampleUiPage.kt` demonstrates custom rendering with `PrivilegeUiHost` and `PrivilegeM3Screen`, using one `PrivilegeM3ViewModel` for business state, presentation and authorization. Use `PrivilegeM3Page` for the default complete page.
