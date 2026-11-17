// Localized tutorial content; HTML is trusted repository copy.
export default {
  "sections": {
    "restart": {
      "title": "Start again",
      "html": "<ul>\n<li><strong>Connected:</strong> continue using the app.</li>\n<li><strong>Disconnected after phone-only startup:</strong> reconnect to Wi-Fi, turn on <strong>Wireless debugging</strong>, then return to <strong>ADB → Start</strong>. Pair again only if the app asks.</li>\n<li><strong>Disconnected after computer startup:</strong> connect the phone and run the command from the app’s <strong>Manual</strong> page again. Copy a fresh command after an app update or reinstall.</li>\n</ul>\n"
    },
    "tcp-ip": {
      "title": "Advanced: Static port",
      "html": "<p>After pairing, use <strong>Static port → Start</strong> and follow the prompt. Use a trusted network and the port shown by the app.</p>\n<p>Opening or stopping the port interrupts other ADB connections. Stop it under <strong>Control → Stop</strong>. The pairing port is separate, and Tango’s terminal cannot run <code>adb tcpip</code>.</p>\n"
    },
    "missing-wireless": {
      "title": "Cannot find wireless debugging",
      "html": ""
    },
    "pairing-discovery": {
      "title": "Still searching for a pairing service",
      "html": ""
    },
    "pairing-failed": {
      "title": "Pairing fails or the code expires",
      "html": ""
    },
    "computer-problems": {
      "title": "Computer cannot find the phone or command",
      "html": "<ul>\n<li><strong>Phone missing:</strong> open Tango in Chrome or Edge on the computer and check that USB debugging is on. If the phone only charges, try a USB data cable and another port.</li>\n<li><strong>Awaiting approval:</strong> unlock the phone and tap <strong>Allow</strong> on the USB debugging prompt. If there is no prompt, reconnect the cable.</li>\n<li><strong>Device busy:</strong> close other debugging tools or see <a href=\"https://help.tangoapp.dev/features/troubleshooting/\" target=\"_blank\" rel=\"noreferrer\">Tango troubleshooting</a>.</li>\n<li><strong>adb not found:</strong> remove the initial <code>adb shell </code> when using Tango’s terminal.</li>\n<li><strong>Path missing:</strong> copy a fresh command from the app’s <strong>Manual</strong> page.</li>\n</ul>\n"
    },
    "feature-failed": {
      "title": "Connected but features still fail",
      "html": "<p>If the app reports restricted permissions, choose your device brand to view its settings. Report your device model, system version, and operation error to the app maintainer.</p>\n"
    },
    "permission-restrictions": {
      "title": "Resolve ADB permission restrictions",
      "html": "<p>Some systems restrict permission changes or simulated taps. Look up your device brand. Setting names vary by version.</p>\n"
    }
  },
  "steps": {
    "prepare-phone": {
      "title": "Prepare your phone",
      "actions": [
        {
          "device": "phone",
          "html": "Connect the phone to <strong>Wi-Fi</strong>."
        },
        {
          "device": "phone",
          "html": "In <strong>Settings</strong>, search for <strong>Wireless debugging</strong> and turn it on in <strong>Developer options</strong>. If Android asks whether to allow debugging on this network, tap <strong>Allow</strong> on a network you trust."
        }
      ]
    },
    "pairing": {
      "title": "Pair this app",
      "actions": [
        {
          "device": "phone",
          "html": "In this app, open <strong>ADB → Pair</strong> and allow notifications."
        },
        {
          "device": "phone",
          "html": "In system settings, open <strong>Wireless debugging → Pair device with pairing code</strong>."
        },
        {
          "device": "phone",
          "html": "<strong>Keep the code window open.</strong> Swipe down from the top of the screen, find this app’s pairing notification, and enter the six-digit code. If it expires, reopen the pairing window for a new code."
        }
      ]
    },
    "start-and-confirm": {
      "title": "Start and confirm",
      "actions": [
        {
          "device": "phone",
          "html": "Once the app confirms pairing, return to <strong>ADB</strong> and tap <strong>Start</strong>. Leave Wireless debugging on and wait for <strong>Connected</strong> before using the app’s features."
        }
      ]
    },
    "prepare-computer": {
      "title": "Prepare the computer and connect",
      "actions": [
        {
          "device": "phone",
          "html": "Enable <strong>USB debugging</strong> in Developer options and connect the phone with a USB data cable."
        },
        {
          "device": "computer",
          "html": "Open <a href=\"https://app.tangoapp.dev/\" target=\"_blank\" rel=\"noreferrer\">Tango</a> in an up-to-date <strong>Chrome or Edge</strong> browser on the computer. No ADB tool download is needed."
        },
        {
          "device": "computer",
          "html": "Add a USB device, select your phone, and connect."
        },
        {
          "device": "phone",
          "html": "Unlock the phone, accept its USB debugging prompt, and wait until Tango shows it connected. Only authorize a computer you trust."
        }
      ]
    },
    "run-command": {
      "title": "Run the command supplied by this app",
      "actions": [
        {
          "device": "computer",
          "html": "In Tango, select <strong>+ → Terminal</strong> at the right of the top tab bar."
        },
        {
          "device": "phone",
          "html": "Open this app's <strong>Manual</strong> page on the phone, tap <strong>Copy</strong>, and transfer the complete command to the computer."
        },
        {
          "device": "computer",
          "html": "Paste the command into Tango’s terminal. Remove only the initial <code>adb shell </code>, keeping the other spaces and quotation marks, then press <strong>Enter</strong>."
        },
        {
          "device": "phone",
          "html": "Return to this app and confirm that the service is connected."
        }
      ]
    }
  },
  "brands": [
    {
      "id": "xiaomi",
      "name": "Xiaomi / Redmi",
      "path": [
        "About phone / My device",
        "Detailed info and specs / All specs",
        "OS version / MIUI version"
      ],
      "html": "<p>This is separate from the ordinary USB debugging switch. Its description refers to modifying permissions or simulating input through USB debugging.</p>",
      "settings": [
        {
          "label": "USB debugging (Security settings)",
          "on": true
        }
      ]
    },
    {
      "id": "coloros",
      "name": "OPPO / OnePlus",
      "path": [
        "About device / About phone",
        "Version",
        "Version number"
      ],
      "html": "<p>On some newer versions, if the option is missing in the Chinese interface, try switching the system language to English and check again. Availability depends on your system version.</p>",
      "settings": [
        {
          "label": "Permission monitoring",
          "on": false
        },
        {
          "label": "Disable permission monitoring",
          "on": true
        },
        {
          "label": "Disable system optimization",
          "on": true
        }
      ]
    },
    {
      "id": "realme",
      "name": "realme",
      "path": [
        "About device / About phone",
        "Version",
        "Version number"
      ],
      "html": "<p>Some system versions may not provide this option.</p>",
      "settings": [
        {
          "label": "Disable permission monitoring",
          "on": true
        }
      ]
    },
    {
      "id": "vivo",
      "name": "vivo / iQOO",
      "path": [
        "System management",
        "About phone",
        "Software version"
      ],
      "html": "<p>Use this setting only if your version provides it. It concerns simulated input and does not remove other Shell permission restrictions.</p>",
      "settings": [
        {
          "label": "USB Simulated Input",
          "on": true
        }
      ]
    },
    {
      "id": "samsung",
      "name": "Samsung",
      "path": [
        "About phone",
        "Software information",
        "Build number"
      ],
      "html": "",
      "settings": []
    },
    {
      "id": "honor",
      "name": "HONOR",
      "path": [
        "About phone",
        "Build number"
      ],
      "html": "",
      "settings": []
    },
    {
      "id": "huawei",
      "name": "Huawei",
      "path": [
        "About phone",
        "Build number"
      ],
      "html": "",
      "settings": []
    },
    {
      "id": "oxygenos",
      "name": "OnePlus global",
      "path": [],
      "html": "<p>The setting name varies by system version. Follow the label shown on your phone.</p>",
      "settings": [
        {
          "label": "Disable permission monitoring",
          "on": true
        },
        {
          "label": "Disable system optimization",
          "on": true
        }
      ]
    },
    {
      "id": "flyme",
      "name": "Meizu",
      "path": [],
      "html": "",
      "settings": [
        {
          "label": "Flyme payment protection",
          "on": false
        }
      ]
    }
  ],
  "locale": "en",
  "helpChecks": {
    "missing-wireless": [
      "Check the Android version in Settings. Wireless debugging requires Android 11+.",
      "Enable Developer options and search Settings for Wireless debugging.",
      "If the system does not provide it, use a computer."
    ],
    "pairing-discovery": [
      "Connect to Wi-Fi.",
      "Open Pair device with pairing code and keep the window open.",
      "Allow local network access if requested, then retry.",
      "Reopen the pairing window or try another trusted Wi-Fi network if discovery still fails."
    ],
    "pairing-failed": [
      "Reopen Pair device with pairing code to get a new six-digit code.",
      "Keep the window open and enter the code using the notification, split-screen, or a floating window."
    ]
  },
  "startChecks": {
    "phone": [
      "Check that the phone is connected to Wi-Fi.",
      "Check that Wireless debugging is still on in Developer options.",
      "Return to the app and tap Start again."
    ],
    "static": [
      "Reopen the static port as prompted by the app.",
      "Enter the configured static port.",
      "Return to the app and tap Start again."
    ]
  }
};
