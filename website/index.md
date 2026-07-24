---
layout: home

hero:
  name: Priv Kit
  text: An app-owned privileged Android runtime
  tagline: Start through Root, wireless ADB, a manual shell command, or an external authorization bridge. Then use Binder or your own UserService while keeping runtime and domain logic under the application's control.
  image:
    src: /priv-kit-mark.svg
    alt: Android
  actions:
    - theme: brand
      text: Get started
      link: /guide/getting-started

features:
  - title: Activation paths
    details: Start through Root, wireless ADB, static TCP, a copied shell command, or an app-provided external bridge.
    link: /guide/activation
  - title: Binder access
    details: Connect to explicit system services while preserving raw Binder contracts and failure semantics.
    link: /guide/binder
  - title: App-defined UserService
    details: Run application-owned privileged work in an embedded or dedicated process.
    link: /guide/user-service
---
