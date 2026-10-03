<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'
import { api, type InboxMessage, type ExpensePartialAdjustmentNotificationTarget } from '../api'
import { readExpensePartialAdjustmentNotificationTarget } from '../expensePartialAdjustmentNotification'
import { partialNoticeLabels } from '../expensePartialAdjustmentNotification'
import { partialLabels, partialOperationLabels, partialSideLabels } from '../expensePartialAdjustment'
import { adjustmentIssue } from '../expenseResourceAdjustment'

const props = defineProps<{ message: InboxMessage; scopeKey: string; locked?: boolean }>()
const emit = defineEmits<{ close: []; open: [target: ExpensePartialAdjustmentNotificationTarget] }>()
const detail = ref<ExpensePartialAdjustmentNotificationTarget | null>(null), loading = ref(false), error = ref('')
let generation = 0, controller: AbortController | null = null
function stop() { generation++; controller?.abort(); controller = null }
/** 每次读取原编号，权限变化、身份切换和超时都清除旧财务事实。 */
async function load() {
  stop(); detail.value = null; error.value = ''; loading.value = false
  if (!props.scopeKey) return
  const current = generation, message = props.message, request = new AbortController(); controller = request; loading.value = true
  let timeout: ReturnType<typeof setTimeout> | undefined
  try {
    const value = await Promise.race([api.expensePartialAdjustmentNotificationTarget(message.id, request.signal),
      new Promise<never>((_, reject) => { timeout = setTimeout(() => { request.abort(); reject(new Error('原报销部分调整读取超时，请重试。')) }, 12_000) })])
    if (current === generation) detail.value = readExpensePartialAdjustmentNotificationTarget(value, message)
  } catch (cause) {
    if (current === generation) error.value = [401, 403, 404].includes((cause as { status?: number })?.status ?? 0)
      ? '当前账号已无法读取这条原报销部分调整记录，请核对当前申请与财务字段权限。'
      : cause instanceof Error ? cause.message : '原报销部分调整暂时无法读取，请重试。'
  } finally { clearTimeout(timeout); if (current === generation) { loading.value = false; controller = null } }
}
const outcomeLabels: Record<string, string> = { APPLIED: '预算调减已确认', REJECTED: '预算调减被拒绝', POSTED: '挂账调整已过账', FAILED: '挂账调整失败', PENDING: '原系统处理中', NOT_FOUND: '原系统暂未查到' }
const time = (value: string) => new Date(value).toLocaleString('zh-CN')
watch(() => JSON.stringify([props.scopeKey, props.message.id]), () => { void load() }, { immediate: true, flush: 'sync' })
onUnmounted(stop)
</script>

<template>
  <section class="notice-settlement" aria-labelledby="expense-partial-adjustment-notice-title">
    <div class="notice-settlement-heading"><h3 id="expense-partial-adjustment-notice-title">消息对应的原报销部分调整</h3><button type="button" class="quiet" :disabled="locked" @click="emit('close')">收起</button></div>
    <p class="notice-settlement-help">预算与挂账各自保留原操作编号。重新授权后的新操作不会替换此处记录；资源完成和安全结束分别核对。</p>
    <p v-if="loading" role="status">正在核对当前权限并读取原调整…</p><p v-if="error" class="notice-settlement-error" role="alert">{{ error }}</p>
    <template v-if="detail">
      <p>第 {{ detail.roundNo }} 轮</p>
      <div class="notice-settlement-fact"><h4>消息发生时</h4><p>{{ partialNoticeLabels[detail.fact] }}</p></div>
      <div v-if="detail.preparation" class="notice-settlement-fact"><h4>本次准备</h4><p>{{ partialSideLabels[detail.preparation.side] }} · {{ detail.preparation.status === 'UNAVAILABLE' ? '依据读取失败' : '准备已停止' }}</p><p>{{ adjustmentIssue(detail.preparation.issue) }}</p></div>
      <template v-for="side in (['budget', 'accrual'] as const)" :key="side">
        <div v-if="detail[side]" class="notice-settlement-fact"><h4>{{ side === 'budget' ? '原预算操作最后记录' : '原挂账操作最后记录' }}</h4>
          <p>{{ partialOperationLabels[detail[side].status] }} · 版本 {{ detail[side].version }}</p>
          <p v-if="detail[side].failure">{{ adjustmentIssue(detail[side].failure) }}</p>
          <p v-if="detail[side].outcome">原系统观察：{{ outcomeLabels[detail[side].outcome] }}</p>
          <p v-if="detail[side].conflictingOutcome">另一次冲突观察：{{ outcomeLabels[detail[side].conflictingOutcome] }}；原观察仍保留。</p>
        </div>
      </template>
      <div v-if="detail.adjustment" class="notice-settlement-fact"><h4>原操作关联的最后调整记录</h4><p>{{ partialLabels[detail.adjustment.status] }} · 版本 {{ detail.adjustment.version }}</p>
        <p v-if="detail.adjustment.issue">{{ adjustmentIssue(detail.adjustment.issue) }}</p>
        <p v-if="detail.completion">实际资源调整已完成 · {{ time(detail.completion.completedAt) }}。后续核对不会重复执行资源变化。</p><p v-else>尚无实际资源完成证明。</p>
        <p v-if="detail.retirement">本次已安全结束 · {{ time(detail.retirement.retiredAt) }}。</p>
        <p v-if="detail.resolution">原{{ partialSideLabels[detail.resolution.side] }}争议裁决：{{ outcomeLabels[detail.resolution.outcome] }} · {{ time(detail.resolution.resolvedAt) }}。</p>
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
