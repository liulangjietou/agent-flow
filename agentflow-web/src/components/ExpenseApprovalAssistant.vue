<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import { api } from '../api'
import { ExpenseDetailQuery, moneyLabel, budgetIssues, reductionReasons, type ExpenseTaskActivity } from '../expenses'
import { budgetApprovalIssues } from '../expenseBudgetReview'
import AssistRunRecords from './AssistRunRecords.vue'
import ExpenseRiskPanel from './ExpenseRiskPanel.vue'
import AgentUsagePanel from './AgentUsagePanel.vue'

const props = defineProps<{ reportId: string; applicationId: string; scopeKey: string; version: number; roundNo: number; taskId: string; locked?: boolean; refreshVersion?: number }>()
const emit = defineEmits<{ taskActivity: [value: ExpenseTaskActivity] }>()
const query = reactive(new ExpenseDetailQuery(api.expenseReport, api.expenseWorkflow))
const riskBusy = ref(false), riskDirty = ref(false)
const financial = computed(() => query.detail?.financialRound)
const missing = computed(() => {
  const workflow = query.workflow, items: string[] = []
  if (!financial.value) items.push('尚未取得本轮冻结的费用金额与制度依据。')
  if (!workflow) items.push('当前审批与预算状态尚未读取。')
  else {
    if (workflow.paper?.required && !workflow.paper.received) items.push('本轮要求纸质原件，尚无签收记录。')
    if (!workflow.budget.confirmedCurrent) items.push('当前金额的预算尚未确认。' + (workflow.budget.issue ? budgetIssues[workflow.budget.issue] ?? '请核对原预算操作。' : ''))
    if (workflow.task?.approvalUnavailable) items.push(budgetApprovalIssues[workflow.task.approvalUnavailable] ?? '当前任务存在待核对的审批条件。')
  }
  return items
})
function activity(busy: boolean) { emit('taskActivity', { scopeKey: props.scopeKey, applicationId: props.applicationId, taskId: props.taskId, applicationVersion: props.version, busy }) }
function load() { return query.load(props.scopeKey, props.reportId, props.applicationId, props.taskId) }
watch(() => [props.scopeKey, props.reportId, props.applicationId, props.version, props.taskId, props.refreshVersion], () => { riskBusy.value = false; riskDirty.value = false; void load() }, { immediate: true, flush: 'sync' })
watch(() => query.loading || riskBusy.value || riskDirty.value, activity, { flush: 'sync' })
onUnmounted(() => { query.clear(); activity(false) })
</script>

<template>
  <section class="reading" aria-label="报销审批阅读材料">
    <header><h3>本轮报销审批材料</h3><button type="button" class="quiet" :disabled="locked || query.loading || riskBusy || riskDirty" @click="load">刷新事实</button></header>
    <p class="help">先核对冻结事实和待补证据，再阅读模型解释。人工复核记录与本次审批决定分别保存。</p>
    <p v-if="query.error" role="alert">{{ query.error }}</p><p v-if="query.loading" role="status">正在核对当前任务的费用读取权限…</p>
    <template v-if="query.detail && !query.loading">
      <section aria-label="已保存的业务事实"><h4>01 · 已保存的业务事实</h4><p>{{ query.detail.businessNo }} · 第 {{ query.detail.roundNo }} 轮 · 财务版本 {{ query.detail.financialVersion }}</p><dl v-if="financial"><div><dt>核定含税额</dt><dd>{{ moneyLabel(financial.approvedGross) }}</dd></div><div><dt>借款抵扣</dt><dd>{{ moneyLabel(financial.offsetTotal) }}</dd></div><div><dt>本轮应付</dt><dd>{{ moneyLabel(financial.payable) }}</dd></div></dl><p v-if="query.workflow?.budget.operationId">原预算操作 {{ query.workflow.budget.operationId }} · {{ query.workflow.budget.confirmedCurrent ? '当前金额已确认' : '待核对' }}</p></section>
      <section aria-label="待补充或核对的证据"><h4>02 · 待补充或核对的证据</h4><ul v-if="missing.length"><li v-for="item in missing" :key="item">{{ item }}</li></ul><p v-else>本页读取的原件、预算与任务条件未返回缺项；仍需核对具体费用真实性与适用制度。</p></section>
      <section aria-label="提交时固定的制度依据"><h4>03 · 提交时固定的制度依据</h4><article v-for="line in financial?.originalLines ?? []" :key="line.original.lineNo"><strong>第 {{ line.original.lineNo }} 行 · {{ line.original.description }}</strong><p>制度 {{ line.assessment.policy.policyId }} / v{{ line.assessment.policy.version }} · {{ ({ WITHIN_LIMIT: '标准内', REQUIRES_EXCEPTION: '需要例外审批', DENIED: '不予报销' })[line.assessment.policy.decision] }}</p><p>申报 {{ moneyLabel(line.assessment.policy.assessedGross) }} · 制度限额 {{ moneyLabel(line.assessment.policy.allowedGross) }}</p><p v-if="line.assessment.policy.evidenceReference">原判定证据 {{ line.assessment.policy.evidenceReference }}</p><p v-if="line.assessment.policy.managedPolicy">规则 {{ line.assessment.policy.managedPolicy.ruleKey }} · 来源 {{ line.assessment.policy.managedPolicy.factSourceReference }}</p></article></section>
      <section aria-label="人工财务意见"><h4>04 · 人工财务意见</h4><p v-if="!financial?.adjustments.length">本轮尚无财务核减记录。其他人工意见请在审批时间线与评论中核对。</p><article v-for="item in financial?.adjustments ?? []" :key="item.id"><strong>{{ item.adjustedBy }} · {{ reductionReasons[item.reasonCode] }}</strong><p>{{ item.comment }}</p><small>{{ new Date(item.adjustedAt).toLocaleString('zh-CN') }}</small></article></section>
    </template>
    <section aria-label="模型解释与人工复核"><h4>05 · 模型解释与人工复核</h4><p class="help">逐项选择可发送的来源，核对模型引用后再保存人工复核意见。模型内容不修改核定金额或审批结论。</p><AssistRunRecords :application-id="applicationId" :scope-key="scopeKey" :version="version" :round-no="roundNo" :task-id="taskId" :locked="!!locked || query.loading || riskBusy || riskDirty" :refresh-version="refreshVersion" /><ExpenseRiskPanel v-if="query.detail?.financialRound && !query.loading" :report="query.detail" :task-id="taskId" :scope-key="scopeKey" :locked="!!locked || query.loading" @busy="riskBusy = $event" @dirty="riskDirty = $event" /></section>
    <AgentUsagePanel :scope-key="scopeKey" :refresh-version="refreshVersion" />
  </section>
</template>

<style scoped>.reading{font-size:12px;line-height:1.8;min-width:0;overflow-wrap:anywhere}.reading header{display:flex;justify-content:space-between;align-items:center;gap:12px}.reading h3{font-size:20px;margin:0}.reading section{border-top:1px solid var(--line);padding:18px 0}.reading h4{font-size:14px;margin:0 0 12px;color:var(--deep)}.reading article{margin:12px 0;padding-left:14px;border-left:2px solid var(--line)}.reading article p{white-space:pre-wrap;margin:4px 0}.reading dl{display:flex;flex-wrap:wrap;gap:24px}.reading dt,.help{color:var(--muted)}.reading dd{margin:4px 0;font-family:'DM Mono',monospace}.reading li{margin:8px 0}</style>
