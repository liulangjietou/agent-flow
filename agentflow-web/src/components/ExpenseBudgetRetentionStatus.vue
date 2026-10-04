<script setup lang="ts">
import { budgetIssues, type ExpenseBudgetRetentionView } from '../expenses'
defineProps<{ retention?: ExpenseBudgetRetentionView | null; stopped: boolean }>()
const labels: Record<ExpenseBudgetRetentionView['status'], string> = {
  RETAINED: '已记录预算保留期限，等待到期核对', RECONCILING: '保留期已结束，正在核对原预算操作',
  RELEASE_QUEUED: '预算释放处理中，尚未确认结果', RELEASED: '预算已确认释放',
  SUPERSEDED: '本轮释放计划已失效，以后续办理状态为准', NO_FROZEN_BUDGET: '本轮到期核对时已无待释放的冻结预算',
  RELEASE_REJECTED: '预算释放未通过，需要核对处理'
}
const timeLabel = (value: string) => new Date(value).toLocaleString('zh-CN')
</script>

<template>
  <section v-if="retention" class="budget-retention" aria-label="预算保留期限">
    <strong>第 {{ retention.roundNo }} 轮：{{ labels[retention.status] }}</strong>
    <p>{{ retention.stoppedStatus === 'WITHDRAWN' ? '撤回' : '退回' }}后保留 {{ retention.retentionDays }} 天，每天按 24 小时计算。保留至 <time :datetime="retention.expiresAt">{{ timeLabel(retention.expiresAt) }}</time>。</p>
    <p v-if="retention.status === 'RETAINED'">到期时仍处于本轮退回或撤回状态，才会核对并释放预算。修改草稿不会延后期限。</p>
    <p v-if="retention.status === 'RELEASE_REJECTED'" role="alert">{{ retention.issue ? budgetIssues[retention.issue] ?? '请联系财务核对预算结果' : '请联系财务核对预算结果' }}。</p>
    <p v-if="retention.status === 'RELEASED'">重新提交须重新预检，并等待本轮预算冻结确认。</p>
    <p>此期限仅用于预算冻结。票据、事前额度和借款预留按各自办理状态处理。</p>
  </section>
  <p v-else-if="stopped" class="budget-retention-unconfigured">本轮未配置预算到期释放期限。</p>
</template>

<style scoped>
.budget-retention { margin: 12px 0; padding: 12px 16px; border: 1px solid var(--border, #dce3ed); border-radius: 10px; color: var(--text, #273449); overflow-wrap: anywhere; }
.budget-retention p, .budget-retention-unconfigured { margin: 6px 0 0; color: var(--muted, #64748b); font-size: 13px; line-height: 1.6; }
.budget-retention p[role="alert"] { color: #b42318; }
</style>
