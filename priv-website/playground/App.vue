<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useData, withBase } from 'vitepress';

const { lang, isDark: dark } = useData();
const host = ref<HTMLElement>();
const useLegacyPackaging = ref(true);
const status = ref<'loading' | 'ready' | 'error'>('loading');
const chinese = computed(() => lang.value.startsWith('zh'));
const docsHref = computed(() => chinese.value ? '/zh/' : '/');
const languageHref = computed(() => withBase(chinese.value ? '/playground/' : '/zh/playground/'));
let generation = 0;
let observer: MutationObserver | undefined;
let timeout: ReturnType<typeof setTimeout> | undefined;
let updateOptions: ((dark: boolean, useLegacyPackaging: boolean) => void) | undefined;
const originalLanguages = Object.getOwnPropertyDescriptor(navigator, 'languages');

function clear() {
  generation++;
  observer?.disconnect();
  observer = undefined;
  clearTimeout(timeout);
  updateOptions = undefined;
  host.value?.replaceChildren();
}

async function mount() {
  clear();
  const container = host.value;
  if (!container) return;
  const current = generation;
  status.value = 'loading';
  // Compose reads navigator.languages; restore it when returning to the documentation.
  Object.defineProperty(navigator, 'languages', {
    configurable: true,
    get: () => [chinese.value ? 'zh-Hans' : 'en'],
  });
  window.dispatchEvent(new Event('languagechange'));
  const fail = () => {
    if (generation !== current) return;
    clear();
    status.value = 'error';
  };
  timeout = setTimeout(fail, 60_000);
  try {
    const { renderPrivilegePlayground } = await import('priv-playground');
    if (generation !== current) return;
    observer = new MutationObserver(() => {
      if (!container.hasChildNodes()) return;
      status.value = 'ready';
      clearTimeout(timeout);
      observer?.disconnect();
    });
    observer.observe(container, { childList: true });
    updateOptions = renderPrivilegePlayground(container, dark.value, useLegacyPackaging.value);
  } catch (cause) {
    console.error('Could not mount the UI playground', cause);
    fail();
  }
}

function retry() {
  // A failed dynamic import can remain cached for this document's lifetime.
  location.reload();
}

onMounted(() => { void mount(); });
watch([dark, useLegacyPackaging], ([appearance, legacyPackaging]) => updateOptions?.(appearance, legacyPackaging));
watch(lang, () => { void mount(); });
onBeforeUnmount(() => {
  clear();
  if (originalLanguages) Object.defineProperty(navigator, 'languages', originalLanguages);
  else Reflect.deleteProperty(navigator, 'languages');
  window.dispatchEvent(new Event('languagechange'));
});
</script>

