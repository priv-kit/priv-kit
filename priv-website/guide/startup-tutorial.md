<a id="startup-tutorial"></a>

# Startup tutorial

Enable Developer options first, then choose a startup method. No programming knowledge is needed.

<a id="developer-options"></a>

## 1. Enable Developer options

1. Open **Settings → About phone**, find **Build number / OS version**, and tap it **7 times**. Enter your screen-lock credential if asked.
2. Search Settings for **Developer options** and open it. Turn on its main switch if shown.

Required for every method. Already enabled? [Choose a method](#choose-method).

<details>
<summary>Cannot find the version field? Browse by brand</summary>

In Settings, follow the path and tap its final item **7 times**.

**Xiaomi / Redmi**

About phone / My device → Detailed info and specs / All specs → **OS version / MIUI version**

**OPPO / OnePlus / realme**

About device / About phone → Version → **Version number**

**vivo / iQOO**

System management → About phone → **Software version** (About phone may be directly in Settings)

**Samsung**

About phone → Software information → **Build number**

**HONOR**

About phone → **Build number**

**Huawei (EMUI / HarmonyOS 4 or earlier)**

About phone → **Build number**

Follow the labels on your phone. This Android tutorial does not apply to HarmonyOS 5 or later.

</details>

<a id="choose-method"></a>

## 2. Choose a method

- **Phone only:** [Wireless debugging](#wireless-debugging), with Android 11+ and Wi-Fi.
- **Computer and USB cable:** [Manual startup](#manual), with no tool download.
- **Need help?** [Common problems](#faq) · [Permission restrictions](#permission-restrictions).

If your phone does not support wireless debugging, use manual startup.

<a id="adb"></a>

## ADB startup

First time? [Enable Developer options](#developer-options), then follow wireless debugging below. If you already configured a port, go to [Static port](#tcp-ip).

<a id="wireless-debugging"></a>

## Wireless debugging: phone only

First [enable Developer options](#developer-options), then follow the steps on this phone.

<a id="prepare-phone"></a>

### Prepare your phone

1. Connect the phone to **Wi-Fi**.
2. In **Developer options**, enable **Wireless debugging** and allow the current network.

<a id="pairing"></a>

### Pair this app

1. In this app, open **ADB → Pair** and allow notifications.
2. In system settings, open **Wireless debugging → Pair device with pairing code**.
3. **Keep the code window open.** Pull down the notification shade and enter its six-digit code in this app's notification.

**No notification?** Check notification access or choose to continue pairing. Use **split screen or a floating window** to keep the system code window and this app visible.

Need help? [Still searching](#pairing-discovery) · [Pairing failed](#pairing-failed).

<a id="start-and-confirm"></a>

### Start and confirm

Tap **Start** in this app. You are done when the service shows **Connected**.

**After pairing, you still need to tap Start.** [Startup failed?](#start-failed)

<a id="manual"></a>

## Manual startup: computer required

Prepare a computer and a USB data cable. No tool download is needed.

**Reading on your phone? Open Tango in your computer’s browser.**

<a id="prepare-computer"></a>

### Prepare the computer and connect

1. **Phone:** First [enable Developer options](#developer-options), then enable **USB debugging** there and connect the phone to the computer with a USB data cable.
2. **Computer:** Open [Tango](https://app.tangoapp.dev/) in an up-to-date **Chrome or Edge** browser on the computer. No ADB tool download is needed.
3. **Computer:** Follow the page to add a USB device, select your phone in the browser device picker, and connect.
4. **Phone:** Unlock the phone, accept its USB debugging prompt, and wait until Tango shows it connected. Only authorize a computer you trust.

<a id="run-command"></a>

### Run the command supplied by this app

1. **Computer:** In Tango, click the **+** button at the far right of the top tab bar, then select **Terminal** from the dropdown menu.
2. **Phone:** Open this app's **Manual** page on the phone, tap **Copy**, and transfer the complete command to the computer.
3. **Computer: Remove the initial `adb shell ` and leave everything after it unchanged.** Paste the remainder into Tango's terminal and press Enter. This terminal is already a phone shell.
4. **Phone:** Return to this app and confirm that the service is connected.

Use the complete command copied from your app; copy again after updates or reinstalls.

[Connection or command failed?](#computer-problems)

<a id="tcp-ip"></a>

## Advanced: Static port

Beginners can skip this. With an available wireless pairing, tap **Static port → Start** and follow the prompt to open a port. Use the separate wireless port setup action if shown. Availability and port depend on the app.

Opening, restarting, or stopping the port interrupts other ADB connections. Use trusted networks; stop through **Control → Stop**. A pairing port is not a static port.

Tango's **terminal** cannot run `adb tcpip`. Computer setup requires a feature or tool supporting static port configuration. You can instead use manual startup above.

<a id="restart"></a>

## Starting again and rebooting

- **Service connected:** no need to pair or start again.
- **Service stopped, pairing valid:** restore Wi-Fi and wireless debugging, then try Start before deleting any pairing record.
- **Phone rebooted:** check service status and start again if needed. Automatic recovery is not guaranteed on every system.
- **App reinstalled or debugging authorization revoked:** pairing may be required again.
- **Manual startup:** run the app's current command again after the service stops.

<a id="faq"></a>

## Common problems

<a id="missing-wireless"></a>

### Cannot find wireless debugging

Check the phone's Android version and whether Developer options is enabled. Search Settings for Wireless debugging; manufacturers may use different labels. Use manual startup if the system does not provide it.

<a id="pairing-discovery"></a>

### Still searching for a pairing service

Check Wi-Fi and open the actual Pair device with pairing code window, not just the wireless debugging switch. Keep that window open. If the app requests local network permission, allow it and retry. Reopen the code window if needed; try another trusted Wi-Fi network if discovery still fails.

<a id="pairing-failed"></a>

### Pairing fails or the code expires

Reopen the system pairing window and use its new six-digit code. Do not enter a connection port, pairing port, or screen-lock credential. Use notification input, split screen, or a floating window to avoid dismissing the system window.

<a id="start-failed"></a>

### Wireless debugging or static port startup fails

For wireless debugging, check Wi-Fi and the wireless debugging switch. For a static port, reopen the port as prompted by the app, then tap **Start**.

**If the steps above do not help, try restarting the device as a last resort.** This applies to wireless pairing failures and startup failures with either method. After restarting, reconnect to Wi-Fi, enable wireless debugging or reopen the static port, then retry pairing or startup. If it still fails, send the error message or startup log to the app maintainer.

<a id="computer-problems"></a>

### Computer cannot find the phone or command

- Browser cannot select a USB device: use up-to-date Chrome or Edge on the computer and reopen Tango.
- Device list empty: check USB debugging, the data cable, and the USB port; unlock the phone and reconnect.
- Awaiting authorization: unlock the phone and accept the USB debugging prompt.
- Device in use: close phone management apps and other browser debugging tools using the connection. If an `adb` background process remains, follow [Tango's connection troubleshooting](https://help.tangoapp.dev/features/troubleshooting/) to release it.
- Terminal reports `adb` not found: remove the initial `adb shell ` from the copied command and keep the remainder.
- Missing startup path: copy the command again from the currently installed app.

<a id="feature-failed"></a>

### Connected but features still fail

If the app reports restricted permissions, follow the system-specific settings below. Even without a warning, the system may restrict particular operations. Report your device model, system version, and operation error to the app maintainer.


<a id="permission-restrictions"></a>

## Resolve ADB permission restrictions

A running privileged service may still be prevented from changing app permissions or simulating
taps by the device manufacturer's restrictions. Find your system below. Setting names and
locations can vary between system versions.

<a id="xiaomi"></a>

### Xiaomi HyperOS / MIUI

Enable **USB debugging (Security settings)** in **Developer options**. This is separate from
the ordinary USB debugging switch. Its description refers to modifying permissions or simulating
input through USB debugging.

<a id="coloros"></a>

### ColorOS (OPPO / OnePlus)

Disable **Permission monitoring** in **Developer options**.

On some newer versions, if this option is missing from the Chinese interface, try switching the
system language to English and check Developer options again. Follow the label shown:

- **Permission monitoring**: turn off.
- **Disable permission monitoring**: turn on.
- **Disable system optimization**, shown on some newer versions: turn on.

Available options depend on the installed system version.

<a id="oxygenos"></a>

### OxygenOS (global OnePlus devices)

Turn on **Disable permission monitoring** in **Developer options**.
Some newer versions label this option **Disable system optimization**; turn it on as well.

<a id="realme"></a>

### realme UI (realme)

Turn on **Disable permission monitoring** in **Developer options**.
Some versions may not provide this option.

<a id="vivo"></a>

### OriginOS / Funtouch OS (vivo / iQOO)

If the service is running but simulated taps do not work, enable **USB Simulated Input**
(**USB 模拟点击**) in **Developer options**, if available on your version.
This setting concerns input control; it does not imply that other Shell permission restrictions
will be removed.

<a id="flyme"></a>

### Flyme (Meizu)

Disable **Flyme payment protection** in **Developer options**.
