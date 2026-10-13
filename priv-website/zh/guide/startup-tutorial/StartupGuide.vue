<script setup lang="ts">
import { computed, ref, onMounted, onBeforeUnmount, nextTick } from 'vue';
import content from './content.ts';
import GuideStep from './GuideStep.vue';
import BrandSettings from './BrandSettings.vue';
import OperationDiagram from './OperationDiagram.vue';
import HelpPanel from './HelpPanel.vue';
const props = defineProps<{ chinese?: boolean }>();
const t = (zh: string, en: string) => props.chinese ? zh : en;
const scene = ref('start');
const method = ref('');
const brand = ref('');
const help = ref('');
const origin = ref('');
const activeStep = ref('developer-options');
const root = ref<HTMLElement>();
const mounted = ref(false);
const phoneSteps = ['prepare-phone', 'pairing', 'start-and-confirm'] as const;
const computerSteps = ['prepare-computer', 'run-command'] as const;
const steps = computed(() => method.value === 'computer' ? computerSteps : phoneSteps);
const stepLabel = (id: string) => ({ 'prepare-phone': t('调试', 'Debugging'), pairing: t('配对', 'Pair'), 'start-and-confirm': t('启动', 'Start'), 'prepare-computer': t('连接', 'Connect'), 'run-command': t('执行', 'Run') }[id] || id);
const helpIds = ['missing-wireless', 'pairing-discovery', 'pairing-failed', 'start-failed', 'computer-problems', 'feature-failed', 'no-notification'];
const brandIds = content.brands.map(b => b.id);
const helpTitle = (id: string) => id === 'start-failed' ? t('启动失败', 'Startup failed') : id === 'no-notification' ? t('没有通知', 'No notification') : content.sections[id as keyof typeof content.sections]?.title || id;
const stepHelp = (id: string) => ({
  'prepare-phone': ['missing-wireless'], 'pairing': ['no-notification', 'pairing-discovery', 'pairing-failed'],
  'start-and-confirm': ['start-failed'], 'prepare-computer': ['computer-problems'], 'run-command': ['computer-problems']
}[id] || []).map(id => ({ id, title: helpTitle(id) }));
const checkpoint = (id: string) => ({
  'prepare-phone': t('无线调试开关已开启。', 'Wireless debugging is switched on.'),
  'pairing': t('应用提示配对成功。接下来还需要点击启动。', 'The app confirms pairing. Next, tap Start.'),
  'start-and-confirm': t('应用显示“已连接”，就完成了。', 'The app shows “Connected”. You are done.'),
  'prepare-computer': t('Tango 已连接到你的手机。', 'Tango is connected to your phone.'),
  'run-command': t('应用显示“已连接”，就完成了。', 'The app shows “Connected”. You are done.')
}[id] || '');
function resolve(id: string) {
  help.value = ''; origin.value = '';
  if (!id || ['startup-tutorial', 'choose-method'].includes(id)) { scene.value = 'start'; method.value = ''; }
  else if (id === 'restart') scene.value = 'restart';
  else if (id === 'tcp-ip') { scene.value = 'advanced'; method.value = 'static'; }
  else if (id === 'permission-restrictions' || brandIds.includes(id)) { scene.value = 'permissions'; if (brandIds.includes(id)) brand.value = id; }
  else if (id === 'faq' || helpIds.includes(id)) { scene.value = 'help'; help.value = id === 'faq' ? '' : id; if (!method.value || method.value === 'computer') method.value = 'phone'; }
  else if (['manual', ...computerSteps].includes(id as typeof computerSteps[number])) { scene.value = 'start'; method.value = 'computer'; activeStep.value = id === 'manual' ? 'developer-options' : id; }
  else if (['adb', 'developer-options', 'wireless-debugging', ...phoneSteps].includes(id as typeof phoneSteps[number])) { scene.value = 'start'; if (id !== 'developer-options' || !method.value || method.value === 'static') method.value = 'phone'; activeStep.value = id === 'adb' || id === 'wireless-debugging' ? 'developer-options' : id; }
}
function scrollTo(id: string, focus = false) {
  nextTick(() => {
    const target = root.value?.querySelector<HTMLElement>('[id="' + id + '"]') || document.getElementById(id);
    target?.scrollIntoView({ block: 'start' });
    if (focus && target) { target.tabIndex = -1; target.focus({ preventScroll: true }); }
  });
}
function save(id: string) {
  history.pushState({ ...history.state, tutorial: { scene: scene.value, method: method.value, help: help.value, origin: origin.value, step: activeStep.value } }, '', '#' + id);
}
function go(id: string) { resolve(id); save(id); scrollTo(id, true); }
function choose(value: string) { method.value = value; scene.value = 'start'; help.value = ''; origin.value = ''; activeStep.value = 'developer-options'; save(value === 'phone' ? 'wireless-debugging' : 'manual'); scrollTo('developer-options', true); }
function openHelp(id: string, from: string) { help.value = id; origin.value = from; activeStep.value = from; save(id); scrollTo(id, true); }
function closeHelp() { const target = origin.value; help.value = ''; origin.value = ''; save(target); scrollTo(target, true); }
function selectBrand(value: string) { brand.value = value; if (scene.value === 'permissions') { save(brandIds.includes(value) ? value : 'permission-restrictions'); } }
function syncLocation() {
  let id: string;
  try { id = decodeURIComponent(location.hash.slice(1)); } catch { return; }
  resolve(id);
  const state = history.state?.tutorial;
  if (state && typeof state.scene === 'string' && typeof state.method === 'string') {
    scene.value = state.scene; method.value = state.method; help.value = state.help || ''; origin.value = state.origin || ''; activeStep.value = state.step || 'developer-options';
  }
  if (id) scrollTo(id);
}
function followLink(event: MouseEvent) {
  const link = (event.target as Element).closest('a');
  if (link?.hash && link.pathname === location.pathname && link.origin === location.origin) setTimeout(syncLocation, 0);
}
let scrollFrame = 0;
function trackStep() {
  if (scrollFrame || scene.value !== 'start' || !method.value) return;
  scrollFrame = requestAnimationFrame(() => {
    scrollFrame = 0;
    const ids = ['developer-options', ...steps.value];
    let current = ids[0];
    for (const id of ids) {
      const element = document.getElementById(id);
      if (element && element.getBoundingClientRect().top <= 185) current = id;
    }
    activeStep.value = current;
  });
}
onMounted(() => { mounted.value = true; syncLocation(); window.addEventListener('hashchange', syncLocation); window.addEventListener('popstate', syncLocation); window.addEventListener('scroll', trackStep, { passive: true }); });
onBeforeUnmount(() => { window.removeEventListener('hashchange', syncLocation); window.removeEventListener('popstate', syncLocation); window.removeEventListener('scroll', trackStep); cancelAnimationFrame(scrollFrame); });
</script>

