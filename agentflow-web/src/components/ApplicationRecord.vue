<script setup lang="ts">
import SubmissionRiskStatus from './SubmissionRiskStatus.vue'
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import RoundDiagram from './RoundDiagram.vue'
import SubprocessRelations from './SubprocessRelations.vue'
import type { RelatedRound } from '../subprocessRelations'
import InstanceControlPanel from './InstanceControlPanel.vue'
import type { InstanceControlView } from '../instanceControl'
import ExpenseDetail from './ExpenseDetail.vue'
import ExpensePlanDetail from './ExpensePlanDetail.vue'
import AdvanceRequestDetail from './AdvanceRequestDetail.vue'
import ProcurementPaymentDetail from './ProcurementPaymentDetail.vue'
import BudgetAdjustmentDetail from './BudgetAdjustmentDetail.vue'
import RoundComparison from './RoundComparison.vue'
import ApplicationHistory from './ApplicationHistory.vue'
import ServiceTaskRuntimePanel from './ServiceTaskRuntimePanel.vue'
import SignaturePanel from './SignaturePanel.vue'
import ApplicationComments from './ApplicationComments.vue'
import AssistRunRecords from './AssistRunRecords.vue'
import DraftAssistPanel from './DraftAssistPanel.vue'
import RequestRecovery from './RequestRecovery.vue'
import FormFields from './FormFields.vue'
import InitiatorAppointmentPicker from './InitiatorAppointmentPicker.vue'
import InitiatorRequirementNotice from './InitiatorRequirementNotice.vue'
import { InitiatorRequirements } from '../initiatorRequirements'
import { initiatorContextLabel } from '../initiatorContext'
import { validatePayload, type FieldErrors } from '../formSchema'
import { api, type ApiError, type Application, type SubmissionRound } from '../api'
import type { PendingWrite } from '../pendingWrites.js'

const props = defineProps<{ applicationId: string; initialRoundNo?: number | null; userId: string; scopeKey: string; commentRefreshVersion: number; pendingWrites: PendingWrite[]; recoveryError: string }>()
const emit = defineEmits<{ close: []; changed: []; commentPosted: []; recover: [id: string]; openRelated: [target: RelatedRound] }>()
const writesBlocked = computed(() => props.pendingWrites.length > 0)
const dialog = ref<HTMLElement | null>(null)
const application = ref<Application | null>(null)
const runtimeState = ref<InstanceControlView | null>(null)
const runtimeBusy = ref(false)
const runtimeBlocked = computed(() => application.value?.status === 'IN_APPROVAL' && (!runtimeState.value
  || runtimeState.value.applicationId !== application.value.id || runtimeState.value.roundNo !== application.value.roundNo
  || runtimeState.value.applicationVersion !== application.value.version || runtimeState.value.state !== 'RUNNING'))
