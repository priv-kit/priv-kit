import process from 'node:process';
import { fileURLToPath } from 'node:url';
import { defineConfig, type MarkdownRenderer } from 'vitepress';
import { tutorialSearchContent as englishTutorialSearch } from '../guide/startup-tutorial/search.ts';
import { tutorialSearchContent as chineseTutorialSearch } from '../zh/guide/startup-tutorial/search.ts';

const base = process.env.DOCS_BASE ?? '/';
const viteConfigPath = fileURLToPath(new URL('../vite.config.ts', import.meta.url));
const tutorialSearchOptions = {
  async _render(src: string, env: { relativePath: string; frontmatter?: { search?: boolean } }, md: Pick<MarkdownRenderer, 'renderAsync'>) {
    const tutorial = env.relativePath === 'guide/startup-tutorial.md'
      ? englishTutorialSearch
      : env.relativePath === 'zh/guide/startup-tutorial.md' ? chineseTutorialSearch : null;
    const html = await md.renderAsync(tutorial ? `${src}\n\n${tutorial()}` : src, env);
    return env.frontmatter?.search === false ? '' : html;
  },
};

const englishSidebar = [
  {
    text: 'Start here',
    items: [
      { text: 'What is Priv Kit?', link: '/guide/what-is-priv-kit' },
      { text: 'Getting started', link: '/guide/getting-started' },
      { text: 'Privilege UI', link: '/guide/priv-ui' },
      { text: 'Startup methods', link: '/guide/activation' },
      { text: 'Startup tutorial & FAQ', link: '/guide/startup-tutorial' },
    ],
  },
  {
    text: 'Use the runtime',
    items: [
      { text: 'Binder', link: '/guide/binder' },
      { text: 'File proxy', link: '/guide/file-proxy' },
      { text: 'Commands', link: '/guide/commands' },
      { text: 'UserService', link: '/guide/user-service' },
    ],
  },
  {
    text: 'Community',
    items: [{ text: 'Ecosystem', link: '/ecosystem' }],
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
      { text: '启动教程与常见问题', link: '/zh/guide/startup-tutorial' },
    ],
  },
  {
    text: '使用',
    items: [
      { text: 'Binder', link: '/zh/guide/binder' },
      { text: '文件代理', link: '/zh/guide/file-proxy' },
      { text: '命令执行', link: '/zh/guide/commands' },
      { text: 'UserService', link: '/zh/guide/user-service' },
    ],
  },
  {
    text: '社区',
    items: [{ text: '社区生态', link: '/zh/ecosystem' }],
  },
];

export default defineConfig({
  markdown: {
    config(md) {
      // Keep the exported guide portable while using the default theme's heading anchors.
      md.core.ruler.before('block', 'portable-guide-anchors', (state) => {
        state.src = state.src.replace(
          /^<a id="([a-z0-9](?:[a-z0-9-]*[a-z0-9])?)"><\/a>\r?\n\r?\n(#{1,6} .+)$/gm,
          '$2 {#$1}',
        );
      });
    },
  },
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
        nav: [{ text: 'UI playground', link: '/playground/' }],
        outline: { label: 'On this page', level: [2, 4] },
        editLink: {
          pattern: 'https://github.com/priv-kit/priv-kit/edit/main/priv-website/:path',
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
        nav: [{ text: 'UI 展示', link: '/zh/playground/' }],
        outline: { label: '本页内容', level: [2, 4] },
        editLink: {
          pattern: 'https://github.com/priv-kit/priv-kit/edit/main/priv-website/:path',
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
        ...tutorialSearchOptions,
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
