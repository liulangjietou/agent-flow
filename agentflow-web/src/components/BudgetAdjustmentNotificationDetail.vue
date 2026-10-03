<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'
import { api, type InboxMessage, type BudgetAdjustmentNotificationTarget } from '../api'
import { readBudgetAdjustmentNotificationTarget } from '../budgetAdjustmentNotification'
import { budgetAdjustmentNoticeLabels } from '../budgetAdjustmentNotification'
import { budgetOperationLabels, budgetReviewLabels, budgetResultLabels, budgetIssueLabels } from '../budgetFinance'

const props = defineProps<{ message: InboxMessage; scopeKey: string; locked?: boolean }>()
const emit = defineEmits<{ close: []; open: [target: BudgetAdjustmentNotificationTarget] }>()
const detail = ref<BudgetAdjustmentNotificationTarget | null>(null), loading = ref(false), error = ref('')
let generation = 0, controller: AbortController | null = null
function stop() { generation++; controller?.abort(); controller = null }
/** 每次读取原编号，权限变化、身份切换和超时都清除旧财务事实。 */
async function load() {
  stop(); detail.value = null; error.value = ''; loading.value = false
  if (!props.scopeKey) return
  const current = generation, message = props.message, request = new AbortController(); controller = request; loading.value = true
  let timeout: ReturnType<typeof setTimeout> | undefined
  try {
    const value = await Promise.race([api.budgetAdjustmentNotificationTarget(message.id, request.signal),
      new Promise<never>((_, reject) => { timeout = setTimeout(() => { request.abort(); reject(new Error('原预算调整读取超时，请重试。')) }, 12_000) })])
    if (current === generation) detail.value = readBudgetAdjustmentNotificationTarget(value, message)
  } catch (cause) {
    if (current === generation) error.value = [401, 403, 404].includes((cause as { status?: number })?.status ?? 0)
      ? '当前账号已无法读取这条原预算调整记录，请核对当前申请与财务字段权限。'
      : cause instanceof Error ? cause.message : '原预算调整暂时无法读取，请重试。'
  } finally { clearTimeout(timeout); if (current === generation) { loading.value = false; controller = null } }
}
const time = (value: string) => new Date(value).toLocaleString('zh-CN')
watch(() => JSON.stringify([props.scopeKey, props.message.id]), () => { void load() }, { immediate: true, flush: 'sync' })
onUnmounted(stop)
</script>

<template>
  <section class="notice-settlement" aria-labelledby="budget-adjustment-notice-title">
    <div class="notice-settlement-heading"><h3 id="budget-adjustment-notice-title">消息对应的原预算调整</h3><button type="button" class="quiet" :disabled="locked" @click="emit('close')">收起</button></div>
    <p class="notice-settlement-help">消息固定原台账复核或原执行指令。后续重新读取、重新授权和其他预算调整分别保留。</p>
    <p v-if="loading" role="status">正在核对当前权限并读取原预算记录…</p><p v-if="error" class="notice-settlement-error" role="alert">{{ error }}</p>
    <template v-if="detail">
      <p>第 {{ detail.roundNo }} 轮</p>
      <div class="notice-settlement-fact"><h4>消息发生时</h4><p>{{ budgetAdjustmentNoticeLabels[detail.fact] }}</p></div>
      <div v-if="detail.review" class="notice-settlement-fact"><h4>本次台账复核</h4><p><strong>{{ budgetReviewLabels[detail.review.status] }}</strong> · 版本 {{ detail.review.version }}</p>
        <p>{{ budgetIssueLabels[detail.review.issue] }}</p><p class="notice-settlement-help">发起 {{ time(detail.review.requestedAt) }} · 更新 {{ time(detail.review.updatedAt) }}</p>
        <p>本次复核未形成调整授权，不表示预算已经生效。</p>
      </div>
      <div v-if="detail.operation" class="notice-settlement-fact"><h4>当前原指令状态</h4><p><strong>{{ budgetOperationLabels[detail.operation.status] }}</strong> · 版本 {{ detail.operation.version }}</p>
        <p v-if="detail.operation.failure">{{ budgetIssueLabels[detail.operation.failure] }}</p><p class="notice-settlement-help">建立 {{ time(detail.operation.createdAt) }} · 更新 {{ time(detail.operation.updatedAt) }}</p>
        <div v-if="detail.operation.observation"><h4>原系统观察</h4><p>{{ budgetResultLabels[detail.operation.observation.outcome] }} · 原件版本 {{ detail.operation.observation.revision }}</p>
          <p class="notice-settlement-help">观察 {{ time(detail.operation.observation.observedAt) }}<span v-if="detail.operation.observation.appliedAt"> · 生效 {{ time(detail.operation.observation.appliedAt) }}</span></p>
          <p v-if="detail.operation.observation.rejection">{{ budgetIssueLabels[detail.operation.observation.rejection] }}</p>
        </div><p v-else>本次原指令尚无可展示的外部确认回执。</p>
        <div v-if="detail.operation.conflictingObservation"><h4>另一次冲突观察</h4><p>{{ budgetResultLabels[detail.operation.conflictingObservation.outcome] }} · 原件版本 {{ detail.operation.conflictingObservation.revision }}</p><p class="notice-settlement-help">观察 {{ time(detail.operation.conflictingObservation.observedAt) }}；该结果不覆盖上面的原观察。</p></div>
        <p v-if="detail.retirement">本次已安全结束 · {{ detail.retirement.basis === 'NEVER_SENT' ? '原指令从未发送' : '原系统明确无副作用拒绝' }} · {{ time(detail.retirement.retiredAt) }}</p>
        <p v-else>本次原指令没有安全结束决定；未知或查无不能作为替换依据。</p>
      </div>
      <p class="notice-settlement-help">其他预算操作、资金付款和结算分别核对；此处读取不产生新的办理许可。</p>
    </template>
    <div class="notice-settlement-actions"><button type="button" class="secondary" :disabled="locked || loading" @click="load">重新读取</button><button v-if="detail" type="button" class="secondary" :disabled="locked || loading" @click="emit('open', detail)">查看原申请轮次</button></div>
  </section>
</template>

<style scoped>
.notice-settlement{border:1px solid var(--line);border-radius:10px;background:white;padding:20px;margin-top:20px;min-width:0}.notice-settlement-heading{display:flex;align-items:center;justify-content:space-between;gap:12px}.notice-settlement-heading h3{font-size:16px;margin:0}.notice-settlement-help{color:var(--muted);font-size:12px;line-height:1.8;overflow-wrap:anywhere}.notice-settlement-error{color:var(--red);font-size:13px}.notice-settlement-actions{display:flex;flex-wrap:wrap;gap:10px;margin-top:16px}.notice-settlement-actions button{min-height:40px}@media(max-width:600px){.notice-settlement{padding:14px}}


.notice-settlement-fact{border-top:1px solid var(--line);padding-top:12px;margin-top:16px}.notice-settlement-fact h4{font-size:13px;margin:0}
</style>
