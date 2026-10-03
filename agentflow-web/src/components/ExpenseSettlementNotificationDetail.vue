<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'
import { api, type InboxMessage, type ExpenseSettlementNotificationTarget } from '../api'
import { readExpenseSettlementNotificationTarget } from '../notificationInbox'
import { settlementLabels, settlementFundingLabels, settlementIssue } from '../expenseSettlement'

const props = defineProps<{ message: InboxMessage; scopeKey: string; locked?: boolean }>()
const emit = defineEmits<{ close: []; open: [target: ExpenseSettlementNotificationTarget] }>()
const detail = ref<ExpenseSettlementNotificationTarget | null>(null), loading = ref(false), error = ref('')
let generation = 0, controller: AbortController | null = null
function stop() { generation++; controller?.abort(); controller = null }
/** 每次读取原编号，权限变化、身份切换和超时都清除旧财务事实。 */
async function load() {
  stop(); detail.value = null; error.value = ''; loading.value = false
  if (!props.scopeKey) return
  const current = generation, message = props.message, request = new AbortController(); controller = request; loading.value = true
  let timeout: ReturnType<typeof setTimeout> | undefined
  try {
    const value = await Promise.race([api.expenseSettlementNotificationTarget(message.id, request.signal),
      new Promise<never>((_, reject) => { timeout = setTimeout(() => { request.abort(); reject(new Error('原报销结算读取超时，请重试。')) }, 12_000) })])
    if (current === generation) detail.value = readExpenseSettlementNotificationTarget(value, message)
  } catch (cause) {
    if (current === generation) error.value = [401, 403, 404].includes((cause as { status?: number })?.status ?? 0)
      ? '当前账号已无法读取这条原报销结算记录，请核对当前申请与财务字段权限。'
      : cause instanceof Error ? cause.message : '原报销结算暂时无法读取，请重试。'
  } finally { clearTimeout(timeout); if (current === generation) { loading.value = false; controller = null } }
}
const time = (value: string) => new Date(value).toLocaleString('zh-CN')
watch(() => JSON.stringify([props.scopeKey, props.message.id]), () => { void load() }, { immediate: true, flush: 'sync' })
onUnmounted(stop)
</script>

<template>
  <section class="notice-settlement" aria-labelledby="notice-settlement-title">
    <div class="notice-settlement-heading"><h3 id="notice-settlement-title">消息对应的原报销结算</h3><button type="button" class="quiet" :disabled="locked" @click="emit('close')">收起</button></div>
    <p class="notice-settlement-help">消息对应固定的历史修订。下方分别核对当时事实与当前状态；读取不会核销资源、重试预算或改变已读状态。</p>
    <p v-if="loading" role="status">正在核对当前权限并读取原结算…</p><p v-if="error" class="notice-settlement-error" role="alert">{{ error }}</p>
    <template v-if="detail">
      <p>第 {{ detail.roundNo }} 轮 · 原财务版本 {{ detail.financialVersion }}</p>
      <p class="notice-settlement-help">原登记依据：{{ settlementFundingLabels[detail.funding] }}<br />依据确认时间 {{ time(detail.fundingConfirmedAt) }}，后续资金或凭证变化请核对原申请。</p>
      <div class="notice-settlement-fact"><h4>消息发生时</h4><p><strong>{{ settlementLabels[detail.notice.status] }}</strong> · 结算版本 {{ detail.notice.version }}</p>
        <p v-if="detail.notice.issue" class="notice-settlement-help">{{ settlementIssue(detail.notice.issue) }}</p><p class="notice-settlement-help">{{ time(detail.notice.updatedAt) }}</p></div>
      <div class="notice-settlement-fact"><h4>当前结算状态</h4><p><strong>{{ settlementLabels[detail.current.status] }}</strong> · 结算版本 {{ detail.current.version }}</p>
        <p v-if="detail.current.issue" class="notice-settlement-help">{{ settlementIssue(detail.current.issue) }}</p>
        <p class="notice-settlement-help">{{ detail.current.resourcesConsumed ? '原结算已记录本地资源核销，后续资源调整应分别查看。' : '原结算尚未记录本地资源核销。' }}<br />{{ time(detail.current.updatedAt) }}</p></div>
      <p class="notice-settlement-help">核销完成表示本地资源与预算实际占用完成。付款凭证、归档和后续资金或资源调整仍是独立步骤；当前能否办理须回到原申请重新校验。</p>
    </template>
    <div class="notice-settlement-actions"><button type="button" class="secondary" :disabled="locked || loading" @click="load">重新读取</button><button v-if="detail" type="button" class="secondary" :disabled="locked || loading" @click="emit('open', detail)">查看原申请轮次</button></div>
  </section>
</template>

<style scoped>
.notice-settlement{border:1px solid var(--line);border-radius:10px;background:white;padding:20px;margin-top:20px;min-width:0}.notice-settlement-heading{display:flex;align-items:center;justify-content:space-between;gap:12px}.notice-settlement-heading h3{font-size:16px;margin:0}.notice-settlement-help{color:var(--muted);font-size:12px;line-height:1.8;overflow-wrap:anywhere}.notice-settlement-error{color:var(--red);font-size:13px}.notice-settlement-actions{display:flex;flex-wrap:wrap;gap:10px;margin-top:16px}.notice-settlement-actions button{min-height:40px}@media(max-width:600px){.notice-settlement{padding:14px}}


.notice-settlement-fact{border-top:1px solid var(--line);padding-top:12px;margin-top:16px}.notice-settlement-fact h4{font-size:13px;margin:0}
</style>
