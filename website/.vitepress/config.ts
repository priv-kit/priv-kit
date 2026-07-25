import process from 'node:process';
import { fileURLToPath } from 'node:url';
import { defineConfig } from 'vitepress';

const base = process.env.DOCS_BASE ?? '/';
const viteConfigPath = fileURLToPath(new URL('../vite.config.ts', import.meta.url));

const englishSidebar = [
  {
    text: 'Start here',
    items: [
      { text: 'What is Priv Kit?', link: '/guide/what-is-priv-kit' },
      { text: 'Getting started', link: '/guide/getting-started' },
      { text: 'Privilege UI', link: '/guide/priv-ui' },
      { text: 'Startup methods', link: '/guide/activation' },
    ],
  },
  {
    text: 'Use the runtime',
    items: [
      { text: 'Binder', link: '/guide/binder' },
      { text: 'UserService', link: '/guide/user-service' },
    ],
  },
];

const chineseSidebar = [
  {
    text: '接入',
    items: [
      { text: '什么是 Priv Kit？', link: '/zh/guide/what-is-priv-kit' },
      { text: '快速接入', link: '/zh/guide/getting-started' },
      { text: 'Privilege UI', link: '/zh/guide/priv-ui' },
      { text: '启动方式', link: '/zh/guide/activation' },
    ],
  },
  {
    text: '使用',
    items: [
      { text: 'Binder', link: '/zh/guide/binder' },
      { text: 'UserService', link: '/zh/guide/user-service' },
    ],
  },
];

export default defineConfig({
  base,
  title: 'Priv Kit',
  description:
    'Start and manage an app-owned privileged Android runtime through Root or ADB.',
  cleanUrls: true,
  lastUpdated: true,
  appearance: true,
  vite: {
    configFile: viteConfigPath,
  },
  head: [
    ['link', { rel: 'icon', href: `${base}priv-kit-mark.svg`, type: 'image/svg+xml' }],
  ],
  locales: {
    root: {
      label: 'English',
      lang: 'en-US',
      title: 'Priv Kit',
      description:
        'Start and manage an app-owned privileged Android runtime through Root or ADB.',
      themeConfig: {
        sidebar: englishSidebar,
        outline: { label: 'On this page', level: [2, 4] },
        editLink: {
          pattern: 'https://github.com/priv-kit/priv-kit/edit/main/website/:path',
          text: 'Edit this page on GitHub',
        },
        lastUpdated: {
          text: 'Last updated',
          formatOptions: {
            dateStyle: 'medium',
            timeStyle: 'short',
          },
        },
        docFooter: {
          prev: 'Previous',
          next: 'Next',
        },
        notFound: {
          title: 'Page not found',
          quote: 'The runtime path exists, but this documentation route does not.',
          linkLabel: 'Go to documentation home',
          linkText: 'Back to Priv Kit',
        },
      },
    },
    zh: {
      label: '简体中文',
      lang: 'zh-CN',
      link: '/zh/',
      title: 'Priv Kit',
      description: '通过 Root 或 ADB 启动并管理应用自有的 Android 特权运行时。',
      themeConfig: {
        sidebar: chineseSidebar,
        outline: { label: '本页内容', level: [2, 4] },
        editLink: {
          pattern: 'https://github.com/priv-kit/priv-kit/edit/main/website/:path',
          text: '在 GitHub 上编辑此页',
        },
        lastUpdated: {
          text: '最后更新',
          formatOptions: {
            dateStyle: 'medium',
            timeStyle: 'short',
          },
        },
        docFooter: {
          prev: '上一页',
          next: '下一页',
        },
        notFound: {
          title: '页面不存在',
          quote: '运行时路径仍然存在，但这个文档路由不存在。',
          linkLabel: '返回文档首页',
          linkText: '返回 Priv Kit',
        },
        darkModeSwitchLabel: '外观',
        lightModeSwitchTitle: '切换到浅色模式',
        darkModeSwitchTitle: '切换到深色模式',
        sidebarMenuLabel: '菜单',
        returnToTopLabel: '返回顶部',
        langMenuLabel: '切换语言',
        skipToContentLabel: '跳到正文',
      },
    },
  },
  themeConfig: {
    logo: '/priv-kit-mark.svg',
    socialLinks: [
      {
        icon: 'github',
        link: 'https://github.com/priv-kit/priv-kit',
        ariaLabel: 'Priv Kit on GitHub',
      },
    ],
    search: {
      provider: 'local',
      options: {
        locales: {
          zh: {
            translations: {
              button: {
                buttonText: '搜索',
                buttonAriaLabel: '搜索文档',
              },
              modal: {
                displayDetails: '显示详细列表',
                resetButtonTitle: '重置搜索',
                backButtonTitle: '关闭搜索',
                noResultsText: '没有找到相关结果',
                footer: {
                  selectText: '选择',
                  selectKeyAriaLabel: '回车',
                  navigateText: '导航',
                  navigateUpKeyAriaLabel: '上箭头',
                  navigateDownKeyAriaLabel: '下箭头',
                  closeText: '关闭',
                  closeKeyAriaLabel: 'Escape',
                },
              },
            },
          },
        },
      },
    },
  },
});
