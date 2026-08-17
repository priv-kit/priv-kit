---
layout: home

hero:
  name: Priv Kit
  text: An app-owned privileged Android runtime
  tagline: Priv Kit supports startup through Root, ADB, Manual, and external authorization bridges, with a Compose UI for quick authorization.
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
    details: Embed the Compose authorization page for startup status, pairing, permissions, and exact silent replay.
    link: /guide/priv-ui
  - title: Startup methods
    details: Start through Root, wireless ADB, static TCP, Manual, or an app-provided external bridge.
    link: /guide/activation
  - title: Binder access
    details: Connect to explicit system services while preserving raw Binder contracts and failure semantics.
    link: /guide/binder
  - title: Built-in file proxy
    details: Access files and stream directory scans in the privileged process without defining a UserService.
    link: /guide/file-proxy
  - title: App-defined UserService
    details: Run application-owned privileged work in an embedded or dedicated process.
    link: /guide/user-service
---
