<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import { api } from '../api'
import { ExpenseDetailQuery, budgetIssues, expenseTypes, moneyLabel, policyExceptionLabels, previewReduction, reductionReasons, type ExpenseTaskActivity, type ExpenseReductionPreview, type ExpenseReturnRequest } from '../expenses'
import ExpenseActions from './ExpenseActions.vue'
import ExpenseEditor from './ExpenseEditor.vue'
import VoucherStatus from './VoucherStatus.vue'
import FinancePaymentStatus from './FinancePaymentStatus.vue'
import ExpenseSettlementStatus from './ExpenseSettlementStatus.vue'
import ExpenseArchiveStatus from './ExpenseArchiveStatus.vue'
import ExpenseBudgetRetentionStatus from './ExpenseBudgetRetentionStatus.vue'
import PrecheckExplanationPanel from './PrecheckExplanationPanel.vue'
import ExpenseRiskPanel from './ExpenseRiskPanel.vue'
import ExpenseSplitRoutingPanel from './ExpenseSplitRoutingPanel.vue'
import ExpenseProjectApprovalPanel from './ExpenseProjectApprovalPanel.vue'
import ExpensePriorControlPanel from './ExpensePriorControlPanel.vue'
import ExpenseBudgetReviewPanel from './ExpenseBudgetReviewPanel.vue'
import { budgetApprovalIssues } from '../expenseBudgetReview'
const props = defineProps<{ reportId: string; applicationId: string; scopeKey: string; version?: number; taskId?: string; roundNo?: number; locked?: boolean; applicant?: boolean }>()
const emit = defineEmits<{ changed: []; busy: [value: boolean]; taskActivity: [value: ExpenseTaskActivity]; returnMissing: [value: ExpenseReturnRequest] }>()
const query = reactive(new ExpenseDetailQuery(api.expenseReport, api.expenseWorkflow))
const notice = ref('')
const editing = ref(false)
const reductionPreview = ref<ExpenseReductionPreview | null>(null)
const explanationBusy = ref(false), explanationDirty = ref(false), businessBusy = ref(false)
const riskBusy = ref(false), riskDirty = ref(false)
const explanationLocked = computed(() => explanationBusy.value || explanationDirty.value)
const riskLocked = computed(() => riskBusy.value || riskDirty.value)
const actionsLocked = computed(() => props.locked || explanationLocked.value || riskLocked.value)
function emitActivity(busy: boolean) {
  emit('busy', busy)
  emit('taskActivity', { scopeKey: props.scopeKey, applicationId: props.applicationId, taskId: props.taskId, applicationVersion: props.version, busy })
}
watch(() => explanationLocked.value || riskLocked.value || businessBusy.value || query.loading, emitActivity, { flush: 'sync' })
function load() { reductionPreview.value = null; return query.load(props.scopeKey, props.reportId, props.applicationId, props.taskId, props.roundNo) }
watch(() => [props.scopeKey, props.reportId, props.applicationId, props.version, props.taskId, props.roundNo], () => { editing.value = false; notice.value = ''; void load() }, { immediate: true, flush: 'sync' })
onUnmounted(() => { query.clear(); emitActivity(false) })
async function changed() { notice.value = '操作已记录，正在核对最新费用状态。'; emit('changed'); await load() }
const financial = computed(() => query.detail?.financialRound)
const amounts = computed(() => financial.value ? reductionPreview.value ?? previewReduction(financial.value, []) : null)
const budgetLabels: Record<string, string> = { QUEUED: '等待预算处理', EXECUTING: '预算请求处理中', UNKNOWN: '预算结果待确认', QUERYING: '正在核对预算结果', APPLIED: '预算操作已确认', REJECTED: '预算操作未通过', FAILED: '预算操作失败' }
const taskLabels: Record<string, string> = { BUSINESS: '业务审批', PROJECT_REVIEW: '项目负责人全员会签', PRIOR_REQUEST_REVIEW: '事前额度例外审批', BUDGET_REVIEW: '预算负责人审批', RECEIPT: '原件收单', FINANCE_REVIEW: '财务审核', FINANCE_RECHECK: '财务复核' }
const budgetLabel = computed(() => {
  const budget = query.workflow?.budget
  if (query.detail?.applicationStatus === 'REVOKED') {
    if (budget?.ledgerStatus === 'RELEASED') return '预算已释放'
    if (budget?.ledgerStatus === 'CONSUMED') return '预算已核销，原记录保留'
    if (budget?.ledgerStatus === 'FROZEN') return '预算仍冻结，请核对当前操作'
    return '预算状态以当前记录为准'
  }
  if (budget?.confirmedCurrent) return '当前金额预算已确认'
  if (!budget?.operationStatus) return '尚无预算操作'
  return budgetLabels[budget.operationStatus] ?? '预算状态待核对'
})
const timeLabel = (value: string) => new Date(value).toLocaleString('zh-CN')
</script>

