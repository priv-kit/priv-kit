// Localized tutorial content; HTML is trusted repository copy.
export default {
  "sections": {
    "restart": {
      "title": "下次怎么启动",
      "html": "<ul>\n<li><strong>仍已连接：</strong>不用操作。</li>\n<li><strong>服务停止或手机重启：</strong>恢复 Wi-Fi、开启无线调试，再点启动；手动方式则重新执行当前命令。</li>\n<li><strong>配对失效、重装应用或撤销授权：</strong>再配对。</li>\n</ul>\n"
    },
    "tcp-ip": {
      "title": "进阶：静态端口",
      "html": "<p>新手可跳过。已有可用的无线配对时，点击 <strong>静态端口 → 启动</strong>，按提示开启端口；若显示“通过无线调试打开静态端口”，也可使用。是否支持、端口是多少，以应用为准。</p>\n<p>开启、重启或停止端口会中断其他 ADB 连接。只在可信网络使用；停止入口在<strong>控制 → 停止</strong>。配对端口不能当作静态端口。</p>\n<p>Tango <strong>终端</strong>不能执行 <code>adb tcpip</code>。电脑配置需要支持静态端口配置的功能或工具；也可直接用上面的手动启动。</p>\n"
    },
    "missing-wireless": {
      "title": "找不到无线调试",
      "html": ""
    },
    "pairing-discovery": {
      "title": "一直搜索配对服务",
      "html": ""
    },
    "pairing-failed": {
      "title": "配对失败／配对码不对",
      "html": ""
    },
    "computer-problems": {
      "title": "Tango 连不上／命令执行失败",
      "html": "<ul>\n<li><strong>看不到手机：</strong>用最新版 Chrome／Edge，检查 USB 调试、数据线和接口。</li>\n<li><strong>等待授权：</strong>解锁手机，允许 USB 调试。</li>\n<li><strong>设备被占用：</strong>关闭其他手机助手或调试工具；仍失败参阅 <a href=\"https://help.tangoapp.dev/features/troubleshooting/\" target=\"_blank\" rel=\"noreferrer\">Tango 连接排查</a>。</li>\n<li><strong>提示找不到 adb：</strong>删掉命令开头的 <code>adb shell </code>。</li>\n<li><strong>路径不存在：</strong>重新从当前应用复制命令。</li>\n</ul>\n"
    },
    "feature-failed": {
      "title": "已连接，但功能不能用",
      "html": "<p>出现“权限受限”时，选择设备品牌查看设置。仍有问题，把手机型号、系统版本和错误信息发给应用维护者。</p>\n"
    },
    "permission-restrictions": {
      "title": "解决 ADB 权限限制",
      "html": "<p>特权服务已启动，但修改应用权限、模拟点击等操作仍可能受到厂商系统限制。\n请按设备系统查找以下设置。选项名称和位置可能随系统版本变化。</p>\n"
    }
  },
  "steps": {
    "prepare-phone": {
      "title": "打开无线调试",
      "actions": [
        {
          "device": "phone",
          "html": "手机连接 <strong>Wi-Fi</strong>。"
        },
        {
          "device": "phone",
          "html": "在<strong>开发者选项</strong>中开启<strong>无线调试</strong>，允许当前网络。"
        }
      ]
    },
    "pairing": {
      "title": "配对应用",
      "actions": [
        {
          "device": "phone",
          "html": "返回本应用，进入 <strong>ADB → 配对</strong>，允许通知权限。"
        },
        {
          "device": "phone",
          "html": "打开系统的 <strong>无线调试 → 使用配对码配对设备</strong>。"
        },
        {
          "device": "phone",
          "html": "<strong>保持配对码窗口打开</strong>，下拉通知栏，在本应用的通知中输入六位配对码。"
        }
      ]
    },
    "start-and-confirm": {
      "title": "点击启动",
      "actions": [
        {
          "device": "phone",
          "html": "点击<strong>启动</strong>，确认服务显示<strong>已连接</strong>。"
        }
      ]
    },
    "prepare-computer": {
      "title": "连接手机",
      "actions": [
        {
          "device": "phone",
          "html": "在开发者选项中开启 <strong>USB 调试</strong>，用数据线连接电脑。"
        },
        {
          "device": "computer",
          "html": "用 <strong>Chrome 或 Edge</strong> 打开 <a href=\"https://app.tangoapp.dev/\" target=\"_blank\" rel=\"noreferrer\">Tango</a>，按提示添加 USB 设备，选中手机。"
        },
        {
          "device": "phone",
          "html": "解锁并确认<strong>允许 USB 调试</strong>。只授权自己信任的电脑。"
        }
      ]
    },
    "run-command": {
      "title": "打开终端，执行命令",
      "actions": [
        {
          "device": "computer",
          "html": "点击 Tango <strong>顶部标签栏最右侧「＋」→ 终端</strong>。"
        },
        {
          "device": "phone",
          "html": "在本应用的<strong>手动</strong>页点击<strong>复制</strong>，把命令传到电脑。"
        },
        {
          "device": "computer",
          "html": "<strong>删除开头的 <code>adb shell </code>，其余内容不变</strong>，粘贴到 Tango 终端，按回车。"
        },
        {
          "device": "phone",
          "html": "返回应用，确认服务显示<strong>已连接</strong>。"
        }
      ]
    }
  },
  "brands": [
    {
      "id": "xiaomi",
      "name": "小米 / 红米",
      "path": [
        "关于手机 / 我的设备",
        "详细信息与规格 / 全部参数",
        "OS 版本 / MIUI 版本"
      ],
      "html": "<p>它与普通的“USB 调试”是两个不同的开关。选项说明通常为“允许通过 USB 调试修改权限或模拟点击”。</p>",
      "settings": [
        {
          "label": "USB 调试（安全设置）",
          "on": true
        }
      ]
    },
    {
      "id": "coloros",
      "name": "OPPO / 一加",
      "path": [
        "关于本机 / 关于手机",
        "版本信息",
        "版本号"
      ],
      "html": "<p>先在中文界面的开发者选项中查找“权限监控”，找到后关闭。若显示“停用权限监控”或“停用系统优化”等名称，则按上表开启对应选项。中文界面找不到时，部分新版系统可尝试切换为英文，按括号内的英文名称查找。名称与可用选项以当前系统实际显示为准。</p>",
      "settings": [
        {
          "label": "权限监控（Permission monitoring）",
          "on": false
        },
        {
          "label": "停用权限监控（Disable permission monitoring）",
          "on": true
        },
        {
          "label": "停用系统优化（Disable system optimization）",
          "on": true
        }
      ]
    },
    {
      "id": "realme",
      "name": "真我",
      "path": [
        "关于本机 / 关于手机",
        "版本信息",
        "版本号"
      ],
      "html": "<p>部分系统版本可能不提供此选项。</p>",
      "settings": [
        {
          "label": "停用权限监控（Disable permission monitoring）",
          "on": true
        }
      ]
    },
    {
      "id": "vivo",
      "name": "vivo / iQOO",
      "path": [
        "系统管理",
        "关于手机",
        "软件版本号"
      ],
      "html": "<p>仅在当前版本提供该设置时操作。此设置针对模拟点击，不代表能解除其他 Shell 权限限制。</p>",
      "settings": [
        {
          "label": "USB 模拟点击",
          "on": true
        }
      ]
    },
    {
      "id": "samsung",
      "name": "三星",
      "path": [
        "关于手机",
        "软件信息",
        "编译编号"
      ],
      "html": "",
      "settings": []
    },
    {
      "id": "honor",
      "name": "荣耀",
      "path": [
        "关于手机",
        "版本号"
      ],
      "html": "",
      "settings": []
    },
    {
      "id": "huawei",
      "name": "华为",
      "path": [
        "关于手机",
        "版本号"
      ],
      "html": "",
      "settings": []
    },
    {
      "id": "oxygenos",
      "name": "一加海外版",
      "path": [],
      "html": "<p>不同系统版本的选项名称可能不同，按实际显示的名称操作。</p>",
      "settings": [
        {
          "label": "停用权限监控（Disable permission monitoring）",
          "on": true
        },
        {
          "label": "停用系统优化（Disable system optimization）",
          "on": true
        }
      ]
    },
    {
      "id": "flyme",
      "name": "魅族",
      "path": [],
      "html": "",
      "settings": [
        {
          "label": "Flyme 支付保护",
          "on": false
        }
      ]
    }
  ],
  "locale": "zh",
  "helpChecks": {
    "missing-wireless": [
      "在设置中查看 Android 版本，无线调试需要 Android 11 及以上。",
      "确认开发者选项已开启，并在设置中搜索“无线调试”。",
      "系统没有提供这个选项时，改用电脑启动。"
    ],
    "pairing-discovery": [
      "连接 Wi-Fi。",
      "打开“使用配对码配对设备”窗口并保持打开。",
      "若应用请求本地网络权限，允许后重试。",
      "仍搜索不到时，重新打开配对窗口，或换一个可信的 Wi-Fi。"
    ],
    "pairing-failed": [
      "重新打开系统配对窗口，获取新的六位配对码。",
      "输入“使用配对码配对设备”窗口中显示的配对码。",
      "输入期间保持窗口打开，可使用通知栏、分屏或小窗。"
    ]
  },
  "startChecks": {
    "phone": [
      "确认手机已连接 Wi-Fi。",
      "返回开发者选项，确认无线调试仍然开启。",
      "返回应用，再点击启动。"
    ],
    "static": [
      "按应用提示重新开启静态端口。",
      "填写已配置的静态端口。",
      "返回应用，再点击启动。"
    ]
  }
};
