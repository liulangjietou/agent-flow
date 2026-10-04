<script setup lang="ts">
import { onUnmounted, reactive, watch } from 'vue'
import { api } from '../api'
import { ConfigurationRead } from '../expenseConfigurationRead'
import { readBudgetReviewView, budgetReviewLabels, budgetOperationLabels, type BudgetReviewView } from '../expenseBudgetReview'
const props = defineProps<{ reportId: string; applicationId: string; roundNo: number; scopeKey: string; version?: number; locked?: boolean }>()
const query = reactive(new ConfigurationRead<BudgetReviewView>(cause => {
  const failure = cause as { status?: number; code?: string; message?: string }
  return failure.status === 403 || failure.status === 404 ? '当前无法读取这份原轮次预算审批依据。'
    : failure.code === 'RESPONSE_UNREADABLE' ? failure.message! : '预算审批依据读取失败，请刷新。'
}, '预算审批依据读取超时，请刷新。'))
function load() {
  const { reportId, applicationId, roundNo, scopeKey } = props
  if (!scopeKey || !reportId || !applicationId || !Number.isSafeInteger(roundNo) || roundNo < 1) { query.clear(); return }
  return query.load(async signal => readBudgetReviewView(await api.expenseBudgetReview(reportId, roundNo, signal), reportId, applicationId, roundNo))
}
watch(() => [props.scopeKey, props.reportId, props.applicationId, props.roundNo, props.version], () => { void load() }, { immediate: true, flush: 'sync' })
onUnmounted(() => query.clear())
const timeLabel = (value: string) => new Date(value).toLocaleString('zh-CN')
const closureLabels = { WITHDRAWN: '申请人撤回', RETURNED: '审批退回', REJECTED: '审批驳回', CANCELLED: '轮次取消' }
</script>

<template>
  <section class="budget-evidence" aria-label="预算审批依据" :aria-busy="query.loading">
    <div class="budget-heading"><h4>预算审批依据</h4><button type="button" class="quiet" :disabled="locked || query.loading" @click="load">刷新依据</button></div>
    <p v-if="query.loading" role="status">正在读取原轮次预算审批依据…</p>
    <p v-else-if="query.error" role="alert">{{ query.error }}</p>
    <template v-else-if="query.value">
      <p v-if="query.value.status === 'NOT_RECORDED'">这份历史轮次未保存预算审批依据，不能据此判断是否已经通过预算审批或冻结。</p>
      <template v-else-if="query.value.details">
        <p class="budget-state">第 {{ query.value.roundNo }} 轮 · {{ budgetReviewLabels[query.value.details.status] }}</p>
        <p v-if="query.value.details.status === 'REVIEW_REQUIRED'">原预算操作明确要求例外审批。请由当前预算负责人在任务中办理；审批通过后仍需实际预算确认。</p>
        <p v-else-if="query.value.details.status === 'AUTHORIZED'">人工授权已经记录，尚未取得本轮预算确认；财务审核继续等待实际结果。</p>
        <p v-else-if="query.value.details.status === 'CONFIRMED'">已收到本轮预算操作的实际确认。后续核减和释放结果请查看当前费用办理状态。</p>
        <p v-if="query.value.details.closure">{{ closureLabels[query.value.details.closure] }}；保留原轮次事实，下一轮需重新核对预算依据。</p>
        <dl>
          <div><dt>原预算操作</dt><dd>{{ budgetOperationLabels[query.value.details.originalOperationStatus] }}</dd></div>
          <div v-if="query.value.details.authorizedOperationStatus"><dt>审批后预算操作</dt><dd>{{ budgetOperationLabels[query.value.details.authorizedOperationStatus] }}</dd></div>
          <div v-if="query.value.details.policyReference"><dt>原预算政策来源</dt><dd>{{ query.value.details.policyReference }}</dd></div>
          <div v-if="query.value.details.decision"><dt>实际审批人</dt><dd>{{ query.value.details.decision.actorId }} · {{ timeLabel(query.value.details.decision.approvedAt) }}</dd></div>
        </dl>
        <p v-if="query.value.details.automaticPass">原预算已确认，本节点于 {{ timeLabel(query.value.details.automaticPass.passedAt) }} 自动通过，无人工例外审批。</p>
        <details><summary>核对原操作与审计编号</summary>
          <p>原操作 {{ query.value.details.originalOperationId }}</p>
          <p v-if="query.value.details.authorizedOperationId">审批后操作 {{ query.value.details.authorizedOperationId }}</p>
          <p v-if="query.value.details.decision">审批任务 {{ query.value.details.decision.taskId }} · 审计 {{ query.value.details.decision.auditEventId }}</p>
          <p v-if="query.value.details.automaticPass">系统通过任务 {{ query.value.details.automaticPass.taskId }} · 审计 {{ query.value.details.automaticPass.auditEventId }}</p>
        </details>
      </template>
    </template>
  </section>
</template>

<style scoped>
.budget-evidence{border-top:1px solid var(--line);padding-top:16px;margin-top:20px}.budget-heading{display:flex;align-items:center;justify-content:space-between;gap:12px}.budget-heading h4{font-size:14px;margin:0}.budget-evidence p,.budget-evidence dl,.budget-evidence summary{font-size:12px;color:var(--muted);line-height:1.8;overflow-wrap:anywhere}.budget-evidence .budget-state{color:var(--text);font-weight:600}.budget-evidence dl{display:grid;gap:8px}.budget-evidence dl>div{display:flex;gap:12px;flex-wrap:wrap}.budget-evidence dt{min-width:110px}.budget-evidence dd{margin:0}.budget-evidence [role=alert]{color:var(--red)}
</style>
