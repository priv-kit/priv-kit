# priv-evdev

Experimental Linux evdev access for Android. APIs may change. The app supplies a privileged process with access to the device nodes.

| Entry | Use |
| --- | --- |
| `PrivilegeEvdevController.createLocal(context)` | Device queries, raw writes and touch gestures in the current process |
| `PrivilegeEvdevController.fromBinder(binder)` | Access a controller exported by your UserService through `asBinder()` |

Touch gestures are cancellable suspend functions, including across a UserService Binder. Cancellation waits for contact release. Queries and raw writes are synchronous.

Close controllers and device handles with `use` or `close()`. Public types use `priv.kit.evdev`; JNI is packaged as `libprivkitinput.so`.

This module can be used independently of [priv-input](../priv-input), which provides system input injection. See the [input guide](../priv-website/guide/input.md) for setup, permissions and examples.