<template>
  <section class="expense-detail" aria-label="费用明细">
    <div class="detail-heading"><h3>{{ roundNo ? `第 ${roundNo} 轮费用` : '费用明细' }}</h3><button type="button" class="quiet" :disabled="editing || query.loading || actionsLocked || businessBusy" @click="notice = ''; load()">刷新费用</button></div>
    <p v-if="notice" class="expense-notice" role="status">{{ notice }}</p>
    <p v-if="query.error" class="expense-error" role="alert">{{ query.error }}</p>
    <slot v-if="query.restricted" name="restricted" />
    <p v-if="query.loading" class="expense-empty" role="status">正在核对费用内容与读取权限…</p>
    <template v-else-if="query.detail">
      <p v-if="query.detail.applicationStatus === 'REVOKED'" class="expense-notice" role="status">此报销审批已撤销，原批准和金额历史保留。已登记的付款、凭证及核销结果仍需按本轮记录核对，预算以当前台账为准。</p>
      <ExpenseEditor v-if="editing && query.detail.editable && roundNo === undefined" :initial="query.detail" :scope-key="scopeKey" :locked="actionsLocked" @busy="businessBusy = $event" @close="editing = false; changed()" @submitted="editing = false; changed()" />
      <template v-else>
      <button v-if="query.detail.editable && roundNo === undefined" type="button" class="primary" :disabled="actionsLocked || businessBusy" @click="editing = true">填写并提交报销</button>
      <div class="expense-context"><strong>{{ query.detail.content.title }}</strong><span>{{ expenseTypes[query.detail.content.type] }} · {{ query.detail.content.lines.length }} 行费用</span></div>
      <div v-if="amounts" class="amount-equation" aria-label="申报核定冲销与应付合计" aria-live="polite">
        <small class="amount-state">{{ reductionPreview ? '核减预览 · 尚未保存' : '本轮已保存结果' }}</small>
        <div><small>原申报合计</small><strong>{{ moneyLabel(amounts.original) }}</strong></div><span aria-hidden="true">→</span>
        <div><small>{{ reductionPreview ? '核减后核定合计' : '当前核定合计' }}</small><strong>{{ moneyLabel(amounts.gross) }}</strong></div><span aria-hidden="true">−</span>
        <div><small>借款冲销</small><strong>{{ moneyLabel(amounts.offset) }}</strong></div><span aria-hidden="true">=</span>
        <div class="payable"><small>应付余额</small><strong>{{ moneyLabel(amounts.payable) }}</strong></div>
      </div>
      <p v-if="financial && amounts" class="financial-caption">可抵扣税额 {{ moneyLabel(amounts.tax) }} · 收款账户 {{ financial.maskedAccount }}<br />{{ reductionPreview ? '当前为未保存预览，保存并确认预算调整后再审批。' : '应付余额为本轮核定结果，付款状态需以实际付款回执为准。' }}</p>
      <div v-if="query.workflow" class="control-strip" aria-label="本轮办理状态">
        <span v-if="query.workflow.task">{{ taskLabels[query.workflow.task.stage] }}</span>
        <span>{{ !query.workflow.paper ? '尚未提交' : !query.workflow.paper.required ? '本轮无需纸质原件' : query.workflow.paper.received ? '本轮原件已签收' : '等待原件签收' }}</span>
        <span :class="{ confirmed: query.workflow.budget.confirmedCurrent }">{{ budgetLabel }}</span>
      </div>
      <p v-if="query.workflow?.paper?.received" class="financial-caption">{{ query.workflow.paper.receivedBy }} 于 {{ timeLabel(query.workflow.paper.receivedAt!) }} 签收</p>
      <p v-if="query.workflow?.paper?.proxyUse" class="financial-caption">签收时代理 {{ query.workflow.paper.proxyUse.principal }} 办理，授权核对时间 {{ timeLabel(query.workflow.paper.proxyUse.authorizedAt) }}</p>
      <p v-if="query.workflow?.budget.issue" class="expense-error">{{ budgetIssues[query.workflow.budget.issue] ?? '预算结果尚未确认' }}，请核对后刷新。</p>
      <p v-if="query.workflow?.task?.approvalUnavailable" class="expense-empty">{{ budgetApprovalIssues[query.workflow.task.approvalUnavailable] ?? '暂不能同意，请刷新核对本轮办理状态。' }}</p>
      <ExpenseBudgetRetentionStatus :retention="query.detail.budgetRetention" :stopped="roundNo === undefined && ['RETURNED', 'WITHDRAWN'].includes(query.detail.applicationStatus)" />
      <p v-if="query.workflow?.task?.reductionUnavailable === 'TASK_DELEGATION_PENDING'" class="expense-empty">当前任务处于委派办理期间，回交后再办理财务操作。</p>
      <p v-if="!query.detail.content.lines.length" class="expense-empty">尚未填写费用明细。</p>
      <article v-for="line in query.detail.content.lines" :key="line.lineNo" class="expense-line">
        <div class="line-heading"><span class="line-number">{{ line.lineNo }}</span><div><h4>{{ line.description }}</h4><p>{{ line.categoryCode }} · {{ line.incurredOn }}<template v-if="line.endedOn"> 至 {{ line.endedOn }}</template> · {{ line.cityCode }}</p></div><strong>{{ moneyLabel(line.claimedGross) }}</strong></div>
        <dl class="line-facts"><div><dt>申报税额</dt><dd>{{ moneyLabel(line.claimedTax) }}</dd></div><div><dt>数量</dt><dd>{{ line.quantity }} {{ ({ ITEM: '项', DAY: '天', NIGHT: '晚', KILOMETER: '公里', PERSON: '人' })[line.unit] }}</dd></div><template v-if="financial"><div><dt>当前核定含税额</dt><dd>{{ moneyLabel(financial.approvedLines.find(value => value.lineNo === line.lineNo)!.gross) }}</dd></div><div><dt>当前可抵扣税额</dt><dd>{{ moneyLabel(financial.approvedLines.find(value => value.lineNo === line.lineNo)!.tax) }}</dd></div></template></dl>
        <p v-if="line.allowance" class="exception-reason">补贴计算依据：{{ moneyLabel(line.allowance.calculation.rule.dailyRate) }} / 天 × {{ line.allowance.calculation.days }} 天 = {{ moneyLabel(line.allowance.calculation.gross) }}；{{ line.allowance.calculation.startsOn }} 至 {{ line.allowance.calculation.endsOn }}，含起止日。制度 v{{ line.allowance.policy.selection.policyVersion }} · 规则 {{ line.allowance.policy.ruleKey }} · 来源 {{ line.allowance.policy.factSourceReference }}。</p>
        <p v-if="line.exceptionReason" class="exception-reason">超标说明：{{ line.exceptionReason }}</p>
        <details class="line-evidence"><summary>查看票据、成本归属与金额依据</summary><div class="evidence-body">
          <p v-if="!line.invoiceIds.length">此行没有关联票据。</p><ul v-else><li v-for="id in line.invoiceIds" :key="id">票据编号 <code>{{ id }}</code></li></ul>
          <p v-if="line.priorRequest">事前申请 {{ line.priorRequest.requestId }} · 第 {{ line.priorRequest.lineNo }} 行</p>
          <h5>申报时成本分摊</h5><ul><li v-for="allocation in line.allocations" :key="`${allocation.costCenter}:${allocation.projectCode}`">{{ allocation.costCenter }}<template v-if="allocation.projectCode"> / {{ allocation.projectCode }}</template> · {{ moneyLabel(allocation.amount) }}</li></ul>
          <template v-if="financial"><h5>当前核定分摊</h5><ul><li v-for="allocation in financial.approvedLines.find(value => value.lineNo === line.lineNo)!.allocations" :key="`${allocation.costCenter}:${allocation.projectCode}`">{{ allocation.costCenter }}<template v-if="allocation.projectCode"> / {{ allocation.projectCode }}</template> · {{ moneyLabel(allocation.amount) }}</li></ul>
            <template v-for="frozen in financial.originalLines.filter(value => value.original.lineNo === line.lineNo)" :key="frozen.original.lineNo">
              <p>提交时折算 {{ moneyLabel(frozen.claimedBase) }} · 可抵扣 {{ moneyLabel(frozen.deductibleTaxBase) }}</p>
              <p>汇率 {{ frozen.assessment.exchangeRate.fromCurrency }} → {{ frozen.assessment.exchangeRate.toCurrency }}：{{ frozen.assessment.exchangeRate.rate }} · {{ frozen.assessment.exchangeRate.rateDate }} · {{ frozen.assessment.exchangeRate.source }}</p>
              <p>制度版本 {{ frozen.assessment.policy.version }} · 额度 {{ moneyLabel(frozen.assessment.policy.allowedGross) }} · {{ frozen.assessment.policy.decision === 'WITHIN_LIMIT' ? '标准内' : frozen.assessment.policy.decision === 'REQUIRES_EXCEPTION' ? '需例外审批' : '不予报销' }}</p>
              <p v-if="frozen.assessment.policy.exceptionReasons?.length">例外原因：{{ frozen.assessment.policy.exceptionReasons.map(reason => policyExceptionLabels[reason] ?? '需核对的制度例外').join('、') }}</p>
              <p v-if="frozen.assessment.policy.managedPolicy">本轮固定类别修订 {{ frozen.assessment.policy.managedPolicy.selection.categoryRevision }} · 制度生效修订 {{ frozen.assessment.policy.managedPolicy.selection.activeRevision }} · 规则 {{ frozen.assessment.policy.managedPolicy.ruleKey }}</p>
              <p>制度 {{ frozen.assessment.policy.policyId }}<template v-if="frozen.assessment.policy.evidenceReference"> · 判定证据 {{ frozen.assessment.policy.evidenceReference }}</template></p>
              <p v-if="frozen.assessment.policy.managedPolicy">匹配事实来源：{{ frozen.assessment.policy.managedPolicy.factSourceReference }}</p>
            </template>
          </template>
        </div></details>
      </article>
      <details v-if="(financial?.advanceOffsets ?? query.detail.content.advanceOffsets).length" class="line-evidence"><summary>借款抵扣明细</summary><ul><li v-for="offset in financial?.advanceOffsets ?? query.detail.content.advanceOffsets" :key="offset.advanceId">{{ offset.advanceId }} · {{ moneyLabel(offset.amount) }}</li></ul></details>
      <section v-if="financial?.adjustments.length" class="adjustment-history" aria-label="财务核减记录"><h4>核减记录</h4><article v-for="adjustment in financial.adjustments" :key="adjustment.id"><p><strong>{{ reductionReasons[adjustment.reasonCode] ?? adjustment.reasonCode }}</strong> · {{ adjustment.adjustedBy }} · {{ timeLabel(adjustment.adjustedAt) }}</p><p class="preserved-text">{{ adjustment.comment }}</p><ul><li v-for="line in adjustment.lineChanges" :key="line.lineNo">第 {{ line.lineNo }} 行：{{ moneyLabel(line.previousGross) }} → {{ moneyLabel(line.approvedGross) }}；税额 {{ moneyLabel(line.previousTax) }} → {{ moneyLabel(line.approvedTax) }}；原因：{{ reductionReasons[line.reasonCode ?? adjustment.reasonCode] ?? line.reasonCode ?? adjustment.reasonCode }}</li><li v-for="offset in adjustment.offsetChanges" :key="offset.advanceId">借款 {{ offset.advanceId }}：{{ moneyLabel(offset.previousAmount) }} → {{ moneyLabel(offset.amount) }}</li></ul></article></section>
      <PrecheckExplanationPanel v-if="applicant && !taskId && roundNo === undefined" :report-id="query.detail.id" :scope-key="scopeKey" :application-version="query.detail.applicationVersion" :financial-version="query.detail.financialVersion" :editable="query.detail.editable" :application-dirty="false" :locked="!!locked || businessBusy || query.loading || riskLocked" @busy="explanationBusy = $event" @dirty="explanationDirty = $event" />
      <ExpenseSplitRoutingPanel v-if="query.detail.financialRound && query.detail.roundNo > 0" :report-id="query.detail.id" :application-id="query.detail.applicationId" :round-no="query.detail.roundNo" :scope-key="scopeKey" :version="query.detail.applicationVersion" :locked="!!locked || businessBusy || query.loading" />
      <ExpenseProjectApprovalPanel v-if="query.detail.financialRound && query.detail.roundNo > 0" :report-id="query.detail.id" :application-id="query.detail.applicationId" :round-no="query.detail.roundNo" :scope-key="scopeKey" :version="query.detail.applicationVersion" :locked="!!locked || businessBusy || query.loading" />
      <ExpensePriorControlPanel v-if="query.detail.financialRound && query.detail.roundNo > 0" :report-id="query.detail.id" :application-id="query.detail.applicationId" :round-no="query.detail.roundNo" :scope-key="scopeKey" :version="query.detail.applicationVersion" :locked="!!locked || businessBusy || query.loading" />
      <ExpenseBudgetReviewPanel v-if="query.detail.financialRound && query.detail.roundNo > 0" :report-id="query.detail.id" :application-id="query.detail.applicationId" :round-no="query.detail.roundNo" :scope-key="scopeKey" :version="query.detail.applicationVersion" :locked="!!locked || businessBusy || query.loading" />
      <ExpenseRiskPanel v-if="query.detail.financialRound && query.detail.roundNo > 0" :report="query.detail" :task-id="roundNo === undefined ? (taskId ?? query.workflow?.task?.taskId) : undefined" :scope-key="scopeKey" :locked="!!locked || businessBusy || query.loading || explanationLocked" @busy="riskBusy = $event" @dirty="riskDirty = $event" />
      <ExpenseActions v-if="query.workflow && roundNo === undefined" :detail="query.detail" :workflow="query.workflow" :scope-key="scopeKey" :locked="actionsLocked" @changed="changed" @busy="businessBusy = $event" @preview="reductionPreview = $event" @return-missing="emit('returnMissing', $event)" @refresh="load" />
      <VoucherStatus v-if="financial && ['APPROVED', 'REVOKED'].includes(query.detail.applicationStatus)" :application-id="query.detail.applicationId" :business-id="query.detail.id" business-type="EXPENSE" :round-no="query.detail.roundNo" :application-version="query.detail.applicationVersion" :business-version="query.detail.financialVersion" :revoked="query.detail.applicationStatus === 'REVOKED'" :scope-key="scopeKey" :locked="actionsLocked" @busy="businessBusy = $event" @changed="load" />
      <FinancePaymentStatus v-if="financial && ['APPROVED', 'REVOKED'].includes(query.detail.applicationStatus)" :application-id="query.detail.applicationId" :business-id="query.detail.id" :round-no="query.detail.roundNo" :application-version="query.detail.applicationVersion" :business-version="query.detail.financialVersion" :revoked="query.detail.applicationStatus === 'REVOKED'" :scope-key="scopeKey" :locked="actionsLocked" @busy="businessBusy = $event" />
      <VoucherStatus v-if="financial && ['APPROVED', 'REVOKED'].includes(query.detail.applicationStatus)" payment :application-id="query.detail.applicationId" :business-id="query.detail.id" business-type="EXPENSE" :round-no="query.detail.roundNo" :application-version="query.detail.applicationVersion" :business-version="query.detail.financialVersion" :revoked="query.detail.applicationStatus === 'REVOKED'" :scope-key="scopeKey" :locked="actionsLocked" @busy="businessBusy = $event" @changed="load" />
      <ExpenseSettlementStatus v-if="financial && ['APPROVED', 'REVOKED'].includes(query.detail.applicationStatus)" :application-id="query.detail.applicationId" :report-id="query.detail.id" :round-no="query.detail.roundNo" :application-version="query.detail.applicationVersion" :financial-version="query.detail.financialVersion" :revoked="query.detail.applicationStatus === 'REVOKED'" :scope-key="scopeKey" :locked="actionsLocked" @busy="businessBusy = $event" @changed="load" />
      <ExpenseArchiveStatus v-if="financial && ['APPROVED', 'REVOKED'].includes(query.detail.applicationStatus)" :application-id="query.detail.applicationId" :report-id="query.detail.id" :round-no="query.detail.roundNo" :application-version="query.detail.applicationVersion" :financial-version="query.detail.financialVersion" :scope-key="scopeKey" :locked="actionsLocked" @busy="businessBusy = $event" />
      </template>
    </template>
  </section>
