<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { api } from '../api'
import { expenseError } from '../expenses'
import { precheckIssues, precheckStatuses } from '../expenseDraft'
import { nextAdvanceRound, advanceIssues, advanceOverduePolicyText, usableAdvanceCheck, type AdvanceDetail, type AdvanceCheckOptions, type AdvanceCheckView } from '../advanceRequest'
import AdvanceRequestTerms from './AdvanceRequestTerms.vue'
import { initiatorContextLabel } from '../initiatorContext'
import InitiatorAppointmentPicker from './InitiatorAppointmentPicker.vue'

const props = defineProps<{ detail: AdvanceDetail; scopeKey: string; timeZone: string; locked: boolean }>()
const emit = defineEmits<{ submitted: [applicationId: string]; busy: [value: boolean] }>()
const appointment = ref(''), options = ref<AdvanceCheckOptions | null>(null), result = ref<AdvanceCheckView | null>(null)
const reading = ref(false), saving = ref(false), confirm = ref(false), error = ref(''), requiresRefresh = ref(false)
let epoch = 0, controller: AbortController | null = null, poll: ReturnType<typeof setTimeout> | undefined, expiry: ReturnType<typeof setTimeout> | undefined, pollUntil = 0
const blocked = computed(() => props.locked || saving.value)
const ready = computed(() => usableAdvanceCheck(result.value, props.detail, appointment.value))
const active = computed(() => result.value?.job.status === 'QUEUED' || result.value?.job.status === 'RUNNING')
function stop() { epoch++; controller?.abort(); controller = null; clearTimeout(poll); clearTimeout(expiry); poll = undefined; expiry = undefined }
function currentReady() {
  const usable = usableAdvanceCheck(result.value, props.detail, appointment.value)
  if (!usable) confirm.value = false
  return usable
}
function issue(code: string | null | undefined) { return code ? advanceIssues[code] ?? precheckIssues[code] ?? '请核对本项信息或联系财务后重新检查' : '' }
/** 所有预检读取有界，迟到的旧账号、旧单据及超时结果不能恢复提交入口。 */
async function load(jobId?: string) {
  stop(); const version = epoch, request = new AbortController(); controller = request
  options.value = null; result.value = null; error.value = ''; reading.value = true; confirm.value = false
  const timeout = setTimeout(() => {
    if (version !== epoch) return
    stop(); reading.value = false; error.value = '借款预检状态读取超时，请刷新。'
  }, 12_000)
  try {
    const available = await api.advanceCheckOptions(props.detail.id, request.signal)
    const id = jobId ?? available.latestPrecheckId
    if (version !== epoch) return
    const view = id ? await api.advanceCheck(props.detail.id, id, request.signal) : null
    if (version !== epoch) return
    if (available.applicationVersion !== props.detail.applicationVersion || available.requestVersion !== props.detail.requestVersion) throw new Error('version')
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
  if (!appointment.value) { error.value = '请选择本次任职。'; return }
  const available = options.value
  if (!available.targetDigest) return
  const version = epoch, id = props.detail.id
  saving.value = true; emit('busy', true); confirm.value = false
  try {
    const receipt = await api.queueAdvanceCheck(id, { applicationVersion: props.detail.applicationVersion, requestVersion: props.detail.requestVersion,
      initiatorAppointmentId: appointment.value, targetDigest: available.targetDigest })
    if (version !== epoch) return
    pollUntil = Date.now() + 90_000
    saving.value = false; emit('busy', false); await load(receipt.id)
  } catch (cause) { if (version === epoch) { error.value = advanceIssues[(cause as { code?: string }).code ?? ''] ?? precheckIssues[(cause as { code?: string }).code ?? ''] ?? expenseError(cause); requiresRefresh.value = true } }
  finally { if (version === epoch) { saving.value = false; emit('busy', false) } }
}
function prepare() { if (!blocked.value && !reading.value && !requiresRefresh.value && currentReady()) confirm.value = true }
async function submit() {
  if (!confirm.value || blocked.value || reading.value || requiresRefresh.value || !currentReady() || !result.value) return
  const version = epoch, id = props.detail.id
  saving.value = true; emit('busy', true); error.value = ''
  try {
    const receipt = await api.submitAdvanceRequest(id, { applicationVersion: props.detail.applicationVersion, requestVersion: props.detail.requestVersion, precheckId: result.value.job.id })
    if (version !== epoch) return
    if (receipt.id !== id || receipt.applicationId !== props.detail.applicationId) throw new Error('Receipt mismatch')
    confirm.value = false; requiresRefresh.value = true; saving.value = false; emit('busy', false); emit('submitted', receipt.applicationId)
  } catch (cause) { if (version === epoch) { error.value = advanceIssues[(cause as { code?: string }).code ?? ''] ?? expenseError(cause); requiresRefresh.value = true; confirm.value = false } }
  finally { if (version === epoch) { saving.value = false; emit('busy', false) } }
}
watch(appointment, () => { confirm.value = false })
watch(() => JSON.stringify([props.scopeKey, props.detail.id, props.detail.applicationVersion, props.detail.requestVersion]), () => {
  stop(); appointment.value = ''; options.value = null; result.value = null; saving.value = false; emit('busy', false); requiresRefresh.value = false
  if (props.scopeKey) void load()
}, { immediate: true, flush: 'sync' })
onUnmounted(() => { stop(); emit('busy', false) })
</script>

<template>
  <section class="expense-submission" aria-label="借款预检与提交">
    <div class="submission-heading"><div><p class="eyebrow">CHECK & SUBMIT</p><h3>核对本轮，再提交审批</h3></div><span>第 {{ nextAdvanceRound(detail) }} 轮</span></div>
    <p class="submission-help">预检核对借款法人、金额、归还日与本人收款账户。审批通过后进入结算准备，实际付款成功后才能形成可冲销余额。</p>
    <InitiatorAppointmentPicker v-model="appointment" :scope-key="scopeKey" :disabled="blocked || active" required />
    <p class="submission-help">借款法人时区：{{ timeZone }}。归还日不能早于法人当地提交日期，预检跨日后需重新查询。</p>
    <p v-if="options?.destination" class="submission-help">本次财务查询目标：{{ options.destination }}</p>
    <p v-if="options" class="submission-help" aria-label="逾期借款控制">{{ advanceOverduePolicyText(options.overduePolicy) }}</p>
    <p v-if="options && !options.enabled" class="submission-error">{{ issue(options.unavailableCode) }}</p>
    <p v-if="error" class="submission-error" role="alert">{{ error }}</p>
    <div class="submission-toolbar"><button type="button" class="secondary" :disabled="blocked || reading || active || requiresRefresh || !options?.enabled" @click="queue">开始借款预检</button><button type="button" class="quiet" :disabled="blocked || reading" @click="pollUntil = Date.now() + 90_000; load()">刷新预检结果</button></div>
    <p v-if="reading" class="submission-help" role="status">正在核对最新预检状态…</p>
    <article v-if="result" class="precheck-result" aria-label="本次预检结果">
      <div class="result-heading"><strong>{{ precheckStatuses[result.job.status] }}</strong><small>第 {{ result.job.attempt }} 次检查</small></div>
      <p class="submission-help">{{ initiatorContextLabel(result.initiator) }}</p>
      <p v-if="!result.usable && !active" class="submission-error">{{ issue(result.unavailableCode) }}</p>
      <p v-if="result.failureCode" class="submission-error">{{ issue(result.failureCode) }}</p>
      <template v-if="result.preview"><AdvanceRequestTerms :content="result.preview.content" :financial="result.preview" /><p class="submission-help">有效至 {{ result.validUntil ? new Date(result.validUntil).toLocaleString('zh-CN') : '待核对' }}；正式提交仍会复核有效性。</p></template>
      <p v-if="result.usable && !ready" class="submission-help">请选择与本次检查一致的任职；修改选择后需要重新预检。</p>
    </article>
    <button v-if="!confirm" type="button" class="primary" :disabled="blocked || reading || requiresRefresh || !ready" @click="prepare">核对并提交审批</button>
    <div v-else class="submit-confirmation" role="group" aria-label="确认正式提交借款申请"><p>确认按上方借款用途、金额、归还日、本人账户及任职提交第 {{ nextAdvanceRound(detail) }} 轮审批？提交后当前内容将冻结，修改需按流程退回或撤回。</p><div class="submission-toolbar"><button type="button" class="secondary" :disabled="saving" @click="confirm = false">返回核对</button><button type="button" class="primary" :disabled="blocked || reading || requiresRefresh || !ready" @click="submit">{{ saving ? '正在提交…' : '确认正式提交' }}</button></div></div>
  </section>
</template>

<style scoped>
.expense-submission{border-top:2px solid var(--teal);margin-top:28px;padding-top:24px}.submission-heading,.result-heading{display:flex;align-items:center;justify-content:space-between;gap:16px}.submission-heading h3{font-size:19px;margin:7px 0}.submission-heading>span{font:12px 'DM Mono',monospace;color:var(--deep)}.submission-help{color:var(--muted);font-size:12px;line-height:1.9}.expense-submission>label{display:grid;gap:8px;font-size:12px;max-width:260px}.expense-submission input{padding:10px;border:1px solid var(--line);border-radius:7px;font:inherit;background:#fff;width:100%;min-width:0}.submission-error{font-size:12px;color:var(--red);line-height:1.8}.submission-toolbar{display:flex;gap:12px;flex-wrap:wrap;margin:16px 0}.precheck-result{border:1px solid var(--line);border-radius:12px;padding:20px;background:var(--paper);margin:20px 0}.result-heading strong{font-size:14px}.result-heading small{font-size:11px;color:var(--muted)}.submit-confirmation{border:1px solid var(--teal);border-radius:10px;padding:18px;background:#f1faf7;font-size:12px;line-height:1.9}@media(max-width:600px){.precheck-result{padding:14px}}
</style>
