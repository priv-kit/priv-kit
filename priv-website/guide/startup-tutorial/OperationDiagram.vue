<script setup lang="ts">
defineProps<{ kind: 'pairing' | 'terminal'; chinese: boolean }>();
</script>

<template>
  <figure class="operation-diagram">
    <figcaption>{{ chinese ? '操作示意 · 非真实截图，界面以设备显示为准' : 'Illustration · Not a screenshot; your interface may differ' }}</figcaption>
    <div v-if="kind === 'pairing'" class="diagram-flow">
      <div class="diagram-window"><span class="diagram-label">{{ chinese ? '系统配对窗口' : 'System pairing window' }}</span><strong>{{ chinese ? '六位配对码' : 'Six-digit pairing code' }}</strong><span class="code-dots" aria-hidden="true">● ● ●　● ● ●</span><small>{{ chinese ? '保持这个窗口打开' : 'Keep this window open' }}</small></div>
      <span class="flow-arrow" aria-hidden="true">→</span>
      <div class="diagram-window"><span class="diagram-label">{{ chinese ? '下拉通知栏' : 'Pull down notifications' }}</span><strong>{{ chinese ? '应用的配对通知' : 'App pairing notification' }}</strong><span class="mock-input">{{ chinese ? '在这里输入配对码' : 'Enter the pairing code here' }}</span><small>{{ chinese ? '输入的是配对码，不是锁屏密码' : 'Use the pairing code, not your screen lock' }}</small></div>
    </div>
    <div v-else class="terminal-diagram"><div class="mock-tabs"><span>Tango</span><span class="mock-plus">＋</span></div><div class="mock-menu"><strong>{{ chinese ? '终端 / Terminal' : 'Terminal' }}</strong><span>↓</span><code><del>adb shell </del><span>{{ chinese ? '应用复制的其余命令' : 'rest of the copied command' }}</span></code></div><p>{{ chinese ? '只删除开头的 adb shell，后面的内容保持原样。' : 'Remove only the initial adb shell; leave the rest unchanged.' }}</p></div>
  </figure>
</template>