const businessLocked = computed(() => saving.value || writesBlocked.value || runtimeBusy.value || runtimeBlocked.value)
const rounds = ref<SubmissionRound[]>([])
const historyTab = ref<'rounds' | 'relations' | 'compare' | 'diagram' | 'timeline' | 'audit' | 'comments' | 'assist' | 'services' | 'signatures'>('rounds')
const title = ref('')
const initiatorAppointmentId = ref('')
const initiatorRequirements = reactive(new InitiatorRequirements(api.definitionInitiatorRequirements, api.applicationInitiatorRequirements))
const amount = ref('')
const description = ref('')
const payload = ref<Record<string, unknown>>({})
const fieldErrors = ref<FieldErrors>({})
const loading = ref(true)
const expenseBusy = ref(false)
const signatureBusy = ref(false), signatureDirty = ref(false)
const draftAssistBusy = ref(false), draftAssistDirty = ref(false)
const selectedExpenseRound = ref<number | null>(null)
const advanceRequestId = computed(() => application.value?.businessReference?.type === 'ADVANCE_REQUEST' ? application.value.businessReference.id : null)
const procurementPaymentId = computed(() => application.value?.businessReference?.type === 'PROCUREMENT_PAYMENT' ? application.value.businessReference.id : null)
const budgetAdjustmentId = computed(() => application.value?.businessReference?.type === 'BUDGET_ADJUSTMENT' ? application.value.businessReference.id : null)
const planId = computed(() => application.value?.businessReference?.type === 'EXPENSE_PLAN' ? application.value.businessReference.id : null)
const expenseId = computed(() => application.value?.businessReference?.type === 'EXPENSE' ? application.value.businessReference.id : null)
const saving = ref(false)
const uploading = ref(false)
const error = ref('')
const notice = ref('')
const initialFields = ref('')
const withdrawalOpen = ref(false)
const withdrawalComment = ref('')
const withdrawalInput = ref<HTMLTextAreaElement | null>(null)
const withdrawalTrigger = ref<HTMLButtonElement | null>(null)
const cancellationOpen = ref(false)
const cancellationComment = ref('')
const cancellationInput = ref<HTMLTextAreaElement | null>(null)
const cancellationTrigger = ref<HTMLButtonElement | null>(null)
let initialRoundFocused = false
let returnFocus: HTMLElement | null = null
const statusLabels: Record<string, string> = { DRAFT: '草稿', IN_APPROVAL: '审批中', RETURNED: '已退回', WITHDRAWN: '已撤回', REJECTED: '已驳回', APPROVED: '已批准', CANCELLED: '已作废', REVOKED: '已撤销' }
const canEdit = computed(() => !application.value?.businessReference && application.value?.createdBy === props.userId && ['DRAFT', 'RETURNED', 'WITHDRAWN'].includes(application.value.status))
const canWithdraw = computed(() => !application.value?.businessReference && application.value?.createdBy === props.userId && application.value.status === 'IN_APPROVAL' && !runtimeBlocked.value && !runtimeBusy.value)
const conclusionLabel = computed(() => application.value?.status === 'WITHDRAWN' ? '撤回说明' : '退回原因')
const dirty = computed(() => application.value !== null && fieldsSnapshot() !== initialFields.value)
const relatedNavigationLocked = computed(() => loading.value || saving.value || uploading.value || expenseBusy.value || signatureBusy.value || signatureDirty.value || draftAssistBusy.value || draftAssistDirty.value || runtimeBusy.value || writesBlocked.value || dirty.value || withdrawalOpen.value || cancellationOpen.value)
/** 关联链接不丢弃未保存内容，也不绕过未知写入恢复。 */
function openRelated(target: RelatedRound) { if (!relatedNavigationLocked.value) emit('openRelated', target) }
const currentRound = computed(() => rounds.value.find(round => round.roundNo === application.value?.roundNo))
const conclusionReason = computed(() => {
  if (currentRound.value?.reason) return currentRound.value.reason
  if (application.value?.status === 'WITHDRAWN') return currentRound.value?.status === 'WITHDRAWN' ? '未填写撤回说明。' : '本轮暂无可用的撤回说明记录。'
  return '本轮暂无可用的退回原因记录，请联系审批人核实。'
})
const extraFields = computed(() => Object.entries(application.value?.payload ?? {}).filter(([key]) => !['amount', 'description'].includes(key)))
const stateLabel = (status: string) => statusLabels[status] ?? status
const valueLabel = (value: unknown) => typeof value === 'object' ? JSON.stringify(value, null, 2) : String(value ?? '—')
const fieldLabel = (key: string) => key === 'amount' ? '申请金额' : key === 'description' ? '申请说明' : key
const timeLabel = (value: string) => new Date(value).toLocaleString('zh-CN')

