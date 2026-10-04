<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { api } from '../api'
import ExpenseProjectOwners from './ExpenseProjectOwners.vue'
import ExpensePriorControlFacts from './ExpensePriorControlFacts.vue'
import { expenseError, moneyLabel, type ExpenseDetail } from '../expenses'
import { nextExpenseRound, precheckIssues, precheckStages, precheckStatuses, usablePrecheck, type PrecheckOptions, type PrecheckView } from '../expenseDraft'
import { initiatorContextLabel } from '../initiatorContext'
import InitiatorAppointmentPicker from './InitiatorAppointmentPicker.vue'
import AdvanceOffsetSuggestion from './AdvanceOffsetSuggestion.vue'
import type { AdvanceOffsetSuggestion as OffsetSuggestion } from '../advanceOffsetSuggestion'

const props = defineProps<{ detail: ExpenseDetail; scopeKey: string; timeZone: string; locked: boolean }>()
const emit = defineEmits<{ submitted: [applicationId: string]; busy: [value: boolean]; offsets: [suggestion: OffsetSuggestion]; checked: [] }>()
const appointment = ref(''), accountingDate = ref(''), options = ref<PrecheckOptions | null>(null), result = ref<PrecheckView | null>(null)
const reading = ref(false), saving = ref(false), confirm = ref(false), error = ref(''), requiresRefresh = ref(false)
let epoch = 0, controller: AbortController | null = null, poll: ReturnType<typeof setTimeout> | undefined, expiry: ReturnType<typeof setTimeout> | undefined, pollUntil = 0
const blocked = computed(() => props.locked || saving.value)
const ready = computed(() => usablePrecheck(result.value, props.detail, appointment.value, accountingDate.value))
const active = computed(() => result.value?.job.status === 'QUEUED' || result.value?.job.status === 'RUNNING')
function stop() { epoch++; controller?.abort(); controller = null; clearTimeout(poll); clearTimeout(expiry); poll = undefined; expiry = undefined }
function currentReady() {
  const usable = usablePrecheck(result.value, props.detail, appointment.value, accountingDate.value)
  if (!usable) confirm.value = false
  return usable
}
function issue(code: string | null) { return code ? precheckIssues[code] ?? '请核对本项信息或联系财务后重新检查' : '' }
/** 所有预检读取有界，迟到的旧账号、旧单据及超时结果不能恢复提交入口。 */
async function load(jobId?: string) {
  stop(); const version = epoch, request = new AbortController(); controller = request
  options.value = null; result.value = null; error.value = ''; reading.value = true; confirm.value = false
  const timeout = setTimeout(() => {
    if (version !== epoch) return
    stop(); reading.value = false; error.value = '费用预检状态读取超时，请刷新。'
  }, 12_000)
  try {
    const available = await api.expensePrecheckOptions(props.detail.id, request.signal)
    const id = jobId ?? available.latestPrecheckId
    if (version !== epoch) return
    const view = id ? await api.expensePrecheck(props.detail.id, id, request.signal) : null
    if (version !== epoch) return
    if (available.applicationVersion !== props.detail.applicationVersion || available.financialVersion !== props.detail.financialVersion) throw new Error('version')
    if (view && view.job.id !== id) throw new Error('binding')
    options.value = available; result.value = view; requiresRefresh.value = false
    if (view?.usable && view.validUntil) expiry = setTimeout(() => {
      if (version !== epoch || !result.value) return
      result.value = { ...result.value, usable: false, unavailableCode: 'FACTS_EXPIRED' }; confirm.value = false
    }, Math.max(0, Math.min(Date.parse(view.validUntil) - Date.now(), 2_147_483_647)))
    if (active.value && id && Date.now() < pollUntil) poll = setTimeout(() => void load(id), 2_000)
  } catch (cause) { if (version === epoch) error.value = (cause as { status?: number }).status ? expenseError(cause) : '单据或预检状态已变化，请重新打开单据后核对。' }
  finally { clearTimeout(timeout); if (version === epoch) { reading.value = false; controller = null } }
}
async function queue() {
  if (blocked.value || reading.value || active.value || requiresRefresh.value || !options.value?.enabled) return
  error.value = ''
  if (!appointment.value || !accountingDate.value) { error.value = '请选择本次任职和会计日期。'; return }
  const available = options.value
  if (!available.targetDigest) return
  const version = epoch, id = props.detail.id
  saving.value = true; emit('busy', true); confirm.value = false
  try {
    const receipt = await api.queueExpensePrecheck(id, { applicationVersion: props.detail.applicationVersion, financialVersion: props.detail.financialVersion,
      initiatorAppointmentId: appointment.value, accountingDate: accountingDate.value, targetDigest: available.targetDigest })
    if (version !== epoch) return
    pollUntil = Date.now() + 90_000
    saving.value = false; emit('busy', false); await load(receipt.id)
  } catch (cause) { if (version === epoch) { error.value = precheckIssues[(cause as { code?: string }).code ?? ''] ?? expenseError(cause); requiresRefresh.value = true } }
  finally { if (version === epoch) { saving.value = false; emit('busy', false) } }
}
function prepare() { if (!blocked.value && !reading.value && !requiresRefresh.value && currentReady()) confirm.value = true }
async function submit() {
  if (!confirm.value || blocked.value || reading.value || requiresRefresh.value || !currentReady() || !result.value) return
  const version = epoch, id = props.detail.id
  saving.value = true; emit('busy', true); error.value = ''
  try {
    const receipt = await api.submitExpense(id, { applicationVersion: props.detail.applicationVersion, financialVersion: props.detail.financialVersion, precheckId: result.value.job.id })
    if (version !== epoch) return
    if (receipt.reportId !== id || receipt.applicationId !== props.detail.applicationId) throw new Error('Receipt mismatch')
    confirm.value = false; requiresRefresh.value = true; saving.value = false; emit('busy', false); emit('submitted', receipt.applicationId)
  } catch (cause) { if (version === epoch) { error.value = expenseError(cause); requiresRefresh.value = true; confirm.value = false } }
  finally { if (version === epoch) { saving.value = false; emit('busy', false) } }
}
watch(() => [appointment.value, accountingDate.value], () => { confirm.value = false })
watch(() => result.value ? `${result.value.job.id}:${result.value.job.status}` : '', (value, old) => { if (value && value !== old) emit('checked') })
watch(() => [props.scopeKey, props.detail.id, props.detail.applicationVersion, props.detail.financialVersion], () => {
  stop(); appointment.value = ''; accountingDate.value = ''; options.value = null; result.value = null; saving.value = false; emit('busy', false); requiresRefresh.value = false
  if (props.scopeKey) void load()
}, { immediate: true, flush: 'sync' })
onUnmounted(() => { stop(); emit('busy', false) })
</script>

