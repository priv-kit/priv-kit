<script setup lang="ts">
defineProps<{
  id: string; number: number; title: string; chinese: boolean;
  actions: { device: string; html: string }[];
  checkpoint: string; help: { id: string; title: string }[];
  showDevices?: boolean;
}>();
defineEmits<{ help: [id: string] }>();
</script>

<template>
  <section :id="id" class="guide-step">
    <header class="step-heading"><span class="step-number">{{ number }}</span><h3>{{ title }}</h3></header>
    <ol class="actions">
      <li v-for="(action, index) in actions" :key="index" :class="{ 'command-action': id === 'run-command' && index === 2 }">
        <span v-if="showDevices" class="device" :class="action.device">{{ action.device === 'computer' ? (chinese ? '电脑上' : 'COMPUTER') : (chinese ? '手机上' : 'PHONE') }}</span>
        <span class="action-text" v-html="action.html" />
      </li>
    </ol>
    <slot />
    <div class="checkpoint"><span aria-hidden="true">✓</span><div><strong>{{ chinese ? '完成标志' : 'WHAT TO LOOK FOR' }}</strong><p>{{ checkpoint }}</p></div></div>
    <div v-if="help.length" class="step-help"><span>{{ chinese ? '卡在这里？' : 'Need help here?' }}</span><button v-for="item in help" :key="item.id" type="button" @click="$emit('help', item.id)">{{ item.title }} <span aria-hidden="true">↗</span></button></div>
    <slot name="help" />
  </section>
</template>
