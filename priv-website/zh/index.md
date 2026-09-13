---
layout: home

hero:
  name: Priv Kit
  text: Android 应用自有特权运行时
  tagline: 支持通过 Root、ADB、手动命令和外部授权器启动，并提供 Compose 快捷授权界面。
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
    details: 嵌入 Compose 授权页面，直接获得启动状态、配对、权限请求和静默启动能力。
    link: /zh/guide/priv-ui
  - title: 多种启动方式
    details: 支持 Root、无线 ADB、静态 TCP、手动以及应用提供的外部授权器。
    link: /zh/guide/activation
  - title: Binder 访问
    details: 连接系统服务并执行底层 Binder 调用，调用格式和失败处理由应用决定。
    link: /zh/guide/binder
  - title: 内置文件代理
    details: 无需定义 UserService，直接在特权进程中访问文件并流式遍历目录树。
    link: /zh/guide/file-proxy
  - title: 应用自定义 UserService
    details: 在嵌入式或独立进程中运行应用自己的特权服务。
    link: /zh/guide/user-service
---