<template>
  <div class="playground">
    <div class="min-h-dvh bg-stone-100 font-sans text-stone-900 dark:bg-stone-950 dark:text-stone-100">
      <header class="border-b border-stone-200 bg-white dark:border-stone-800 dark:bg-stone-900">
        <div class="mx-auto flex min-h-16 max-w-5xl flex-wrap items-center justify-between gap-3 px-5 py-3">
          <a :href="docsHref" class="flex items-center gap-3 font-semibold tracking-tight">
            <span class="grid size-8 place-items-center rounded-lg bg-violet-600 text-sm text-white">P</span>
            <span>Priv Kit <span class="ml-1 font-normal text-stone-400">/ Playground</span></span>
          </a>
          <nav class="flex items-center gap-2 text-sm">
            <a :href="docsHref" class="rounded-lg px-3 py-2 hover:bg-stone-100 dark:hover:bg-stone-800">
              {{ chinese ? '文档' : 'Docs' }} ↗
            </a>
            <a :href="languageHref" :hreflang="chinese ? 'en' : 'zh-CN'" rel="alternate" class="rounded-lg border border-stone-200 px-3 py-2 hover:bg-stone-100 dark:border-stone-700 dark:hover:bg-stone-800">
              {{ chinese ? 'English' : '中文' }}
            </a>
            <button type="button" class="rounded-lg border border-stone-200 px-3 py-2 hover:bg-stone-100 dark:border-stone-700 dark:hover:bg-stone-800" @click="dark = !dark">
              {{ dark ? (chinese ? '浅色' : 'Light') : (chinese ? '深色' : 'Dark') }}
            </button>
          </nav>
        </div>
      </header>

      <main class="mx-auto max-w-2xl px-4 py-8 sm:px-6 sm:py-12">
        <div class="mb-6 flex flex-wrap items-end justify-between gap-3">
          <div>
            <p class="mb-2 text-xs font-semibold tracking-widest text-violet-600 dark:text-violet-400">COMPOSE MULTIPLATFORM</p>
            <h1 class="text-2xl font-semibold tracking-tight">{{ chinese ? '授权界面预览' : 'Authorization UI preview' }}</h1>
          </div>
          <span class="rounded-full border border-stone-300 px-3 py-1 text-xs text-stone-500 dark:border-stone-700 dark:text-stone-400">{{ chinese ? '交互模拟' : 'Interactive simulation' }}</span>
        </div>
        <p class="mb-6 text-sm leading-6 text-stone-500 dark:text-stone-400">
          {{ chinese ? '体验各启动方式、配对与确认弹窗。数据仅保存在本次页面会话中，操作默认模拟成功，不会修改真实设备。' : 'Explore startup methods, pairing, and confirmation dialogs. Data lives in this page session; operations simulate success without changing a real device.' }}
        </p>

        <div class="mb-5 flex items-center justify-between gap-4">
          <div>
            <label for="legacy-packaging" class="cursor-pointer font-mono text-sm">useLegacyPackaging</label>
            <p id="legacy-packaging-description" class="mt-1 text-xs text-stone-500 dark:text-stone-400">
              {{ useLegacyPackaging
                ? (chinese ? '手动命令：直接执行解压后的原生库' : 'Manual command: execute the extracted native library')
                : (chinese ? '手动命令：通过 linker64 加载 APK 内的原生库' : 'Manual command: load the native library inside the APK with linker64') }}
            </p>
          </div>
          <button
            id="legacy-packaging"
            type="button"
            role="switch"
            :aria-checked="useLegacyPackaging"
            aria-describedby="legacy-packaging-description"
            class="relative h-6 w-11 shrink-0 cursor-pointer rounded-full transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-violet-600"
            :class="useLegacyPackaging ? 'bg-violet-600' : 'bg-stone-400 dark:bg-stone-600'"
            @click="useLegacyPackaging = !useLegacyPackaging"
          >
            <span class="absolute top-0.5 left-0.5 size-5 rounded-full bg-white shadow-sm transition-transform" :class="useLegacyPackaging ? 'translate-x-5' : 'translate-x-0'" />
          </button>
        </div>

        <section class="relative overflow-hidden rounded-2xl border border-stone-200 bg-white shadow-sm dark:border-stone-800 dark:bg-stone-900" :aria-label="chinese ? '授权页面' : 'Authorization page'">
          <div ref="host" class="h-[760px] w-full overflow-hidden" />
          <div v-if="status !== 'ready'" class="absolute inset-0 grid place-content-center gap-4 bg-white px-8 text-center dark:bg-stone-900" role="status">
            <template v-if="status === 'loading'">
              <div class="mx-auto size-7 animate-spin rounded-full border-2 border-violet-200 border-t-violet-600" />
              <p class="text-sm font-medium">{{ chinese ? '正在加载展示页面…' : 'Loading the playground…' }}</p>
              <p class="max-w-xs text-xs leading-5 text-stone-500">{{ chinese ? '首次打开需要下载运行时与中文字体。' : 'The first visit downloads the runtime and Chinese font.' }}</p>
            </template>
            <template v-else>
              <p class="text-sm font-medium">{{ chinese ? '页面未能加载' : 'Unable to load the preview' }}</p>
              <p class="max-w-xs text-xs leading-5 text-stone-500">{{ chinese ? '请检查网络，并使用支持 WasmGC 的浏览器。' : 'Check your connection and use a browser with WasmGC support.' }}</p>
              <button type="button" class="mx-auto rounded-lg bg-violet-600 px-4 py-2 text-sm text-white hover:bg-violet-700" @click="retry">{{ chinese ? '重试' : 'Retry' }}</button>
            </template>
          </div>
        </section>
        <p class="mt-5 text-center text-xs text-stone-400">{{ chinese ? '共享 Kotlin UI · 在浏览器中运行' : 'Shared Kotlin UI · Running in your browser' }}</p>
      </main>
    </div>
  </div>
</template>

<style scoped>
/* Supply the border defaults previously provided by the standalone page's Preflight. */
@layer base {
  .playground,
  .playground :where(*) {
    border-style: solid;
    border-width: 0;
  }
}
</style>