<template>
  <section class="expense-submission" aria-label="费用预检与提交">
    <div class="submission-heading"><div><p class="eyebrow">CHECK & SUBMIT</p><h3>核对本轮，再提交审批</h3></div><span>第 {{ nextExpenseRound(detail) }} 轮</span></div>
    <p class="submission-help">预检读取实际账户、制度、汇率、原件及预算。检查完成后，仍需明确提交才会预留资源并创建审批待办。</p>
    <InitiatorAppointmentPicker v-model="appointment" :scope-key="scopeKey" :disabled="blocked || active" required />
    <label>会计日期<input v-model="accountingDate" type="date" :disabled="blocked || active" required /></label>
    <p class="submission-help">费用法人时区：{{ timeZone }}。会计日期用于预算归属；汇率日期由服务端按实际提交日核对。</p>
    <p v-if="options?.destination" class="submission-help">本次财务查询目标：{{ options.destination }}</p>
    <p v-if="options && !options.enabled" class="submission-error">{{ issue(options.unavailableCode) }}</p>
    <p v-if="error" class="submission-error" role="alert">{{ error }}</p>
    <div class="submission-toolbar"><button type="button" class="secondary" :disabled="blocked || reading || active || requiresRefresh || !options?.enabled" @click="queue">开始费用预检</button><button type="button" class="quiet" :disabled="blocked || reading" @click="pollUntil = Date.now() + 90_000; load()">刷新预检结果</button></div>
    <p v-if="reading" class="submission-help" role="status">正在核对最新预检状态…</p>
    <article v-if="result" class="precheck-result" aria-label="本次预检结果">
      <div class="result-heading"><strong>{{ precheckStatuses[result.job.status] }}</strong><small>第 {{ result.job.attempt }} 次检查</small></div>
      <p class="submission-help">{{ initiatorContextLabel(result.initiator) }} · 会计日期 {{ result.accountingDate }}</p>
      <p v-if="!result.usable && !active" class="submission-error">{{ issue(result.unavailableCode) }}</p>
      <ul v-if="result.findings.length"><li v-for="(finding, index) in result.findings" :key="index">{{ finding.lineNo ? `第 ${finding.lineNo} 行 · ` : '' }}{{ precheckStages[finding.stage] ?? '检查结果' }}：{{ issue(finding.code) }}<small>核对码 {{ finding.code }}</small></li></ul>
      <p v-if="result.budgetExceptionPolicy" class="submission-help">预算政策 {{ result.budgetExceptionPolicy.reference }} 允许申请例外。此时预算尚未冻结；正式提交后如需例外，将由独立预算负责人审批，批准后仍须实际预算确认。</p>
      <template v-if="result.preview"><div class="preview-amounts"><div><small>核定含税额</small><strong>{{ moneyLabel(result.preview.approvedGross) }}</strong></div><div><small>借款抵扣</small><strong>{{ moneyLabel(result.preview.offsetTotal) }}</strong></div><div><small>应付余额</small><strong>{{ moneyLabel(result.preview.payable) }}</strong></div></div><p class="submission-help">收款账户 {{ result.preview.maskedAccount }} · 汇率日期 {{ result.rateDate }}<br />有效至 {{ result.validUntil ? new Date(result.validUntil).toLocaleString('zh-CN') : '待核对' }}；正式提交仍会复核有效性。</p></template>
      <details v-if="result.projectOwners" class="policy-sources"><summary>核对本次项目负责人</summary><p>正式提交时核对当前资格并固定责任；申请人兼任负责人时按本次任职上溯直接主管。</p><ExpenseProjectOwners :source="result.projectOwners" /></details>
      <details v-if="result.priorControls" class="policy-sources"><summary>核对事前额度累计依据</summary><p>预检不占用额度，正式提交时将再次核对；同一批准行的全部报销共享累计阈值。</p><ExpensePriorControlFacts :assessments="result.priorControls" /></details>
      <details v-if="result.preview" class="policy-sources"><summary>核对各行制度版本与来源</summary><ul><li v-for="line in result.preview.originalLines" :key="line.original.lineNo">第 {{ line.original.lineNo }} 行 · 制度 {{ line.assessment.policy.policyId }} · v{{ line.assessment.policy.version }}<template v-if="line.assessment.policy.managedPolicy"><br />类别修订 {{ line.assessment.policy.managedPolicy.selection.categoryRevision }} · 生效修订 {{ line.assessment.policy.managedPolicy.selection.activeRevision }} · 规则 {{ line.assessment.policy.managedPolicy.ruleKey }}<br />匹配事实来源：{{ line.assessment.policy.managedPolicy.factSourceReference }}</template><template v-if="line.assessment.policy.evidenceReference"><br />判定证据 {{ line.assessment.policy.evidenceReference }}</template></li></ul></details>
      <p v-if="result.usable && !ready" class="submission-help">请选择与本次检查一致的任职和会计日期；修改选择后需要重新预检。</p>
    </article>
    <AdvanceOffsetSuggestion v-if="ready && result && !requiresRefresh" :detail="detail" :precheck="result" :scope-key="scopeKey" :locked="blocked || reading || confirm" @apply="emit('offsets', $event)" />
    <button v-if="!confirm" type="button" class="primary" :disabled="blocked || reading || requiresRefresh || !ready" @click="prepare">核对并提交审批</button>
    <div v-else class="submit-confirmation" role="group" aria-label="确认正式提交报销"><p>确认按上方费用、任职、会计日期和收款账户提交第 {{ nextExpenseRound(detail) }} 轮审批？提交后当前内容将冻结，修改需按流程退回或撤回。</p><div class="submission-toolbar"><button type="button" class="secondary" :disabled="saving" @click="confirm = false">返回核对</button><button type="button" class="primary" :disabled="blocked || reading || requiresRefresh || !ready" @click="submit">{{ saving ? '正在提交…' : '确认正式提交' }}</button></div></div>
  </section>