function fieldsSnapshot() { return JSON.stringify(application.value?.formSchema ? [title.value, payload.value] : [title.value, amount.value, description.value]) }
function showError(cause: unknown) {
  const failure = cause as ApiError
  fieldErrors.value = failure.details?.fieldErrors ?? {}
  error.value = failure.code === 'CONCURRENCY_CONFLICT'
    ? '申请已被更新。请重新加载最新内容，核对后再操作。'
    : failure.message ?? '请求未完成，请重试。'
}
function setApplication(value: Application) {
  application.value = value
  title.value = value.title
  payload.value = { ...value.payload }; fieldErrors.value = {}
  amount.value = value.payload.amount == null ? '' : String(value.payload.amount)
  description.value = value.payload.description == null ? '' : String(value.payload.description)
  initialFields.value = fieldsSnapshot()
  withdrawalOpen.value = false; withdrawalComment.value = ''
  cancellationOpen.value = false; cancellationComment.value = ''
}
async function load() {
  loading.value = true; error.value = ''; notice.value = ''; runtimeState.value = null
  try {
    const [value, history] = await Promise.all([api.application(props.applicationId), api.applicationRounds(props.applicationId)])
    setApplication(value); rounds.value = [...history].sort((a, b) => b.roundNo - a.roundNo)
  } catch (cause) { showError(cause) }
  finally { loading.value = false }
  // loading 结束后才会挂载历史节点，提前定位会永久错过原轮次。
  if (!error.value && !initialRoundFocused && props.initialRoundNo) {
    historyTab.value = 'rounds'
    await nextTick()
    const target = dialog.value?.querySelector<HTMLElement>(`[data-round-no="${props.initialRoundNo}"]`)
    if (target) { target.scrollIntoView?.({ block: 'start' }); initialRoundFocused = true }
  }
}
function editPayload(): Record<string, unknown> {
  if (application.value!.formSchema) return { ...payload.value }
  // 固定表单仅编辑已展示字段，保留其他业务字段，不覆盖历史轮次内容。
  const original = application.value!.payload
  const edited = { ...original }
  if (description.value !== String(original.description ?? '')) edited.description = description.value.trim()
  if (amount.value !== '' && String(amount.value) !== String(original.amount ?? '')) edited.amount = Number(amount.value)
  return edited
}
function validate(submit: boolean) {
  fieldErrors.value = {}
  if (!title.value.trim()) { error.value = '请填写申请标题。'; return false }
  if (application.value?.formSchema) {
    fieldErrors.value = validatePayload(application.value.formSchema, payload.value, submit)
    if (Object.keys(fieldErrors.value).length) { error.value = '请按字段提示修改后再操作。'; return false }
    return true
  }
  if ((amount.value === '' && application.value?.payload.amount != null) || (amount.value !== '' && (!Number.isFinite(Number(amount.value)) || Number(amount.value) < 0))) {
    error.value = '请输入不小于零的申请金额。'; return false
  }
  return true
}
async function saveChanges() {
  if (!application.value || !dirty.value) return
  setApplication(await api.updateApplication(application.value.id, {
    expectedVersion: application.value.version, title: title.value.trim(), payload: editPayload()
  }))
  emit('changed')
}
async function save(submit = false) {
  if (!canEdit.value || uploading.value || saving.value || draftAssistBusy.value || draftAssistDirty.value || loading.value || writesBlocked.value || cancellationOpen.value) return
  error.value = ''; notice.value = ''
  if (submit && (error.value = initiatorRequirements.submissionError(initiatorAppointmentId.value))) return
  if (!validate(submit)) return
  saving.value = true
  try {
    await saveChanges()
    if (submit) {
      const value = application.value!
      setApplication(await api.submitApplication(value.id, value.version, initiatorAppointmentId.value))
      emit('changed')
      notice.value = `已提交第 ${application.value!.roundNo} 轮审批。`
      rounds.value = (await api.applicationRounds(value.id)).sort((a, b) => b.roundNo - a.roundNo)
    } else notice.value = '修改已保存，尚未提交审批。'
  } catch (cause) { showError(cause) }
  finally {
    saving.value = false
    await nextTick(); dialog.value?.focus()
  }
}
async function openWithdrawal() {
  if (!canWithdraw.value || writesBlocked.value || saving.value || loading.value || draftAssistBusy.value || draftAssistDirty.value) return
  withdrawalOpen.value = true
  await nextTick(); withdrawalInput.value?.focus()
}
async function cancelWithdrawal() {
  withdrawalOpen.value = false; withdrawalComment.value = ''
  await nextTick(); withdrawalTrigger.value?.focus()
}
async function withdraw() {
  if (!application.value || !canWithdraw.value || saving.value || loading.value || writesBlocked.value || draftAssistBusy.value || draftAssistDirty.value) return
  saving.value = true; error.value = ''; notice.value = ''
  try {
    const value = await api.withdrawApplication(application.value.id, {
      expectedVersion: application.value.version, comment: withdrawalComment.value.trim() || undefined
    })
    setApplication(value); emit('changed')
    notice.value = '已撤回当前审批，可以修改后重新提交。'
    rounds.value = (await api.applicationRounds(value.id)).sort((a, b) => b.roundNo - a.roundNo)
  } catch (cause) { showError(cause) }
  finally {
    saving.value = false
    await nextTick(); (withdrawalOpen.value ? withdrawalInput.value : dialog.value)?.focus()
  }
}
function close() {
  if (!runtimeBusy.value && !signatureBusy.value && !signatureDirty.value && !expenseBusy.value && !draftAssistBusy.value && !draftAssistDirty.value && !uploading.value && !saving.value && !dirty.value) emit('close')
}
/** 已确认保存后重新读取申请，读取失败不继续呈现旧版本可编辑正文。 */
async function draftAssistSaved() {
  application.value = null
  await load()
  if (!error.value) notice.value = '勾选字段已保存到草稿，尚未提交审批。'
  emit('changed')
}
function runtimeUpdated(value: InstanceControlView | null) {
  runtimeState.value = value
  if (runtimeBlocked.value) { withdrawalOpen.value = false; withdrawalComment.value = '' }
}
async function openCancellation() {
  if (!canEdit.value || dirty.value || draftAssistBusy.value || draftAssistDirty.value || saving.value || loading.value || writesBlocked.value) return
  cancellationOpen.value = true
  await nextTick(); cancellationInput.value?.focus()
}
async function dismissCancellation() {
  cancellationOpen.value = false; cancellationComment.value = ''
  await nextTick(); cancellationTrigger.value?.focus()
}
async function cancelApplication() {
  if (!application.value || !canEdit.value || dirty.value || draftAssistBusy.value || draftAssistDirty.value || saving.value || loading.value || writesBlocked.value) return
  saving.value = true; error.value = ''; notice.value = ''
  try {
    const value = await api.cancelApplication(application.value.id, {
      expectedVersion: application.value.version, comment: cancellationComment.value.trim() || undefined
    })
    setApplication(value); emit('changed'); historyTab.value = 'audit'
    notice.value = '申请已作废，不能再修改或提交；原内容、审批轮次和历史记录已保留。'
  } catch (cause) { showError(cause) }
  finally {
    saving.value = false
    await nextTick(); (cancellationOpen.value ? cancellationInput.value : dialog.value)?.focus()
  }
}
function trapFocus(event: KeyboardEvent) {
  if (event.key === 'Escape') { event.preventDefault(); close(); return }
  if (event.key !== 'Tab' || !dialog.value) return
  const controls = [...dialog.value.querySelectorAll<HTMLElement>('button, input, select, textarea, summary, [tabindex="0"]')]
    .filter(element => !element.hasAttribute('disabled') && !element.closest('fieldset:disabled') && element.getClientRects().length > 0)
  const first = controls[0]; const last = controls[controls.length - 1]
  if (event.shiftKey && (document.activeElement === first || document.activeElement === dialog.value)) { event.preventDefault(); last?.focus() }
  else if (!event.shiftKey && (document.activeElement === last || document.activeElement === dialog.value)) { event.preventDefault(); first?.focus() }
}
onMounted(async () => {
  returnFocus = document.activeElement as HTMLElement | null
  await nextTick(); dialog.value?.focus(); await load()
})
/** 重提读取原申请绑定；读取失败不改变表单，保存草稿仍可继续。 */
function loadInitiatorRequirements() {
  const value = application.value
  void initiatorRequirements.load(props.scopeKey, canEdit.value && value ? {
    kind: 'application', id: value.id, processKey: value.processKey, definitionVersion: value.definitionVersion
  } : null)
}
watch([() => props.scopeKey, () => application.value?.id, () => canEdit.value], () => {
  initiatorAppointmentId.value = ''; loadInitiatorRequirements()
}, { flush: 'sync' })
onUnmounted(() => { initiatorRequirements.clear(); returnFocus?.focus() })
</script>

