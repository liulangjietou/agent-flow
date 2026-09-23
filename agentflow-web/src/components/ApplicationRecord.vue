<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import ApplicationHistory from './ApplicationHistory.vue'
import ApplicationComments from './ApplicationComments.vue'
import RequestRecovery from './RequestRecovery.vue'
import FormFields from './FormFields.vue'
import { validatePayload, type FieldErrors } from '../formSchema'
import { api, type ApiError, type Application, type SubmissionRound } from '../api'
import type { PendingWrite } from '../pendingWrites.js'

const props = defineProps<{ applicationId: string; userId: string; scopeKey: string; commentRefreshVersion: number; pendingWrites: PendingWrite[]; recoveryError: string }>()
const emit = defineEmits<{ close: []; changed: []; commentPosted: []; recover: [id: string] }>()
const writesBlocked = computed(() => props.pendingWrites.length > 0)
const dialog = ref<HTMLElement | null>(null)
const application = ref<Application | null>(null)
const rounds = ref<SubmissionRound[]>([])
const historyTab = ref<'rounds' | 'timeline' | 'audit' | 'comments'>('rounds')
const title = ref('')
const amount = ref('')
const description = ref('')
const payload = ref<Record<string, unknown>>({})
const fieldErrors = ref<FieldErrors>({})
const loading = ref(true)
const saving = ref(false)
const error = ref('')
const notice = ref('')
const initialFields = ref('')
const withdrawalOpen = ref(false)
const withdrawalComment = ref('')
const withdrawalInput = ref<HTMLTextAreaElement | null>(null)
const withdrawalTrigger = ref<HTMLButtonElement | null>(null)
let returnFocus: HTMLElement | null = null
const statusLabels: Record<string, string> = { DRAFT: '草稿', IN_APPROVAL: '审批中', RETURNED: '已退回', WITHDRAWN: '已撤回', REJECTED: '已驳回', APPROVED: '已批准', CANCELLED: '已作废', REVOKED: '已撤销' }
const canEdit = computed(() => application.value?.createdBy === props.userId && ['DRAFT', 'RETURNED', 'WITHDRAWN'].includes(application.value.status))
const canWithdraw = computed(() => application.value?.createdBy === props.userId && application.value.status === 'IN_APPROVAL')
const conclusionLabel = computed(() => application.value?.status === 'WITHDRAWN' ? '撤回说明' : '退回原因')
const dirty = computed(() => application.value !== null && fieldsSnapshot() !== initialFields.value)
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
}
async function load() {
  loading.value = true; error.value = ''; notice.value = ''
  try {
    const [value, history] = await Promise.all([api.application(props.applicationId), api.applicationRounds(props.applicationId)])
    setApplication(value); rounds.value = [...history].sort((a, b) => b.roundNo - a.roundNo)
  } catch (cause) { showError(cause) }
  finally { loading.value = false }
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
  if (!canEdit.value || saving.value || loading.value || writesBlocked.value) return
  error.value = ''; notice.value = ''
  if (!validate(submit)) return
  saving.value = true
  try {
    await saveChanges()
    if (submit) {
      const value = application.value!
      setApplication(await api.submitApplication(value.id, value.version))
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
  withdrawalOpen.value = true
  await nextTick(); withdrawalInput.value?.focus()
}
async function cancelWithdrawal() {
  withdrawalOpen.value = false; withdrawalComment.value = ''
  await nextTick(); withdrawalTrigger.value?.focus()
}
async function withdraw() {
  if (!application.value || !canWithdraw.value || saving.value || loading.value || writesBlocked.value) return
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
  if (!saving.value && !dirty.value) emit('close')
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
onUnmounted(() => returnFocus?.focus())
</script>

<template>
  <div class="modal-backdrop" @click.self="close">
    <section ref="dialog" class="modal application-record" role="dialog" aria-modal="true" aria-labelledby="record-title" tabindex="-1" @keydown="trapFocus">
      <div class="modal-heading">
        <div><p class="eyebrow">APPLICATION RECORD</p><h2 id="record-title">申请详情与轮次</h2></div>
        <button aria-label="关闭申请详情" :disabled="saving || dirty" @click="close">×</button>
      </div>
      <p v-if="error" class="record-alert" role="alert">{{ error }}</p>
      <p v-if="notice" class="record-notice" role="status">{{ notice }}</p>
      <RequestRecovery :pending="pendingWrites" :error="recoveryError" @recover="emit('recover', $event)" />
      <p v-if="loading" class="unavailable" role="status">正在加载申请与提交记录…</p>
      <template v-else-if="application">
        <div class="record-meta"><span class="status-chip">{{ stateLabel(application.status) }}</span><span>第 {{ application.roundNo }} 轮</span><span>{{ application.businessNo }}</span></div>
        <p class="record-binding">{{ application.processKey }} · v{{ application.definitionVersion }} · 申请人 {{ application.createdBy }}</p>
        <div v-if="['RETURNED', 'WITHDRAWN'].includes(application.status)" class="return-context">
          <strong>{{ conclusionLabel }}</strong><p>{{ conclusionReason }}</p>
          <small v-if="currentRound?.completedBy">{{ currentRound.completedBy }}<template v-if="currentRound.completedAt"> · {{ timeLabel(currentRound.completedAt) }}</template></small>
          <p v-if="canEdit">修改后提交将开始第 {{ application.roundNo + 1 }} 轮审批，前一轮内容和意见会保留。</p>
        </div>
        <form v-if="canEdit" novalidate @submit.prevent="save(true)">
          <fieldset :disabled="saving || writesBlocked">
            <label>申请标题<input v-model="title" required maxlength="256" /></label>
            <label>流程版本<input :value="`v${application.definitionVersion}（沿用原版本）`" disabled /></label>
            <FormFields v-if="application.formSchema" v-model="payload" :schema="application.formSchema" :disabled="saving || writesBlocked" :errors="fieldErrors" />
            <template v-else><label>申请金额<input v-model="amount" type="number" min="0" step="0.01" :required="application.payload.amount != null" /></label><label>申请说明<textarea v-model="description" rows="3" /></label></template>
          </fieldset>
          <dl v-if="!application.formSchema && extraFields.length" class="payload-list"><template v-for="[key, value] in extraFields" :key="key"><dt>{{ fieldLabel(key) }}</dt><dd>{{ valueLabel(value) }}</dd></template></dl>
          <div class="record-actions"><span>{{ dirty ? '有未保存的修改' : '当前内容已保存' }}</span><button type="button" class="secondary" :disabled="saving || writesBlocked || !dirty" @click="save()">保存修改</button><button class="primary" :disabled="saving || writesBlocked">{{ saving ? '处理中…' : application.status === 'DRAFT' ? '提交申请' : '重新提交审批' }}</button></div>
        </form>
        <template v-else><h3 class="record-section-title">{{ application.title }}</h3><FormFields :schema="application.formSchema" :model-value="application.payload" readonly /><p class="unavailable">{{ application.createdBy !== userId ? '只有申请人可在草稿、退回或撤回状态下修改内容。' : '当前申请不可编辑，可查看下方提交记录。' }}</p></template>
        <section v-if="canWithdraw" class="withdrawal-panel" aria-label="撤回审批">
          <template v-if="!withdrawalOpen"><p>需要修改这份申请？先撤回当前审批，再补正并重新提交。</p><button ref="withdrawalTrigger" type="button" class="return" :disabled="saving || writesBlocked" @click="openWithdrawal">撤回审批</button></template>
          <form v-else @submit.prevent="withdraw">
            <h3>撤回当前审批</h3><p>撤回后，当前待办将停止，已经产生的审批意见会保留。再次提交会开始新一轮审批。</p>
            <label>撤回说明（选填）<textarea ref="withdrawalInput" v-model="withdrawalComment" rows="3" maxlength="2000" :disabled="saving || writesBlocked" placeholder="例如：需要补充申请材料" /></label>
            <div class="form-actions"><button type="button" class="secondary" :disabled="saving || writesBlocked" @click="cancelWithdrawal">暂不撤回</button><button class="return" :disabled="saving || writesBlocked">{{ saving ? '正在撤回…' : '确认撤回审批' }}</button></div>
          </form>
        </section>
        <div class="record-history-tabs" role="group" aria-label="选择申请历史视图"><button type="button" :aria-pressed="historyTab === 'rounds'" @click="historyTab = 'rounds'">提交轮次</button><button type="button" :aria-pressed="historyTab === 'timeline'" @click="historyTab = 'timeline'">审批轨迹</button><button type="button" :aria-pressed="historyTab === 'audit'" @click="historyTab = 'audit'">操作审计</button><button type="button" :aria-pressed="historyTab === 'comments'" @click="historyTab = 'comments'">协作评论</button></div>
        <section v-if="historyTab === 'rounds'" class="round-history" aria-label="提交轮次记录">
          <div class="record-history-heading"><h3>提交轮次</h3><span>{{ rounds.length }} 条记录</span></div>
          <p v-if="!rounds.length" class="unavailable">{{ application.status === 'DRAFT' ? '尚未提交，保存修改不会产生审批轮次。' : '此申请暂无提交快照。早期版本的历史内容不会用当前内容补写。' }}</p>
          <details v-for="round in rounds" :key="round.roundNo" class="round-card">
            <summary><span class="round-index">{{ round.roundNo }}</span><span class="round-summary"><strong>第 {{ round.roundNo }} 轮 · {{ stateLabel(round.status) }}</strong><small>{{ timeLabel(round.submittedAt) }} · {{ round.submittedBy }} 提交</small></span><span class="round-version">v{{ round.definitionVersion }}</span></summary>
            <div class="round-content"><h4>{{ round.title }}</h4><FormFields :schema="round.formSchema" :model-value="round.payload" readonly /><div v-if="round.reason" class="round-reason"><strong>{{ round.status === 'RETURNED' ? '退回原因' : round.status === 'WITHDRAWN' ? '撤回说明' : '处理意见' }}</strong><p>{{ round.reason }}</p></div><p v-if="round.completedAt" class="unavailable">{{ round.completedBy }} · {{ timeLabel(round.completedAt) }} · {{ stateLabel(round.status) }}</p><small class="round-footnote">本轮提交时的内容，后续修改不会覆盖。</small></div>
          </details>
        </section>
        <ApplicationHistory v-else-if="historyTab !== 'comments'" :application-id="application.id" :mode="historyTab" :round-no-max="application.roundNo" :version="application.version" />
        <ApplicationComments v-else :application-id="application.id" :scope-key="scopeKey" :version="application.version" :status="application.status" :round-no="application.roundNo" :locked="saving || loading || writesBlocked" :refresh-version="commentRefreshVersion" @posted="emit('commentPosted')" @refresh-application="load" />
      </template>
      <div class="form-actions"><button v-if="dirty" type="button" class="return" :disabled="saving" @click="emit('close')">放弃修改并关闭</button><button type="button" class="secondary" :disabled="saving || loading" @click="load">{{ dirty ? '放弃修改并重新加载' : '重新加载' }}</button><button type="button" class="secondary" :disabled="saving || dirty" @click="close">关闭</button></div>
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
