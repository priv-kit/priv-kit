---
layout: home

hero:
  name: Priv Kit
  text: Privileged processes for Android apps
  tagline: Start with Root or ADB. Access Binder, files, commands, and your own services.
  image:
    src: /priv-kit-mark.svg
    alt: Android
  actions:
    - theme: brand
      text: Get started
      link: /guide/getting-started
    - theme: alt
      text: What is Priv Kit?
      link: /guide/what-is-priv-kit
    - theme: alt
      text: GitHub
      link: https://github.com/priv-kit/priv-kit

features:
  - title: Privilege UI
    details: A Compose page for startup, pairing, permissions, and automatic recovery.
    link: /guide/priv-ui
  - title: Startup methods
    details: Start through Root, wireless ADB, static TCP, Manual, or an app-provided external bridge.
    link: /guide/activation
  - title: Binder access
    details: Call system services through the privileged process.
    link: /guide/binder
  - title: Built-in file proxy
    details: Access files and stream directory walks in the privileged process.
    link: /guide/file-proxy
  - title: App-defined UserService
    details: Run your AIDL service inside the server or in a separate process.
    link: /guide/user-service
---
