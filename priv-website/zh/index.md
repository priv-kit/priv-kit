---
layout: home

hero:
  name: Priv Kit
  text: 为 Android 应用提供特权进程
  tagline: 通过 Root 或 ADB 启动，使用 Binder、文件、命令和自定义服务。
  image:
    src: /priv-kit-mark.svg
    alt: Android
  actions:
    - theme: brand
      text: 开始使用
      link: /zh/guide/getting-started
    - theme: alt
      text: 什么是 Priv Kit？
      link: /zh/guide/what-is-priv-kit
    - theme: alt
      text: GitHub
      link: https://github.com/priv-kit/priv-kit

features:
  - title: Privilege UI
    details: 用 Compose 页面处理启动、配对、权限请求和自动恢复。
    link: /zh/guide/priv-ui
  - title: 多种启动方式
    details: 支持 Root、无线 ADB、静态 TCP、手动以及应用提供的外部授权器。
    link: /zh/guide/activation
  - title: Binder 访问
    details: 通过特权进程调用系统服务。
    link: /zh/guide/binder
  - title: 内置文件代理
    details: 在特权进程中访问文件并流式遍历目录树。
    link: /zh/guide/file-proxy
  - title: 应用自定义 UserService
    details: 在服务端或独立进程中运行自己的 AIDL 服务。
    link: /zh/guide/user-service
---
