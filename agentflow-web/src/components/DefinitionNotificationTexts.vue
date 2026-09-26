<script setup lang="ts">
import { notificationTextEvents, NOTIFICATION_TEXT_LIMIT, type NotificationTexts } from '../notificationTexts'
const props = defineProps<{ modelValue: NotificationTexts; disabled: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: NotificationTexts]; beforeChange: [] }>()
function change(key: keyof NotificationTexts, event: Event) {
  if (props.disabled) return
  emit('beforeChange')
  emit('update:modelValue', { ...props.modelValue, [key]: (event.target as HTMLTextAreaElement).value })
}
</script>
<template>
  <section class="notification-config panel" aria-labelledby="notification-config-title">
    <div class="notification-config-heading"><div><p class="eyebrow">NOTIFICATIONS</p><h3 id="notification-config-title">站内通知文案</h3></div><span>接收人：申请人</span></div>
    <p class="notification-config-help">文案随流程版本发布，用于提交、退回和批准后的消息。留空时保留平台状态提示；只支持纯文本，不自动填入申请字段。</p>
    <div class="notification-text-grid">
      <article v-for="event in notificationTextEvents" :key="event.key">
        <label :for="`notice-${event.key}`">{{ event.label }}<span>{{ modelValue[event.key].length }} / {{ NOTIFICATION_TEXT_LIMIT }}</span></label>
        <textarea :id="`notice-${event.key}`" :value="modelValue[event.key]" :readonly="disabled" :maxlength="NOTIFICATION_TEXT_LIMIT" rows="4" placeholder="留空使用平台状态提示" @input="change(event.key, $event)" />
        <div class="notification-preview" :aria-label="`${event.label}消息预览`"><small>消息预览</small><strong>{{ event.status }}</strong><p v-if="modelValue[event.key].trim()">{{ modelValue[event.key] }}</p><span v-else>显示申请标题、单号与轮次</span></div>
      </article>
    </div>
  </section>
</template>
<style scoped>
.notification-config{padding:24px;margin-top:18px}.notification-config-heading{display:flex;align-items:center;justify-content:space-between;gap:12px}.notification-config-heading h3{font-size:17px;margin:6px 0}.notification-config-heading>span,.notification-config-help{font-size:12px;color:var(--muted);line-height:1.8}.notification-text-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:18px;margin-top:20px}.notification-text-grid label{display:flex;justify-content:space-between;gap:10px;font-size:12px;font-weight:600;margin-bottom:9px}.notification-text-grid label span{color:var(--muted);font-size:11px;font-weight:400}.notification-text-grid textarea{box-sizing:border-box;width:100%;resize:vertical;min-height:100px;border:1px solid var(--line);border-radius:7px;background:#fff;padding:10px;font:inherit;font-size:12px;line-height:1.7;color:var(--ink)}.notification-text-grid textarea[readonly]{background:var(--paper)}.notification-text-grid textarea:focus-visible{outline:3px solid #20a18c60;outline-offset:2px}.notification-preview{border-left:2px solid var(--teal);padding:0 12px;margin-top:15px;overflow-wrap:anywhere}.notification-preview small,.notification-preview>span{display:block;font-size:11px;color:var(--muted)}.notification-preview strong{display:block;font-size:12px;margin-top:7px;color:var(--deep)}.notification-preview p{font-size:12px;line-height:1.8;white-space:pre-wrap;margin:7px 0 0}.notification-preview>span{margin-top:7px}@media(max-width:850px){.notification-text-grid{grid-template-columns:minmax(0,1fr)}.notification-config{padding:18px}.notification-config-heading{align-items:flex-start}}
</style>
