// Localized tutorial content; HTML is trusted repository copy.
export default {
  "sections": {
    "restart": {
      "title": "下次怎么启动",
      "html": "<ul>\n<li><strong>仍已连接：</strong>可以直接使用应用功能。</li>\n<li><strong>上次只用手机启动，现在已断开：</strong>连上 Wi-Fi，开启<strong>无线调试</strong>，再回到应用点<strong>ADB → 启动</strong>。应用提示未配对时才需要重新配对。</li>\n<li><strong>上次用电脑启动，现在已断开：</strong>连接手机，再执行应用<strong>手动</strong>页提供的命令。应用更新或重装后请重新复制。</li>\n</ul>\n"
    },
    "tcp-ip": {
      "title": "进阶：静态端口",
      "html": "<p>配对完成后，可用<strong>静态端口 → 启动</strong>按提示开启端口。只在可信网络使用，端口以应用显示为准。</p>\n<p>打开或关闭静态端口会中断其他 ADB 连接。停止入口在<strong>控制 → 停止</strong>。配对端口不能用作静态端口，Tango 终端也不能执行 <code>adb tcpip</code>。</p>\n"
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
      "html": "<ul>\n<li><strong>找不到手机：</strong>在电脑的 Chrome 或 Edge 中打开 Tango，确认 USB 调试已开启。如果手机只能充电，试试更换数据线或 USB 接口。</li>\n<li><strong>等待授权：</strong>解锁手机，在 USB 调试提示里点击<strong>允许</strong>。没有弹窗时重新插拔数据线。</li>\n<li><strong>设备被占用：</strong>关闭其他调试工具，或查阅 <a href=\"https://help.tangoapp.dev/features/troubleshooting/\" target=\"_blank\" rel=\"noreferrer\">Tango 连接排查</a>。</li>\n<li><strong>找不到 adb：</strong>在 Tango 终端里，删掉命令开头的 <code>adb shell </code>。</li>\n<li><strong>路径不存在：</strong>从应用的<strong>手动</strong>页重新复制命令。</li>\n</ul>\n"
    },
    "feature-failed": {
      "title": "已连接，但功能不能用",
      "html": "<p>出现“权限受限”时，选择设备品牌查看设置。仍有问题，把手机型号、系统版本和错误信息发给应用维护者。</p>\n"
    },
    "permission-restrictions": {
      "title": "解决 ADB 权限限制",
      "html": "<p>有些系统会限制修改应用权限或模拟点击。按品牌查找对应设置，名称可能随系统版本变化。</p>\n"
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
          "html": "在<strong>设置</strong>中搜索<strong>无线调试</strong>，进入<strong>开发者选项</strong>并打开无线调试开关。若系统询问是否允许在当前网络上调试，请在可信网络上点击<strong>允许</strong>。"
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
          "html": "<strong>保持配对码窗口打开</strong>，从屏幕顶部下拉通知栏，在本应用的配对通知中输入六位配对码。配对码过期后，重新打开配对窗口获取新码。"
        }
      ]
    },
    "start-and-confirm": {
      "title": "点击启动",
      "actions": [
        {
          "device": "phone",
          "html": "应用提示配对成功后，返回<strong>ADB</strong>页点击<strong>启动</strong>。保持无线调试开启，等服务显示<strong>已连接</strong>，再使用应用功能。"
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
          "html": "将命令粘贴到 Tango 终端，只删除开头的 <code>adb shell </code>，保留其余空格和引号，然后按<strong>回车</strong>。"
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
      "重新打开「使用配对码配对设备」，获取新的六位配对码。",
      "保持配对窗口打开，通过通知、分屏或小窗输入配对码。"
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
