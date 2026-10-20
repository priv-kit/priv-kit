# priv-input

Android input injection. The app supplies a privileged system input-service Binder.

`PrivilegeInput(binder)` provides Android input commands. `shellCommand(vararg args)` runs the device implementation on Android 12+. `compatShellCommand(vararg args)` fills missing device commands/options using the local implementation on Android 8+. Both shell-command methods are synchronous. Convenience methods such as `tap` and `swipe` are cancellable suspend functions.

For Linux device queries, touch gestures and raw writes, add [priv-evdev](../priv-evdev).

See the [input guide](../priv-website/guide/input.md) for setup and examples, and [architecture](../docs/architecture.md#输入) for internals.
