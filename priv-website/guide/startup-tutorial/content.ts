// Localized tutorial content; HTML is trusted repository copy.
export default {
  "sections": {
    "restart": {
      "title": "Start again",
      "html": "<ul>\n<li><strong>Service connected:</strong> no need to pair or start again.</li>\n<li><strong>Service stopped, pairing valid:</strong> restore Wi-Fi and wireless debugging, then try Start before deleting any pairing record.</li>\n<li><strong>Phone rebooted:</strong> check service status and start again if needed.</li>\n<li><strong>App reinstalled or debugging authorization revoked:</strong> pairing may be required again.</li>\n<li><strong>Manual startup:</strong> run the app's current command again after the service stops.</li>\n</ul>\n"
    },
    "tcp-ip": {
      "title": "Advanced: Static port",
      "html": "<p>Beginners can skip this. With an available wireless pairing, tap <strong>Static port → Start</strong> and follow the prompt to open a port. Use the separate wireless port setup action if shown. Availability and port depend on the app.</p>\n<p>Opening, restarting, or stopping the port interrupts other ADB connections. Use trusted networks; stop through <strong>Control → Stop</strong>. A pairing port is not a static port.</p>\n<p>Tango's <strong>terminal</strong> cannot run <code>adb tcpip</code>. Computer setup requires a feature or tool supporting static port configuration. You can instead use manual startup above.</p>\n"
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
      "html": "<ul>\n<li>Browser cannot select a USB device: use up-to-date Chrome or Edge on the computer and reopen Tango.</li>\n<li>Device list empty: check USB debugging, the data cable, and the USB port; unlock the phone and reconnect.</li>\n<li>Awaiting authorization: unlock the phone and accept the USB debugging prompt.</li>\n<li>Device in use: close phone management apps and other browser debugging tools using the connection. If an <code>adb</code> background process remains, follow <a href=\"https://help.tangoapp.dev/features/troubleshooting/\" target=\"_blank\" rel=\"noreferrer\">Tango's connection troubleshooting</a> to release it.</li>\n<li>Terminal reports <code>adb</code> not found: remove the initial <code>adb shell </code> from the copied command and keep the remainder.</li>\n<li>Missing startup path: copy the command again from the currently installed app.</li>\n</ul>\n"
    },
    "feature-failed": {
      "title": "Connected but features still fail",
      "html": "<p>If the app reports restricted permissions, choose your device brand to view its settings. Report your device model, system version, and operation error to the app maintainer.</p>\n"
    },
    "permission-restrictions": {
      "title": "Resolve ADB permission restrictions",
      "html": "<p>A running privileged service may still be prevented from changing app permissions or simulating\ntaps by the device manufacturer's restrictions. Find your system below. Setting names and\nlocations can vary between system versions.</p>\n"
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
          "html": "In <strong>Developer options</strong>, enable <strong>Wireless debugging</strong> and allow the current network."
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
          "html": "<strong>Keep the code window open.</strong> Pull down the notification shade and enter its six-digit code in this app's notification."
        }
      ]
    },
    "start-and-confirm": {
      "title": "Start and confirm",
      "actions": [
        {
          "device": "phone",
          "html": "Tap <strong>Start</strong> and check that the service shows <strong>Connected</strong>."
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
          "html": "<strong>Remove the initial <code>adb shell </code> and leave everything after it unchanged.</strong> Paste the remainder into Tango's terminal and press Enter. This terminal is already a phone shell."
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
      "Reopen the system pairing window to get a new six-digit code.",
      "Enter the code shown in the Pair device with pairing code window.",
      "Keep the window open while entering the code using the notification, split screen or a floating window."
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