</template>

<style scoped>
.policy-sources{font-size:12px;line-height:1.8;overflow-wrap:anywhere}.policy-sources summary{cursor:pointer;color:var(--deep)}.policy-sources summary:focus-visible{outline:3px solid var(--teal);outline-offset:2px}.policy-sources li+li{margin-top:10px}
.expense-submission{border-top:2px solid var(--teal);margin-top:28px;padding-top:24px}.submission-heading,.result-heading{display:flex;align-items:center;justify-content:space-between;gap:16px}.submission-heading h3{font-size:19px;margin:7px 0}.submission-heading>span{font:12px 'DM Mono',monospace;color:var(--deep)}.submission-help{color:var(--muted);font-size:12px;line-height:1.9}.expense-submission>label{display:grid;gap:8px;font-size:12px;max-width:260px}.expense-submission input{padding:10px;border:1px solid var(--line);border-radius:7px;font:inherit;background:#fff;width:100%;min-width:0}.submission-error{font-size:12px;color:var(--red);line-height:1.8}.submission-toolbar{display:flex;gap:12px;flex-wrap:wrap;margin:16px 0}.precheck-result{border:1px solid var(--line);border-radius:12px;padding:20px;background:var(--paper);margin:20px 0}.result-heading strong{font-size:14px}.result-heading small{font-size:11px;color:var(--muted)}.precheck-result ul{padding-left:18px;font-size:12px;line-height:1.8}.precheck-result li small{display:block;color:var(--muted);font-size:10px}.preview-amounts{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:15px;margin-top:18px}.preview-amounts small{display:block;font-size:11px;color:var(--muted);margin-bottom:9px}.preview-amounts strong{font:14px 'DM Mono',monospace;overflow-wrap:anywhere;line-height:1.8}.submit-confirmation{border:1px solid var(--teal);border-radius:10px;padding:18px;background:#f1faf7;font-size:12px;line-height:1.9}@media(max-width:600px){.preview-amounts{grid-template-columns:1fr}.precheck-result{padding:14px}}
</style>
