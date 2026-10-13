<script setup lang="ts">
import { computed } from 'vue';
import content from './content.ts';
const props = defineProps<{ topic: string; chinese: boolean; method: string; inline?: boolean }>();
defineEmits<{ close: []; method: [value: string]; navigate: [id: string] }>();
const t = (zh: string, en: string) => props.chinese ? zh : en;
const titles = computed(() => ({ 'start-failed': t('启动失败，按顺序检查', 'Startup failed: check in order'), 'no-notification': t('没有收到配对通知', 'No pairing notification') }));
const entry = computed(() => content.sections[props.topic as keyof typeof content.sections]);
const checks = computed(() => content.helpChecks[props.topic as keyof typeof content.helpChecks] || []);
const startupChecks = computed(() => content.startChecks[props.method === 'static' ? 'static' : 'phone']);
</script>

<template>
  <aside :id="topic" class="help-panel" tabindex="-1" :aria-label="t('问题排查', 'Troubleshooting')">
    <header><span class="eyebrow">{{ t('一起排查', 'LET’S CHECK') }}</span><h3>{{ titles[topic as keyof typeof titles] || entry?.title }}</h3></header>
    <template v-if="topic === 'start-failed'">
      <div v-if="!inline" class="choice-row" role="group" :aria-label="t('启动方式', 'Startup method')"><button type="button" :aria-pressed="method !== 'static'" @click="$emit('method', 'phone')">{{ t('无线调试', 'Wireless debugging') }}</button><button type="button" :aria-pressed="method === 'static'" @click="$emit('method', 'static')">{{ t('静态端口', 'Static port') }}</button></div>
      <ol class="check-list"><li v-for="(check, index) in startupChecks" :key="check"><span>{{ index + 1 }}</span>{{ check }}</li></ol>
      <div class="last-resort"><strong>{{ t('仍然失败？', 'Still failing?') }}</strong><p>{{ method === 'static' ? t('最后尝试重启设备，再按应用提示重新开启静态端口并启动。', 'As a last resort, restart the device, reopen the static port as prompted and start again.') : t('最后尝试重启设备，重新连接 Wi-Fi、开启无线调试，再尝试启动；配对失效时重新配对。', 'As a last resort, restart the device, reconnect Wi-Fi, enable wireless debugging and retry. Pair again if authorization has expired.') }}</p><p>{{ t('仍无效时，把错误信息或启动日志发给应用维护者。', 'If it still fails, send the error or startup log to the app maintainer.') }}</p></div>
    </template>
    <template v-else-if="topic === 'no-notification'"><ol><li>{{ t('检查应用的通知权限是否开启。', 'Check that notifications are allowed for this app.') }}</li><li>{{ t('也可以选择继续配对，用分屏或小窗同时显示系统配对窗口和应用。', 'Alternatively, continue pairing using split screen or a floating window to keep both the system pairing window and the app visible.') }}</li></ol></template>
    <ol v-else-if="checks.length" class="check-list"><li v-for="(check, index) in checks" :key="check"><span>{{ index + 1 }}</span>{{ check }}</li></ol>
    <div v-else-if="entry" class="help-copy" v-html="entry.html" />
    <p v-if="topic === 'pairing-failed' || topic === 'pairing-discovery'" class="muted">{{ t('以上方法都无效时，最后尝试重启设备，再连接 Wi-Fi、开启无线调试并重新配对。仍失败时，将错误信息或日志发给应用维护者。', 'If none of these steps help, restart the device as a last resort, reconnect Wi-Fi, enable wireless debugging and pair again. Send the error or log to the app maintainer if it still fails.') }}</p>
    <button v-if="topic === 'missing-wireless'" type="button" class="primary-button" @click="$emit('navigate', 'manual')">{{ t('改用电脑启动', 'Use a computer instead') }}</button>
    <button v-if="topic === 'feature-failed'" type="button" class="primary-button" @click="$emit('navigate', 'permission-restrictions')">{{ t('按品牌查看设置', 'View brand settings') }}</button>
    <button v-if="inline" type="button" class="return-button" @click="$emit('close')">{{ t('↑ 返回操作步骤', '↑ Back to the step') }}</button>
  </aside>
</template>
