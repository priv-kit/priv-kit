---
description: 特权服务已启动但 ADB 权限受限时，按系统查找解决方法。
---

# 解决 ADB 权限限制 {#permission-restrictions}

特权服务已启动，但修改应用权限、模拟点击等操作仍可能受到厂商系统限制。
请按设备系统查找以下设置。选项名称和位置可能随系统版本变化。

## 小米 HyperOS / MIUI {#xiaomi}

在**开发者选项**中开启 **USB 调试（安全设置）**。它与普通的“USB 调试”是两个不同的开关。
该选项的说明通常为“允许通过 USB 调试修改权限或模拟点击”。

## ColorOS（OPPO / OnePlus） {#coloros}

在**开发者选项**中关闭**权限监控**。

部分新版系统若在中文界面下找不到该选项，可尝试将系统语言切换为英文，
再进入开发者选项查找。按实际显示的名称操作：

- **Permission monitoring**：关闭。
- **Disable permission monitoring**：开启。
- 部分新版显示 **Disable system optimization**：开启。

实际提供的选项以当前系统版本为准。

## OxygenOS（一加海外版） {#oxygenos}

在**开发者选项**中开启 **Disable permission monitoring**（停用权限监控）。
部分新版将该选项显示为 **Disable system optimization**，同样需要开启。

## realme UI（真我） {#realme}

在**开发者选项**中开启 **Disable permission monitoring**（停用权限监控）。
部分版本可能不提供此选项。

## OriginOS / Funtouch OS（vivo / iQOO） {#vivo}

如果服务已经启动，但无法模拟点击，可在**开发者选项**中开启
**USB 模拟点击 / USB Simulated Input**（若当前版本提供）。
此设置针对输入控制，不代表能解除其他 Shell 权限限制。

## Flyme（魅族） {#flyme}

在**开发者选项**中关闭 **Flyme 支付保护**。
