import content from './content.ts';

// Feed the same component content to VitePress's Markdown-only local search index.
export function tutorialSearchContent(): string {
  const sections = Object.entries(content.sections).map(([id, section]) =>
    `## ${section.title} {#${id}}\n\n${section.html}\n${(content.helpChecks[id as keyof typeof content.helpChecks] || []).map(check => `<p>${check}</p>`).join('\n')}\n`,
  );
  const steps = Object.entries(content.steps).map(([id, step]) =>
    `## ${step.title} {#${id}}\n\n${step.actions.map(action => `<p>${action.html}</p>`).join('\n')}\n`,
  );
  const brands = content.brands.filter(brand => brand.settings.length).map(brand =>
    `## ${brand.name} {#${brand.id}}\n\n${brand.settings.map(setting => `<p>${setting.label}: ${content.locale === 'zh' ? (setting.on ? '开启' : '关闭') : (setting.on ? 'Turn on' : 'Turn off')}</p>`).join('\n')}\n${brand.html}\n`,
  );
  const startup = `## ${content.locale === 'zh' ? '启动失败' : 'Startup failed'} {#start-failed}\n\n${[...content.startChecks.phone, ...content.startChecks.static].map(check => `<p>${check}</p>`).join('\n')}\n`;
  return [...steps, ...sections, ...brands, startup].join('\n');
}