<template>
  <div ref="root" class="startup-guide" @click="followLink">
    <p class="guide-intro">{{ t('找到适合你的步骤，一次只处理眼前的问题。', 'Find the right steps and focus on one task at a time.') }}</p>
    <nav class="scenario-nav" :aria-label="t('使用场景', 'Your situation')">
      <button type="button" :aria-pressed="scene === 'start'" @click="go('choose-method')"><span>01</span>{{ t('首次启动', 'First-time setup') }}</button>
      <button type="button" :aria-pressed="scene === 'restart'" @click="go('restart')"><span>02</span>{{ t('再次启动', 'Start again') }}</button>
      <button type="button" :aria-pressed="['help', 'permissions'].includes(scene)" @click="go('faq')"><span>03</span>{{ t('解决问题', 'Get help') }}</button>
    </nav>

    <section v-show="scene === 'start' || !mounted" id="choose-method" class="scene-section">
      <div class="section-heading"><div><p class="eyebrow">{{ t('选择一种方式', 'CHOOSE A METHOD') }}</p><h2>{{ t('你准备怎么启动？', 'How will you start?') }}</h2></div><button type="button" class="text-button" @click="go('tcp-ip')">{{ t('进阶：静态端口 →', 'Advanced: static port →') }}</button></div>
      <div class="method-grid grid gap-4 sm:grid-cols-2">
        <button type="button" class="method-card" :aria-pressed="method === 'phone'" @click="choose('phone')"><span class="method-symbol" aria-hidden="true">▯</span><strong>{{ t('只用手机', 'Phone only') }}</strong><span>{{ t('Android 11 及以上 · Wi-Fi', 'Android 11+ · Wi-Fi') }}</span><b>{{ t('查看操作步骤 →', 'View the steps →') }}</b></button>
        <button type="button" class="method-card" :aria-pressed="method === 'computer'" @click="choose('computer')"><span class="method-symbol" aria-hidden="true">▱</span><strong>{{ t('使用电脑', 'Use a computer') }}</strong><span>{{ t('电脑 · USB 数据线 · 无需下载工具', 'Computer · USB data cable · No tool download') }}</span><b>{{ t('查看操作步骤 →', 'View the steps →') }}</b></button>
      </div>
      <p v-if="!method" class="muted">{{ t('不知道 Android 版本？在设置中搜索“Android 版本”，或进入“关于手机”查看。没有无线调试时，选择电脑方式。', 'Not sure of your Android version? Search Settings for “Android version” or check About phone. Use a computer if wireless debugging is unavailable.') }}</p>
      <template v-if="method === 'phone' || method === 'computer'">
        <nav class="step-nav" :aria-label="t('操作步骤', 'Setup steps')">
          <button type="button" :aria-current="activeStep === 'developer-options' ? 'step' : undefined" @click="go('developer-options')">{{ t('准备', 'Prepare') }}</button>
          <button v-for="(id, index) in steps" :key="id" type="button" :aria-current="activeStep === id ? 'step' : undefined" @click="go(id)">{{ index + 1 }} · {{ stepLabel(id) }}</button>
        </nav>
        <section id="developer-options" class="preparation">
          <p class="eyebrow">{{ t('首次使用前', 'BEFORE YOU BEGIN') }}</p><h2>{{ t('开启开发者模式', 'Enable Developer options') }}</h2>
          <p>{{ t('选择品牌找到版本号，连续点击 7 次；随后回到设置，搜索“开发者选项”，开启总开关（若有）。', 'Find your version field below and tap it 7 times. Then search Settings for Developer options and enable its main switch, if shown.') }}</p>
          <BrandSettings :model-value="brand" :chinese="!!chinese" @update:model-value="selectBrand" />
          <button type="button" class="primary-button" @click="go(steps[0])">{{ t('已经开启，查看启动步骤 ↓', 'Already enabled? Continue ↓') }}</button>
        </section>
        <div :id="method === 'phone' ? 'wireless-debugging' : 'manual'" class="route-heading"><span v-if="method === 'phone'" id="adb" /><h2>{{ method === 'phone' ? t('在手机上完成启动', 'Start on your phone') : t('用电脑帮助手机启动', 'Start with a computer') }}</h2><p v-if="method === 'computer'" class="notice">{{ t('Tango 必须在电脑的 Chrome 或 Edge 中打开；数据线需要支持数据传输。', 'Open Tango in Chrome or Edge on the computer. Use a USB cable that supports data transfer.') }}</p></div>
        <GuideStep v-for="(id, index) in steps" :id="id" :key="id" :number="index + 1" :title="content.steps[id].title" :actions="content.steps[id].actions" :chinese="!!chinese" :checkpoint="checkpoint(id)" :help="stepHelp(id)" :show-devices="method === 'computer'" @help="openHelp($event, id)">
          <p v-if="id === 'pairing'" class="notice">{{ t('输入期间保持系统配对码窗口打开。配对成功后还需要点击启动。', 'Keep the system pairing window open while entering the code. After pairing, you still need to tap Start.') }}</p>
          <OperationDiagram v-if="id === 'pairing' || id === 'run-command'" :kind="id === 'pairing' ? 'pairing' : 'terminal'" :chinese="!!chinese" />
          <p v-if="id === 'run-command'" class="muted">{{ t('使用当前应用复制的完整命令；更新或重装后重新复制。', 'Use the complete command copied from the current app. Copy again after updates or reinstalls.') }}</p>
          <template #help><HelpPanel v-if="help && origin === id" :topic="help" :method="method" :chinese="!!chinese" inline @close="closeHelp" @navigate="go" /></template>
        </GuideStep>
        <div class="finish-card"><strong>{{ t('看到“已连接”？启动就完成了。', 'See “Connected”? Setup is complete.') }}</strong><p>{{ t('可以返回应用使用功能。下面的帮助只在需要时查看。', 'Return to the app to use its features. Open the help below only when needed.') }}</p><div class="choice-row"><button type="button" @click="go('restart')">{{ t('下次怎么启动', 'Starting next time') }}</button><button type="button" @click="go('permission-restrictions')">{{ t('已连接，但功能不能用', 'Connected, but features fail') }}</button></div></div>
      </template>
    </section>

    <section v-show="scene === 'restart' || !mounted" id="restart" class="scene-section"><p class="eyebrow">{{ t('再次使用', 'WELCOME BACK') }}</p><h2>{{ t('下次怎么启动', 'Starting again') }}</h2><div class="restart-copy" v-html="content.sections.restart.html" /><div class="choice-row"><button type="button" @click="choose('phone')">{{ t('查看手机步骤', 'Phone steps') }}</button><button type="button" @click="choose('computer')">{{ t('查看电脑步骤', 'Computer steps') }}</button></div></section>

    <section v-show="scene === 'help' || !mounted" id="faq" class="scene-section"><p class="eyebrow">{{ t('按现象找答案', 'FIND YOUR SYMPTOM') }}</p><h2>{{ t('你遇到了什么问题？', 'What is happening?') }}</h2><div class="issue-grid"><button v-for="id in helpIds.filter(id => id !== 'no-notification')" :key="id" type="button" :aria-pressed="help === id" @click="go(id)">{{ helpTitle(id) }} <span aria-hidden="true">→</span></button></div><HelpPanel v-if="help && !origin" :topic="help" :chinese="!!chinese" :method="method" @method="method = $event" @navigate="go" /></section>

    <section v-show="scene === 'permissions' || !mounted" id="permission-restrictions" class="scene-section"><p class="eyebrow">{{ t('服务已连接之后', 'AFTER CONNECTING') }}</p><h2>{{ t('已连接，但功能不能用？', 'Connected, but features fail?') }}</h2><p>{{ t('部分系统会限制修改权限或模拟点击。选择品牌，只查看与你有关的设置。', 'Some systems restrict permission changes or simulated taps. Choose your brand to see the relevant settings.') }}</p><span v-for="id in brandIds" :id="id" :key="id" class="anchor-marker" /><BrandSettings :model-value="brand" :chinese="!!chinese" permissions @update:model-value="selectBrand" /><button type="button" class="text-button" @click="go('faq')">{{ t('← 查看其他问题', '← Other problems') }}</button></section>

    <section v-show="scene === 'advanced' || !mounted" id="tcp-ip" class="scene-section"><p class="eyebrow">{{ t('进阶方式', 'ADVANCED') }}</p><h2>{{ t('静态端口', 'Static port') }}</h2><div v-html="content.sections['tcp-ip'].html" /><button type="button" class="choice-button" @click="openHelp('start-failed', 'tcp-ip')">{{ t('静态端口启动失败？', 'Static port failed to start?') }}</button><HelpPanel v-if="help && origin === 'tcp-ip'" :topic="help" method="static" :chinese="!!chinese" inline @close="closeHelp" @navigate="go" /><button type="button" class="text-button" @click="go('choose-method')">{{ t('← 返回常规启动方式', '← Standard startup methods') }}</button></section>
    <div v-if="!mounted" class="static-guide">
      <section id="developer-options"><h2>{{ t('准备：开启开发者模式', 'Prepare: Enable Developer options') }}</h2><p>{{ t('在设置的“关于手机”中连续点击版本号 7 次，再回到设置开启开发者选项。菜单因品牌而异。', 'Tap the version field 7 times in Settings → About phone, then enable Developer options. Labels vary by brand.') }}</p></section>
      <span id="adb" /><span id="wireless-debugging" /><span id="manual" />
      <GuideStep v-for="(id, index) in [...phoneSteps, ...computerSteps]" :id="id" :key="id" :number="index + 1" :title="content.steps[id].title" :actions="content.steps[id].actions" :chinese="!!chinese" :checkpoint="checkpoint(id)" :help="[]" :show-devices="computerSteps.includes(id as typeof computerSteps[number])" />
      <HelpPanel v-for="id in helpIds" :key="id" :topic="id" method="phone" :chinese="!!chinese" />
      <section v-for="item in content.brands.filter(item => item.settings.length)" :key="item.id"><h3>{{ item.name }}</h3><ul><li v-for="setting in item.settings" :key="setting.label">{{ setting.label }}: {{ setting.on ? t('开启', 'Turn on') : t('关闭', 'Turn off') }}</li></ul><div v-html="item.html" /></section>
    </div>
    <noscript>{{ t('启用 JavaScript 可使用场景选择和原地排错；上方提供完整文字说明。', 'Enable JavaScript for scenario selection and inline help. Full text instructions are available above.') }}</noscript>
  </div>
</template>

<style src="./guide.css" scoped></style>