<template>
  <div class="modal-backdrop" @click.self="close">
    <section ref="dialog" class="modal application-record" role="dialog" aria-modal="true" aria-labelledby="record-title" tabindex="-1" @keydown="trapFocus">
      <div class="modal-heading">
        <div><p class="eyebrow">APPLICATION RECORD</p><h2 id="record-title">申请详情与轮次</h2></div>
        <button aria-label="关闭申请详情" :disabled="runtimeBusy || signatureBusy || signatureDirty || expenseBusy || draftAssistBusy || draftAssistDirty || uploading || saving || dirty" @click="close">×</button>
      </div>
      <p v-if="error" class="record-alert" role="alert">{{ error }}</p>
      <p v-if="notice" class="record-notice" role="status">{{ notice }}</p>
      <RequestRecovery :pending="pendingWrites" :error="recoveryError" @recover="emit('recover', $event)" />
      <p v-if="loading" class="unavailable" role="status">正在加载申请与提交记录…</p>
      <template v-else-if="application">
        <div class="record-meta"><span class="status-chip">{{ stateLabel(application.status) }}</span><span>第 {{ application.roundNo }} 轮</span><span>{{ application.businessNo }}</span></div>
        <p class="record-binding">{{ application.processKey }} · v{{ application.definitionVersion }} · 申请人 {{ application.createdBy }}</p>
        <InstanceControlPanel v-if="application.status === 'IN_APPROVAL'" :application-id="application.id" :round-no="application.roundNo" :version="application.version" :scope-key="scopeKey" :locked="saving || expenseBusy || writesBlocked" @state="runtimeUpdated" @busy="runtimeBusy = $event" @changed="load(); emit('changed')" />
        <div v-if="['RETURNED', 'WITHDRAWN'].includes(application.status)" class="return-context">
          <strong>{{ conclusionLabel }}</strong><p>{{ conclusionReason }}</p>
          <small v-if="currentRound?.completedBy">{{ currentRound.completedBy }}<template v-if="currentRound.completedAt"> · {{ timeLabel(currentRound.completedAt) }}</template></small>
          <p v-if="canEdit">修改后提交将开始第 {{ application.roundNo + 1 }} 轮审批，前一轮内容和意见会保留。</p>
        </div>
        <ExpenseDetail v-if="expenseId" :report-id="expenseId" :application-id="application.id" :scope-key="scopeKey" :version="application.version" :applicant="application.createdBy === userId" :locked="businessLocked" @busy="expenseBusy = $event" @changed="load(); emit('changed')"><template #restricted><FormFields :schema="application.formSchema" :model-value="application.payload" :attachment-context="{ applicationId: application.id, scopeKey }" readonly /></template></ExpenseDetail>
        <ExpensePlanDetail v-else-if="planId" :plan-id="planId" :application-id="application.id" :scope-key="scopeKey" :version="application.version" :owner="application.createdBy === userId" :locked="businessLocked" @busy="expenseBusy = $event" @changed="load(); emit('changed')"><template #restricted><FormFields :schema="application.formSchema" :model-value="application.payload" :attachment-context="{ applicationId: application.id, scopeKey }" readonly /></template></ExpensePlanDetail>
        <AdvanceRequestDetail v-else-if="advanceRequestId" :request-id="advanceRequestId" :application-id="application.id" :scope-key="scopeKey" :version="application.version" :owner="application.createdBy === userId" :locked="businessLocked" @busy="expenseBusy = $event" @changed="load(); emit('changed')"><template #restricted><FormFields :schema="application.formSchema" :model-value="application.payload" :attachment-context="{ applicationId: application.id, scopeKey }" readonly /></template></AdvanceRequestDetail>
        <ProcurementPaymentDetail v-else-if="procurementPaymentId" :request-id="procurementPaymentId" :application-id="application.id" :scope-key="scopeKey" :version="application.version" :owner="application.createdBy === userId" :locked="businessLocked" @busy="expenseBusy = $event" @changed="load(); emit('changed')"><template #restricted><FormFields :schema="application.formSchema" :model-value="application.payload" :attachment-context="{ applicationId: application.id, scopeKey }" readonly /></template></ProcurementPaymentDetail>
        <BudgetAdjustmentDetail v-else-if="budgetAdjustmentId" :request-id="budgetAdjustmentId" :application-id="application.id" :scope-key="scopeKey" :version="application.version" :owner="application.createdBy === userId" :locked="businessLocked" @busy="expenseBusy = $event" @changed="load(); emit('changed')"><template #restricted><FormFields :schema="application.formSchema" :model-value="application.payload" :attachment-context="{ applicationId: application.id, scopeKey }" readonly /></template></BudgetAdjustmentDetail>
        <form v-else-if="canEdit" novalidate @submit.prevent="save(true)">
          <p v-if="draftAssistDirty" class="record-notice">草稿助手有未完成的填写或核对，请先发送、保存或清空助手输入，再修改或提交申请。</p>
          <fieldset :disabled="saving || draftAssistBusy || draftAssistDirty || writesBlocked || cancellationOpen">
            <label>申请标题<input v-model="title" required maxlength="256" /></label>
            <label>流程版本<input :value="`v${application.definitionVersion}（沿用原版本）`" disabled /></label>
            <FormFields v-if="application.formSchema" v-model="payload" :attachment-context="{ applicationId: application.id, expectedVersion: application.version, scopeKey }" @uploading="uploading = $event" :schema="application.formSchema" :disabled="saving || draftAssistBusy || draftAssistDirty || writesBlocked" :errors="fieldErrors" @update:model-value="fieldErrors = {}" />
            <template v-else><label>申请金额<input v-model="amount" type="number" min="0" step="0.01" :required="application.payload.amount != null" /></label><label>申请说明<textarea v-model="description" rows="3" /></label></template>
          </fieldset>
          <InitiatorRequirementNotice :state="initiatorRequirements" :disabled="saving || writesBlocked || cancellationOpen" @retry="loadInitiatorRequirements" />
          <InitiatorAppointmentPicker v-model="initiatorAppointmentId" :scope-key="scopeKey" :required="initiatorRequirements.required === true" :disabled="saving || writesBlocked || cancellationOpen" />
          <dl v-if="!application.formSchema && extraFields.length" class="payload-list"><template v-for="[key, value] in extraFields" :key="key"><dt>{{ fieldLabel(key) }}</dt><dd>{{ valueLabel(value) }}</dd></template></dl>
          <div class="record-actions"><span>{{ dirty ? '有未保存的修改' : '当前内容已保存' }}</span><button type="button" class="secondary" :disabled="uploading || saving || draftAssistBusy || draftAssistDirty || writesBlocked || !dirty || cancellationOpen" @click="save()">保存修改</button><button class="primary" :disabled="uploading || saving || draftAssistBusy || draftAssistDirty || writesBlocked || cancellationOpen">{{ saving ? '处理中…' : application.status === 'DRAFT' ? '提交申请' : '重新提交审批' }}</button></div>
        </form>
        <template v-else><h3 class="record-section-title">{{ application.title }}</h3><FormFields :schema="application.formSchema" :model-value="application.payload" :attachment-context="{ applicationId: application.id, scopeKey }" readonly /><p class="unavailable">{{ application.createdBy !== userId ? '只有申请人可在草稿、退回或撤回状态下修改内容。' : '当前申请不可编辑，可查看下方提交记录。' }}</p></template>
        <DraftAssistPanel v-if="!application.businessReference && application.formSchema && application.createdBy === userId" :application-id="application.id" :scope-key="scopeKey" :version="application.version" :editable="canEdit" :application-dirty="dirty" :locked="saving || uploading || writesBlocked || cancellationOpen || withdrawalOpen" @busy="draftAssistBusy = $event" @dirty="draftAssistDirty = $event" @saved="draftAssistSaved" />
        <section v-if="canEdit" class="withdrawal-panel" aria-label="作废申请">
          <template v-if="!cancellationOpen">
            <p>不再需要这份申请时可以作废。作废后不能修改或重新提交，原内容与历史记录会保留。</p>
            <p v-if="dirty">请先保存修改，或重新加载已保存的内容，再作废申请。</p>
            <button ref="cancellationTrigger" type="button" class="return" :disabled="uploading || saving || draftAssistBusy || draftAssistDirty || loading || writesBlocked || dirty" @click="openCancellation">作废申请</button>
          </template>
          <form v-else @submit.prevent="cancelApplication">
            <h3>确认作废这份申请</h3><p>作废后将结束这份申请，不能恢复编辑或重新提交。历史审批意见和轮次不会删除。</p>
            <label>作废说明（选填）<textarea ref="cancellationInput" v-model="cancellationComment" rows="3" maxlength="2000" :disabled="saving || writesBlocked" placeholder="例如：申请计划已取消" /></label>
            <div class="form-actions"><button type="button" class="secondary" :disabled="uploading || saving || writesBlocked" @click="dismissCancellation">暂不作废</button><button class="return" :disabled="uploading || saving || writesBlocked || dirty">{{ saving ? '正在作废…' : '确认作废申请' }}</button></div>
          </form>
        </section>
        <p v-if="application.status === 'CANCELLED'" class="return-context">此申请已作废，不能再修改或提交。作废人、说明和时间可在操作审计中查看。</p>
        <section v-if="canWithdraw" class="withdrawal-panel" aria-label="撤回审批">
          <template v-if="!withdrawalOpen"><p>需要修改这份申请？先撤回当前审批，再补正并重新提交。</p><button ref="withdrawalTrigger" type="button" class="return" :disabled="uploading || saving || draftAssistBusy || draftAssistDirty || writesBlocked" @click="openWithdrawal">撤回审批</button></template>
          <form v-else @submit.prevent="withdraw">
            <h3>撤回当前审批</h3><p>撤回后，当前待办将停止，已经产生的审批意见会保留。再次提交会开始新一轮审批。</p>
            <label>撤回说明（选填）<textarea ref="withdrawalInput" v-model="withdrawalComment" rows="3" maxlength="2000" :disabled="saving || writesBlocked" placeholder="例如：需要补充申请材料" /></label>
            <div class="form-actions"><button type="button" class="secondary" :disabled="uploading || saving || writesBlocked" @click="cancelWithdrawal">暂不撤回</button><button class="return" :disabled="uploading || saving || writesBlocked">{{ saving ? '正在撤回…' : '确认撤回审批' }}</button></div>
          </form>
        </section>
        <p v-if="initialRoundNo" class="field-help">关联入口指向第 {{ initialRoundNo }} 轮提交记录；上方显示申请当前状态。</p>
        <div class="record-history-tabs" role="group" aria-label="选择申请历史视图"><button type="button" :disabled="signatureBusy || signatureDirty" :aria-pressed="historyTab === 'rounds'" @click="historyTab = 'rounds'">提交轮次</button><button type="button" :disabled="signatureBusy || signatureDirty" :aria-pressed="historyTab === 'relations'" @click="historyTab = 'relations'">父子流程</button><button type="button" :disabled="signatureBusy || signatureDirty" :aria-pressed="historyTab === 'compare'" @click="historyTab = 'compare'">内容对比</button><button type="button" :disabled="signatureBusy || signatureDirty" :aria-pressed="historyTab === 'diagram'" @click="historyTab = 'diagram'">流程图</button><button type="button" :disabled="signatureBusy || signatureDirty" :aria-pressed="historyTab === 'timeline'" @click="historyTab = 'timeline'">审批轨迹</button><button type="button" :disabled="signatureBusy || signatureDirty" :aria-pressed="historyTab === 'audit'" @click="historyTab = 'audit'">操作审计</button><button type="button" :disabled="signatureBusy || signatureDirty" :aria-pressed="historyTab === 'comments'" @click="historyTab = 'comments'">协作评论</button><button type="button" :disabled="signatureBusy || signatureDirty" :aria-pressed="historyTab === 'assist'" @click="historyTab = 'assist'">Agent 摘要</button><button type="button" :disabled="signatureBusy || signatureDirty" :aria-pressed="historyTab === 'services'" @click="historyTab = 'services'">服务运行</button><button type="button" :disabled="signatureBusy || signatureDirty" :aria-pressed="historyTab === 'signatures'" @click="historyTab = 'signatures'">电子签</button></div>
        <section v-if="historyTab === 'rounds'" class="round-history" aria-label="提交轮次记录">
          <div class="record-history-heading"><h3>提交轮次</h3><span>{{ rounds.length }} 条记录</span></div>
          <p v-if="!rounds.length" class="unavailable">{{ application.status === 'DRAFT' ? '尚未提交，保存修改不会产生审批轮次。' : application.status === 'CANCELLED' ? '此申请没有提交轮次记录。作废不会补造审批轮次。' : '此申请暂无提交快照。早期版本的历史内容不会用当前内容补写。' }}</p>
          <details v-for="round in rounds" :key="round.roundNo" class="round-card" :data-round-no="round.roundNo" :open="round.roundNo === initialRoundNo">
            <summary><span class="round-index">{{ round.roundNo }}</span><span class="round-summary"><strong>第 {{ round.roundNo }} 轮 · {{ stateLabel(round.status) }}</strong><small>{{ timeLabel(round.submittedAt) }} · {{ round.submittedBy }} 提交</small></span><span class="round-version">v{{ round.definitionVersion }}</span></summary>
            <div class="round-content"><h4>{{ round.title }}</h4><SubmissionRiskStatus :risk="round.risk" /><p class="field-help">发起任职：{{ round.initiatorContext ? initiatorContextLabel(round.initiatorContext) : '本轮未记录任职上下文' }}</p><template v-if="expenseId"><button type="button" class="secondary" @click="selectedExpenseRound = selectedExpenseRound === round.roundNo ? null : round.roundNo">{{ selectedExpenseRound === round.roundNo ? '收起本轮费用' : '查看本轮费用与核减记录' }}</button><ExpenseDetail v-if="selectedExpenseRound === round.roundNo" :report-id="expenseId" :application-id="application.id" :scope-key="scopeKey" :version="application.version"  :round-no="round.roundNo"><template #restricted><FormFields :schema="round.formSchema" :model-value="round.payload" :attachment-context="{ applicationId: application.id, roundNo: round.roundNo, scopeKey }" readonly /></template></ExpenseDetail></template><template v-else-if="planId"><button type="button" class="secondary" @click="selectedExpenseRound = selectedExpenseRound === round.roundNo ? null : round.roundNo">{{ selectedExpenseRound === round.roundNo ? '收起本轮计划' : '查看本轮冻结计划' }}</button><ExpensePlanDetail v-if="selectedExpenseRound === round.roundNo" :plan-id="planId" :application-id="application.id" :scope-key="scopeKey" :version="application.version" :round-no="round.roundNo"><template #restricted><FormFields :schema="round.formSchema" :model-value="round.payload" :attachment-context="{ applicationId: application.id, roundNo: round.roundNo, scopeKey }" readonly /></template></ExpensePlanDetail></template><template v-else-if="advanceRequestId"><button type="button" class="secondary" @click="selectedExpenseRound = selectedExpenseRound === round.roundNo ? null : round.roundNo">{{ selectedExpenseRound === round.roundNo ? '收起本轮借款约定' : '查看本轮冻结借款约定' }}</button><AdvanceRequestDetail v-if="selectedExpenseRound === round.roundNo" :request-id="advanceRequestId" :application-id="application.id" :scope-key="scopeKey" :version="application.version" :round-no="round.roundNo"><template #restricted><FormFields :schema="round.formSchema" :model-value="round.payload" :attachment-context="{ applicationId: application.id, roundNo: round.roundNo, scopeKey }" readonly /></template></AdvanceRequestDetail></template><template v-else-if="procurementPaymentId"><button type="button" class="secondary" @click="selectedExpenseRound = selectedExpenseRound === round.roundNo ? null : round.roundNo">{{ selectedExpenseRound === round.roundNo ? '收起本轮采购付款依据' : '查看本轮冻结采购付款依据' }}</button><ProcurementPaymentDetail v-if="selectedExpenseRound === round.roundNo" :request-id="procurementPaymentId" :application-id="application.id" :scope-key="scopeKey" :version="application.version" :round-no="round.roundNo"><template #restricted><FormFields :schema="round.formSchema" :model-value="round.payload" :attachment-context="{ applicationId: application.id, roundNo: round.roundNo, scopeKey }" readonly /></template></ProcurementPaymentDetail></template><template v-else-if="budgetAdjustmentId"><button type="button" class="secondary" @click="selectedExpenseRound = selectedExpenseRound === round.roundNo ? null : round.roundNo">{{ selectedExpenseRound === round.roundNo ? '收起本轮预算调整依据' : '查看本轮冻结预算调整依据' }}</button><BudgetAdjustmentDetail v-if="selectedExpenseRound === round.roundNo" :request-id="budgetAdjustmentId" :application-id="application.id" :scope-key="scopeKey" :version="application.version" :round-no="round.roundNo"><template #restricted><FormFields :schema="round.formSchema" :model-value="round.payload" :attachment-context="{ applicationId: application.id, roundNo: round.roundNo, scopeKey }" readonly /></template></BudgetAdjustmentDetail></template><FormFields v-else :schema="round.formSchema" :model-value="round.payload" :attachment-context="{ applicationId: application.id, roundNo: round.roundNo, scopeKey }" readonly /><div v-if="round.reason" class="round-reason"><strong>{{ round.status === 'RETURNED' ? '退回原因' : round.status === 'WITHDRAWN' ? '撤回说明' : '处理意见' }}</strong><p>{{ round.reason }}</p></div><p v-if="round.completedAt" class="unavailable">{{ round.completedBy }} · {{ timeLabel(round.completedAt) }} · {{ stateLabel(round.status) }}</p><small class="round-footnote">本轮提交时的内容，后续修改不会覆盖。</small></div>
          </details>
        </section>
        <SubprocessRelations v-else-if="historyTab === 'relations'" :application-id="application.id" :rounds="rounds" :scope-key="scopeKey" :version="application.version" :initial-round-no="initialRoundNo" :locked="relatedNavigationLocked" @open="openRelated" />
        <RoundComparison v-else-if="historyTab === 'compare'" :application-id="application.id" :scope-key="scopeKey" :version="application.version" />
        <RoundDiagram v-else-if="historyTab === 'diagram'" :application-id="application.id" :rounds="rounds" :scope-key="scopeKey" :version="application.version" :locked="writesBlocked" @changed="load(); emit('changed')" />
        <AssistRunRecords v-else-if="historyTab === 'assist'" :application-id="application.id" :scope-key="scopeKey" :version="application.version" :round-no="application.roundNo" />
        <ServiceTaskRuntimePanel v-else-if="historyTab === 'services'" :application-id="application.id" :version="application.version" :scope-key="scopeKey" :rounds="rounds" :initial-round-no="initialRoundNo" @changed="load(); emit('changed')" />
        <SignaturePanel v-else-if="historyTab === 'signatures'" :application="application" :rounds="rounds" :scope-key="scopeKey" :user-id="userId" :initial-round-no="initialRoundNo" :locked="writesBlocked || saving || loading" @busy="signatureBusy = $event" @dirty="signatureDirty = $event" />
        <ApplicationHistory v-else-if="historyTab !== 'comments'" :application-id="application.id" :mode="historyTab" :round-no-max="application.roundNo" :version="application.version" />
        <ApplicationComments v-else :application-id="application.id" :scope-key="scopeKey" :version="application.version" :status="application.status" :round-no="application.roundNo" :locked="saving || loading || writesBlocked" :refresh-version="commentRefreshVersion" @posted="emit('commentPosted')" @refresh-application="load" />
      </template>
      <div class="form-actions"><button v-if="dirty || draftAssistDirty || signatureDirty" type="button" class="return" :disabled="uploading || saving || draftAssistBusy || signatureBusy" @click="emit('close')">放弃修改并关闭</button><button type="button" class="secondary" :disabled="expenseBusy || draftAssistBusy || signatureBusy || writesBlocked || uploading || saving || loading" @click="load">{{ dirty || draftAssistDirty || signatureDirty ? '放弃修改并重新加载' : '重新加载' }}</button><button type="button" class="secondary" :disabled="expenseBusy || draftAssistBusy || draftAssistDirty || signatureBusy || signatureDirty || uploading || saving || dirty" @click="close">关闭</button></div>
    </section>
  </div>
