<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'
import { api, type InboxMessage, type ExpenseReturnNotificationTarget } from '../api'
import { readExpenseReturnNotificationTarget } from '../expenseReturnNotification'
import { expenseReturnNoticeLabels, expenseReturnObservationLabels } from '../expenseReturnNotification'
import { expenseReturnCheckLabels } from '../expensePaymentReturn'

const props = defineProps<{ message: InboxMessage; scopeKey: string; locked?: boolean }>()
const emit = defineEmits<{ close: []; open: [target: ExpenseReturnNotificationTarget] }>()
const detail = ref<ExpenseReturnNotificationTarget | null>(null), loading = ref(false), error = ref('')
let generation = 0, controller: AbortController | null = null
function stop() { generation++; controller?.abort(); controller = null }
/** 每次读取原编号，权限变化、身份切换和超时都清除旧财务事实。 */
async function load() {
  stop(); detail.value = null; error.value = ''; loading.value = false
  if (!props.scopeKey) return
  const current = generation, message = props.message, request = new AbortController(); controller = request; loading.value = true
  let timeout: ReturnType<typeof setTimeout> | undefined
  try {
    const value = await Promise.race([api.expenseReturnNotificationTarget(message.id, request.signal),
      new Promise<never>((_, reject) => { timeout = setTimeout(() => { request.abort(); reject(new Error('原报销退回读取超时，请重试。')) }, 12_000) })])
    if (current === generation) detail.value = readExpenseReturnNotificationTarget(value, message)
  } catch (cause) {
    if (current === generation) error.value = [401, 403, 404].includes((cause as { status?: number })?.status ?? 0)
      ? '当前账号已无法读取这条原报销退回记录，请核对当前申请与财务字段权限。'
      : cause instanceof Error ? cause.message : '原报销退回暂时无法读取，请重试。'
  } finally { clearTimeout(timeout); if (current === generation) { loading.value = false; controller = null } }
}
const time = (value: string) => new Date(value).toLocaleString('zh-CN')
watch(() => JSON.stringify([props.scopeKey, props.message.id]), () => { void load() }, { immediate: true, flush: 'sync' })
onUnmounted(stop)
</script>

<template>
  <section class="notice-settlement" aria-labelledby="notice-settlement-title">
    <div class="notice-settlement-heading"><h3 id="notice-settlement-title">消息对应的原报销退回</h3><button type="button" class="quiet" :disabled="locked" @click="emit('close')">收起</button></div>
    <p class="notice-settlement-help">消息固定本次银行查询及其实际登记。后续查询、资金登记和资源调整分别保留，不替换这条原记录。</p>
    <p v-if="loading" role="status">正在核对当前权限并读取原退回记录…</p><p v-if="error" class="notice-settlement-error" role="alert">{{ error }}</p>
    <template v-if="detail">
      <p>第 {{ detail.roundNo }} 轮 · 原查询版本 {{ detail.version }}</p>
      <div class="notice-settlement-fact"><h4>消息发生时</h4><p>{{ expenseReturnNoticeLabels[detail.fact] }}</p></div>
      <div class="notice-settlement-fact"><h4>当前原查询状态</h4><p><strong>{{ expenseReturnCheckLabels[detail.status] }}</strong></p>
        <p v-if="detail.issue" class="notice-settlement-help">{{ detail.issue === 'SOURCE_CHANGED' ? '请在原申请核对当前来源与人员资格。' : '本次未取得可用原件，请在原申请核对后处理。' }}</p>
        <p class="notice-settlement-help">发起 {{ time(detail.requestedAt) }} · 更新 {{ time(detail.updatedAt) }}</p>
        <template v-if="detail.observation"><p>{{ expenseReturnObservationLabels[detail.observation.outcome] }} · 原件版本 {{ detail.observation.revision }}</p>
          <p class="notice-settlement-help">观察 {{ time(detail.observation.observedAt) }} · 当时证据到期 {{ time(detail.observation.validUntil) }}；读取不会续期。</p></template>
        <p v-else>本次没有可展示的银行核对原件。</p>
        <p v-if="detail.registration">本次依据已明确登记 · 退回版本 {{ detail.registration.returnVersion }} · {{ time(detail.registration.registeredAt) }}</p>
        <p v-else>本次查询尚未登记；查询原件不等于资金已经登记。</p>
      </div>
      <p class="notice-settlement-help">实际退回、预算与其他资源调整分别核对。原银行付款和既有结算事实保留，读取不产生财务办理许可。</p>
    </template>
    <div class="notice-settlement-actions"><button type="button" class="secondary" :disabled="locked || loading" @click="load">重新读取</button><button v-if="detail" type="button" class="secondary" :disabled="locked || loading" @click="emit('open', detail)">查看原申请轮次</button></div>
  </section>
</template>

<style scoped>
.notice-settlement{border:1px solid var(--line);border-radius:10px;background:white;padding:20px;margin-top:20px;min-width:0}.notice-settlement-heading{display:flex;align-items:center;justify-content:space-between;gap:12px}.notice-settlement-heading h3{font-size:16px;margin:0}.notice-settlement-help{color:var(--muted);font-size:12px;line-height:1.8;overflow-wrap:anywhere}.notice-settlement-error{color:var(--red);font-size:13px}.notice-settlement-actions{display:flex;flex-wrap:wrap;gap:10px;margin-top:16px}.notice-settlement-actions button{min-height:40px}@media(max-width:600px){.notice-settlement{padding:14px}}


.notice-settlement-fact{border-top:1px solid var(--line);padding-top:12px;margin-top:16px}.notice-settlement-fact h4{font-size:13px;margin:0}
</style>
