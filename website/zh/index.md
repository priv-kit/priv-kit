---
layout: home

hero:
  name: Priv Kit
  text: Android 应用自有特权运行时
  tagline: 通过 Root、无线 ADB、手动 shell 命令或外部授权桥启动，随后使用 Binder 或应用自有的 UserService，让运行时和领域逻辑继续由应用掌控。
  image:
    src: /priv-kit-mark.svg
    alt: Android
  actions:
    - theme: brand
      text: 开始使用
      link: /zh/guide/getting-started

features:
  - title: 多种激活路径
    details: 支持 Root、无线 ADB、静态 TCP、复制 shell 命令以及由应用提供的外部桥。
    link: /zh/guide/activation
  - title: Binder 访问
    details: 连接显式系统服务，同时保留 raw Binder 契约和失败语义。
    link: /zh/guide/binder
  - title: 应用自定义 UserService
    details: 在嵌入式或独立进程中运行由应用自有的特权工作。
    link: /zh/guide/user-service
---