</template>

<style scoped>
.application-record{width:min(760px,100%)}
.form-actions{flex-wrap:wrap}
.record-meta{display:flex;align-items:center;flex-wrap:wrap;gap:12px;font-size:12px}
.record-binding{color:var(--muted);font-size:11px;overflow-wrap:anywhere;margin:12px 0 22px}
.record-alert,.record-notice{border-radius:9px;padding:12px 15px;font-size:12px;line-height:1.7}
.record-alert{color:var(--red);background:#fff0ed}.record-notice{color:var(--deep);background:var(--soft)}
.return-context{border-left:3px solid var(--red);background:#fff7f5;padding:14px 17px;margin-bottom:24px;font-size:12px;line-height:1.7}
.return-context p,.round-reason p{white-space:pre-wrap;overflow-wrap:anywhere;margin:7px 0}.return-context small{color:var(--muted)}
.withdrawal-panel{border:1px solid var(--line);border-radius:10px;background:var(--paper);padding:16px;margin:20px 0}.withdrawal-panel h3{font-size:14px;margin:0 0 10px}.withdrawal-panel p{font-size:12px;color:var(--muted);line-height:1.8;margin:0 0 13px}.withdrawal-panel label{margin-bottom:0}
.record-fields{display:grid;grid-template-columns:1fr 1fr;gap:16px}.record-fields input:disabled{color:var(--muted)}
.record-actions{display:flex;align-items:center;gap:10px;margin:22px 0 28px}.record-actions>span{font-size:11px;color:var(--muted);margin-right:auto}
.record-section-title{font-size:17px;margin-top:22px}.round-history{padding-top:20px}
.record-history-tabs{display:flex;gap:6px;flex-wrap:wrap;border-bottom:1px solid var(--line);margin-top:24px;padding-bottom:9px}.record-history-tabs button{font-size:12px;border:0;background:transparent;color:var(--muted);padding:10px 13px;border-radius:8px}.record-history-tabs button[aria-pressed="true"]{background:var(--soft);color:var(--deep);font-weight:600}
.record-history-heading{display:flex;justify-content:space-between;align-items:center;margin-bottom:14px}.record-history-heading h3{margin:0;font-size:16px}.record-history-heading>span{font-size:11px;color:var(--muted)}
.round-card{border:1px solid var(--line);border-radius:12px;margin-bottom:12px;overflow:hidden}.round-card summary{display:flex;align-items:center;gap:12px;padding:15px;cursor:pointer;list-style:none}.round-card summary::-webkit-details-marker{display:none}.round-card summary::after{content:'＋';color:var(--muted)}.round-card[open] summary::after{content:'−'}
.round-index{width:30px;height:30px;flex-shrink:0;display:grid;place-items:center;border-radius:9px;background:var(--soft);color:var(--deep);font:12px 'DM Mono',monospace}.round-summary{display:grid;gap:5px}.round-summary strong{font-size:12px}.round-summary small,.round-version{font-size:10px;color:var(--muted)}.round-version{margin-left:auto;font-family:'DM Mono',monospace}
.round-content{padding:0 18px 18px;border-top:1px solid var(--line)}.round-content h4{font-size:13px}.round-reason{font-size:12px;padding:12px;background:var(--paper);border-radius:8px}.round-footnote{font-size:10px;color:var(--muted)}
textarea:focus-visible,summary:focus-visible{outline:3px solid rgba(33,173,159,.35);outline-offset:2px}
@media(max-width:650px){.record-fields{grid-template-columns:1fr;gap:0}.record-actions{flex-wrap:wrap}.record-actions>span{width:100%}.record-actions .primary{flex:1}.round-card summary{gap:9px;padding:13px 10px}.round-content{padding:0 12px 15px}}
</style>
