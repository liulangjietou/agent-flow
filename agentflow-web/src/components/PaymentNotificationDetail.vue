<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'
import { api, type InboxMessage, type PaymentNotificationTarget } from '../api'
import { readPaymentNotificationTarget } from '../notificationInbox'
import PaymentFacts from './PaymentFacts.vue'

const props = defineProps<{ message: InboxMessage; scopeKey: string; locked?: boolean }>()
const emit = defineEmits<{ close: []; open: [target: PaymentNotificationTarget] }>()
const detail = ref<PaymentNotificationTarget | null>(null), loading = ref(false), error = ref('')
let generation = 0, controller: AbortController | null = null
function stop() { generation++; controller?.abort(); controller = null }
/** 每次显式读取原付款；身份切换、失权或超时立即放弃旧记录。 */
async function load() {
  stop(); detail.value = null; error.value = ''; loading.value = false
  if (!props.scopeKey) return
  const current = generation, message = props.message, request = new AbortController(); controller = request; loading.value = true
  let timeout: ReturnType<typeof setTimeout> | undefined
  try {
    const value = await Promise.race([
      api.paymentNotificationTarget(message.id, request.signal),
      new Promise<never>((_, reject) => { timeout = setTimeout(() => { request.abort(); reject(new Error('原付款读取超时，请重试。')) }, 12_000) })
    ])
    if (current === generation) detail.value = readPaymentNotificationTarget(value, message)
  } catch (cause) {
    if (current === generation) error.value = [401, 403, 404].includes((cause as { status?: number })?.status ?? 0)
      ? '当前账号已无法读取这笔原付款，请刷新消息并核对当前权限。'
      : cause instanceof Error ? cause.message : '原付款暂时无法读取，请重试。'
  } finally {
    clearTimeout(timeout)
    if (current === generation) { loading.value = false; controller = null }
  }
}
watch(() => JSON.stringify([props.scopeKey, props.message.id]), () => { void load() }, { immediate: true, flush: 'sync' })
onUnmounted(stop)
</script>

<template>
  <section class="notice-payment" aria-labelledby="notice-payment-title">
    <div class="notice-payment-heading"><h3 id="notice-payment-title">消息对应的原付款</h3><button type="button" class="quiet" :disabled="locked" @click="emit('close')">收起</button></div>
    <p class="notice-payment-help">消息保留发生时的提示，下方显示原付款当前记录。查询不会重新付款。</p>
    <p v-if="loading" role="status">正在核对当前权限并读取原付款…</p>
    <p v-if="error" class="notice-payment-error" role="alert">{{ error }}</p>
    <template v-if="detail"><PaymentFacts :payment="detail.payment" /><p class="notice-payment-id">原付款编号：{{ detail.paymentId }}</p></template>
    <div class="notice-payment-actions"><button type="button" class="secondary" :disabled="locked || loading" @click="load">重新读取</button><button v-if="detail" type="button" class="secondary" :disabled="locked || loading" @click="emit('open', detail)">{{ detail.view === 'CASHIER_PAYMENT' ? '打开原付款工作区' : '查看原申请轮次' }}</button></div>
  </section>
</template>

<style scoped>
.notice-payment{border:1px solid var(--line);border-radius:10px;background:white;padding:20px;margin-top:20px;min-width:0}.notice-payment-heading{display:flex;align-items:center;justify-content:space-between;gap:12px}.notice-payment-heading h3{font-size:16px;margin:0}.notice-payment-help,.notice-payment-id{color:var(--muted);font-size:12px;line-height:1.8;overflow-wrap:anywhere}.notice-payment-error{color:var(--red);font-size:13px}.notice-payment-actions{display:flex;flex-wrap:wrap;gap:10px;margin-top:16px}.notice-payment-actions button{min-height:40px}@media(max-width:600px){.notice-payment{padding:14px}}
</style>
