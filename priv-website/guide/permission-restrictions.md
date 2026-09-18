---
description: Find system-specific solutions when the privileged service starts but ADB permissions remain restricted.
---

# Resolve ADB permission restrictions {#permission-restrictions}

A running privileged service may still be prevented from changing app permissions or simulating
taps by the device manufacturer's restrictions. Find your system below. Setting names and
locations can vary between system versions.

## Xiaomi HyperOS / MIUI {#xiaomi}

Enable **USB debugging (Security settings)** in **Developer options**. This is separate from
the ordinary USB debugging switch. Its description refers to modifying permissions or simulating
input through USB debugging.

## ColorOS (OPPO / OnePlus) {#coloros}

Disable **Permission monitoring** in **Developer options**.

On some newer versions, if this option is missing from the Chinese interface, try switching the
system language to English and check Developer options again. Follow the label shown:

- **Permission monitoring**: turn off.
- **Disable permission monitoring**: turn on.
- **Disable system optimization**, shown on some newer versions: turn on.

Available options depend on the installed system version.

## OxygenOS (global OnePlus devices) {#oxygenos}

Turn on **Disable permission monitoring** in **Developer options**.
Some newer versions label this option **Disable system optimization**; turn it on as well.

## realme UI (realme) {#realme}

Turn on **Disable permission monitoring** in **Developer options**.
Some versions may not provide this option.

## OriginOS / Funtouch OS (vivo / iQOO) {#vivo}

If the service is running but simulated taps do not work, enable **USB Simulated Input**
(**USB 模拟点击**) in **Developer options**, if available on your version.
This setting concerns input control; it does not imply that other Shell permission restrictions
will be removed.

## Flyme (Meizu) {#flyme}

Disable **Flyme payment protection** in **Developer options**.
