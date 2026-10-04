<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'
import { api, type InboxMessage, type ExpenseAdjustmentNotificationTarget } from '../api'
import { readExpenseAdjustmentNotificationTarget } from '../expenseAdjustmentNotification'
import { expenseAdjustmentNoticeLabels } from '../expenseAdjustmentNotification'
import { adjustmentLabels, adjustmentBudgetLabels, adjustmentPreparationLabels, adjustmentIssue } from '../expenseResourceAdjustment'

const props = defineProps<{ message: InboxMessage; scopeKey: string; locked?: boolean }>()
const emit = defineEmits<{ close: []; open: [target: ExpenseAdjustmentNotificationTarget] }>()
const detail = ref<ExpenseAdjustmentNotificationTarget | null>(null), loading = ref(false), error = ref('')
let generation = 0, controller: AbortController | null = null
function stop() { generation++; controller?.abort(); controller = null }
/** 每次读取原编号，权限变化、身份切换和超时都清除旧财务事实。 */
async function load() {
  stop(); detail.value = null; error.value = ''; loading.value = false
  if (!props.scopeKey) return
  const current = generation, message = props.message, request = new AbortController(); controller = request; loading.value = true
  let timeout: ReturnType<typeof setTimeout> | undefined
  try {
    const value = await Promise.race([api.expenseAdjustmentNotificationTarget(message.id, request.signal),
      new Promise<never>((_, reject) => { timeout = setTimeout(() => { request.abort(); reject(new Error('原报销资源调整读取超时，请重试。')) }, 12_000) })])
    if (current === generation) detail.value = readExpenseAdjustmentNotificationTarget(value, message)
  } catch (cause) {
    if (current === generation) error.value = [401, 403, 404].includes((cause as { status?: number })?.status ?? 0)
      ? '当前账号已无法读取这条原报销资源调整记录，请核对当前申请与财务字段权限。'
      : cause instanceof Error ? cause.message : '原报销资源调整暂时无法读取，请重试。'
  } finally { clearTimeout(timeout); if (current === generation) { loading.value = false; controller = null } }
}
const outcomeLabels: Record<string, string> = { APPLIED: '预算冲正已确认', REJECTED: '预算冲正被拒绝', PENDING: '原预算系统处理中', NOT_FOUND: '原系统暂未查到' }
const time = (value: string) => new Date(value).toLocaleString('zh-CN')
watch(() => JSON.stringify([props.scopeKey, props.message.id]), () => { void load() }, { immediate: true, flush: 'sync' })
onUnmounted(stop)
</script>

<template>
  <section class="notice-settlement" aria-labelledby="expense-adjustment-notice-title">
    <div class="notice-settlement-heading"><h3 id="expense-adjustment-notice-title">消息对应的原报销资源调整</h3><button type="button" class="quiet" :disabled="locked" @click="emit('close')">收起</button></div>
    <p class="notice-settlement-help">本次准备、预算冲正、资源恢复和安全结束分别核对。后续重新授权不会替换这条原记录。</p>
    <p v-if="loading" role="status">正在核对当前权限并读取原调整…</p><p v-if="error" class="notice-settlement-error" role="alert">{{ error }}</p>
    <template v-if="detail">
      <p>第 {{ detail.roundNo }} 轮</p>
      <div class="notice-settlement-fact"><h4>消息发生时</h4><p>{{ expenseAdjustmentNoticeLabels[detail.fact] }}</p></div>
      <div class="notice-settlement-fact"><h4>本次准备</h4><p>{{ adjustmentPreparationLabels[detail.preparation.status] }} · 版本 {{ detail.preparation.version }}</p><p v-if="detail.preparation.issue">{{ adjustmentIssue(detail.preparation.issue) }}</p></div>
      <div v-if="detail.budget" class="notice-settlement-fact"><h4>原预算冲正当前状态</h4><p>{{ adjustmentBudgetLabels[detail.budget.status] }} · 版本 {{ detail.budget.version }}</p>
        <p v-if="detail.budget.failure">{{ adjustmentIssue(detail.budget.failure) }}</p>
        <p v-if="detail.budget.outcome">原系统观察：{{ outcomeLabels[detail.budget.outcome] }}</p>
        <p v-if="detail.budget.conflictingOutcome">另一次冲突观察：{{ outcomeLabels[detail.budget.conflictingOutcome] }}；原观察仍保留。</p>
      </div>
      <div v-if="detail.adjustment" class="notice-settlement-fact"><h4>本地资源当前状态</h4><p>{{ adjustmentLabels[detail.adjustment.status] }} · 版本 {{ detail.adjustment.version }}</p><p v-if="detail.adjustment.issue">{{ adjustmentIssue(detail.adjustment.issue) }}</p>
        <p v-if="detail.completion">实际资源恢复已完成 · {{ time(detail.completion.completedAt) }} · 完成版本 {{ detail.completion.adjustmentVersion }}。之后的预算核对不会重复释放资源。</p><p v-else>尚无实际资源完成证明。</p>
        <p v-if="detail.retirement">本次已安全结束 · {{ time(detail.retirement.retiredAt) }}；原资源未在此次调整中冲回。</p>
      </div>
      <p class="notice-settlement-help">此处只读取原记录；原结算、付款和封存档案保持各自事实，办理须回到原申请核对。</p>
    </template>
    <div class="notice-settlement-actions"><button type="button" class="secondary" :disabled="locked || loading" @click="load">重新读取</button><button v-if="detail" type="button" class="secondary" :disabled="locked || loading" @click="emit('open', detail)">查看原申请轮次</button></div>
  </section>
</template>

<style scoped>
.notice-settlement{border:1px solid var(--line);border-radius:10px;background:white;padding:20px;margin-top:20px;min-width:0}.notice-settlement-heading{display:flex;align-items:center;justify-content:space-between;gap:12px}.notice-settlement-heading h3{font-size:16px;margin:0}.notice-settlement-help{color:var(--muted);font-size:12px;line-height:1.8;overflow-wrap:anywhere}.notice-settlement-error{color:var(--red);font-size:13px}.notice-settlement-actions{display:flex;flex-wrap:wrap;gap:10px;margin-top:16px}.notice-settlement-actions button{min-height:40px}@media(max-width:600px){.notice-settlement{padding:14px}}


.notice-settlement-fact{border-top:1px solid var(--line);padding-top:12px;margin-top:16px}.notice-settlement-fact h4{font-size:13px;margin:0}
</style>
