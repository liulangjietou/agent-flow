<script setup lang="ts">
import { ref } from 'vue'
import WebhookDeliveries from './WebhookDeliveries.vue'
import PaymentCallbackInbox from './PaymentCallbackInbox.vue'
const props = defineProps<{ scopeKey: string; refreshVersion: number; locked: boolean }>()
const emit = defineEmits<{ open: [id: string] }>()
const tab = ref<'outbound' | 'callbacks'>('outbound')
</script>
<template>
  <section class="integration-workspace">
    <nav class="integration-tabs" aria-label="集成消息类型"><button type="button" :aria-pressed="tab === 'outbound'" :disabled="locked" @click="tab = 'outbound'">审批事件投递</button><button type="button" :aria-pressed="tab === 'callbacks'" :disabled="locked" @click="tab = 'callbacks'">支付回调接收</button></nav>
    <WebhookDeliveries v-if="tab === 'outbound'" v-bind="props" @open="emit('open', $event)" />
    <PaymentCallbackInbox v-else v-bind="props" />
  </section>
</template>
<style scoped>
.integration-workspace{min-width:0}.integration-tabs{display:flex;gap:8px;flex-wrap:wrap;border-bottom:1px solid var(--line);margin-bottom:26px;padding-bottom:12px}.integration-tabs button{border:1px solid transparent;border-radius:7px;background:transparent;padding:10px 16px;font:inherit;font-size:12px;color:var(--muted);cursor:pointer}.integration-tabs button[aria-pressed=true]{background:var(--soft);border-color:var(--line);color:var(--deep)}.integration-tabs button:disabled{opacity:.6;cursor:default}.integration-tabs button:focus-visible{outline:2px solid var(--deep);outline-offset:2px}
</style>
