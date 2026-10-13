<script setup lang="ts">
import { computed } from 'vue';
import content from './content.ts';
const props = defineProps<{ modelValue: string; chinese: boolean; permissions?: boolean }>();
defineEmits<{ 'update:modelValue': [value: string] }>();
const brand = computed(() => content.brands.find(item => item.id === props.modelValue));
</script>

<template>
  <div class="brand-settings">
    <p class="field-label">{{ chinese ? '选择手机品牌' : 'Choose your phone brand' }}</p>
    <div class="brand-choices" role="group" :aria-label="chinese ? '手机品牌' : 'Phone brand'">
      <button v-for="item in content.brands" :key="item.id" type="button" :aria-pressed="modelValue === item.id" @click="$emit('update:modelValue', item.id)">{{ item.name }}</button>
      <button type="button" :aria-pressed="modelValue === 'other'" @click="$emit('update:modelValue', 'other')">{{ chinese ? '其他 / 不确定' : 'Other / Not sure' }}</button>
    </div>
    <div v-if="modelValue" class="brand-result" aria-live="polite">
      <template v-if="!permissions">
        <p>{{ chinese ? '在设置中沿下面的路径查找：' : 'Follow this path in Settings:' }}</p>
        <ol class="setting-path"><li v-for="part in (brand?.path.length ? brand.path : [chinese ? '关于手机' : 'About phone', chinese ? '版本号 / OS 版本' : 'Build number / OS version'])" :key="part">{{ part }}</li></ol>
        <p class="callout">{{ chinese ? '连续点击最后一项 7 次，按提示输入锁屏密码。' : 'Tap the final item 7 times. Enter your screen-lock credential if asked.' }}</p>
        <p class="muted">{{ chinese ? '菜单以手机实际显示为准；vivo 部分版本可直接进入“关于手机”。本教程不适用于 HarmonyOS 5 及以上。' : 'Labels vary by system. On some vivo versions, About phone is directly in Settings. This tutorial does not apply to HarmonyOS 5 or later.' }}</p>
      </template>
      <template v-else>
        <template v-if="brand?.settings.length">
          <ol class="setting-path"><li>{{ chinese ? '设置' : 'Settings' }}</li><li>{{ chinese ? '开发者选项' : 'Developer options' }}</li></ol>
          <p v-if="brand.settings.length > 1" class="muted">{{ chinese ? '按手机上实际显示的名称操作；下面是不同系统版本的名称，不要求全部存在。' : 'Match the label shown on your phone. These names vary by system version; not all will be present.' }}</p>
          <div class="setting-switches"><div v-for="setting in brand.settings" :key="setting.label"><strong>{{ setting.label }}</strong><span>{{ setting.on ? (chinese ? '开启' : 'Turn on') : (chinese ? '关闭' : 'Turn off') }}</span></div></div>
        </template>
        <div v-if="brand?.html" v-html="brand.html" />
        <p v-else-if="!brand?.settings.length">{{ chinese ? '当前教程没有该品牌的专用设置说明。请记录手机型号、系统版本及错误信息，联系应用维护者；没有权限警告也不代表所有功能都能使用。' : 'This guide has no brand-specific setting for this device. Send the model, system version and error to the app maintainer. An absent permission warning does not guarantee that every feature will work.' }}</p>
      </template>
    </div>
  </div>
</template>