</template>

<style scoped>
.expense-detail{min-width:0;margin:18px 0}.detail-heading{display:flex;align-items:center;justify-content:space-between;gap:12px}.detail-heading h3{font-size:17px;margin:0}.detail-heading button{font-size:12px}.expense-context{display:grid;gap:7px;margin:18px 0}.expense-context strong{font-size:15px;overflow-wrap:anywhere}.expense-context span,.financial-caption{font-size:11px;color:var(--muted);line-height:1.9}.amount-equation{position:sticky;top:0;z-index:1;display:grid;grid-template-columns:1fr auto 1fr auto 1fr auto 1fr;gap:12px;align-items:center;padding:20px 16px;border-radius:12px;background:var(--soft)}.amount-equation .amount-state{grid-column:1/-1;margin:0}.amount-equation small{display:block;font-size:11px;color:var(--deep);margin-bottom:8px}.amount-equation strong{font:500 13px 'DM Mono',monospace;overflow-wrap:anywhere}.amount-equation>span{color:var(--muted)}.amount-equation .payable{color:var(--deep)}.control-strip{display:flex;gap:7px;flex-wrap:wrap;padding:8px 0}.control-strip span{font-size:11px;padding:6px 9px;background:var(--paper);border:1px solid var(--line);border-radius:6px}.control-strip .confirmed{color:var(--deep);background:var(--soft)}.expense-line{padding:20px 0;border-bottom:1px solid var(--line)}.line-heading{display:flex;align-items:flex-start;gap:10px}.line-number{flex-shrink:0;width:25px;height:25px;border-radius:7px;display:grid;place-items:center;font:11px 'DM Mono',monospace;background:var(--paper);color:var(--muted)}.line-heading h4{margin:3px 0 7px;font-size:13px;white-space:pre-wrap;overflow-wrap:anywhere}.line-heading p{margin:0;color:var(--muted);font-size:11px}.line-heading>strong{font:12px 'DM Mono',monospace;margin:5px 0 0 auto;white-space:nowrap}.line-facts{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:16px;margin:18px 0}.line-facts dt{font-size:10px;color:var(--muted);margin-bottom:5px}.line-facts dd{margin:0;font:12px 'DM Mono',monospace;overflow-wrap:anywhere}.line-evidence{font-size:11px;line-height:1.9;color:var(--muted);margin-top:14px}.line-evidence summary{cursor:pointer;color:var(--deep);padding:5px 0}.line-evidence summary:focus-visible{outline:3px solid var(--teal);outline-offset:2px}.evidence-body{padding:8px 14px;background:var(--paper);border-radius:8px}.line-evidence p,.line-evidence li{overflow-wrap:anywhere}.line-evidence h5{font-size:11px;margin-bottom:5px}.line-evidence ul,.adjustment-history ul{padding-left:18px}.expense-error{font-size:12px;line-height:1.8;color:var(--red);background:#fff0ed;padding:12px;border-radius:8px}.expense-notice{font-size:12px;color:var(--deep)}.expense-empty{font-size:12px;line-height:1.8;color:var(--muted)}.exception-reason{font-size:12px;white-space:pre-wrap;padding-left:12px;border-left:2px solid var(--amber)}.adjustment-history{font-size:11px;line-height:1.9;overflow-wrap:anywhere}.adjustment-history article{padding:8px 12px;background:var(--paper);border-radius:8px;margin-top:10px}.preserved-text{white-space:pre-wrap}@media(max-width:650px){.amount-equation{grid-template-columns:1fr auto;gap:14px}.amount-equation>div{display:flex;justify-content:space-between;gap:10px}.amount-equation .amount-state{grid-column:1/-1;margin:0}.amount-equation small{margin:0}.amount-equation .payable{grid-column:1/-1;border-top:1px solid var(--line);padding-top:14px}.line-heading{flex-wrap:wrap}.line-heading>div{flex:1;min-width:0}.line-heading>strong{width:100%;margin-left:35px}.line-facts{gap:12px}}
</style>
