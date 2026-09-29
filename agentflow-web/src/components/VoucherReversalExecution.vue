<script setup lang="ts">
import { computed, nextTick, onUnmounted, ref, watch } from 'vue'
import { api, writeRequests } from '../api'
import { moneyLabel } from '../expenses'
import type { VoucherReversalBinding } from '../voucherReversal'
import { reversalPreparationLabels, reversalExecutionLabels, reversalExecutionActions, reversalExecutionIssue, reversalExecutionError, validateReversalExecution, reversalPrepareInput, reversalAuthorizeInput, reversalOperationInput, validateReversalExecutionReceipt, type VoucherReversalExecutionView, type ReversalExecutionInput, type ReversalExecutionAction } from '../voucherReversalExecution'

const props = defineProps<{ applicationId: string; operationId: string; roundNo: number; applicationVersion: number; businessVersion: number; operationVersion: number; kind: 'EMPLOYEE_ADVANCE' | 'EXPENSE_ACCRUAL' | 'PAYMENT'; scopeKey: string; locked?: boolean }>()
const emit = defineEmits<{ busy: [value: boolean]; changed: [] }>()
const view = ref<VoucherReversalExecutionView | null>(null), loading = ref(false), saving = ref(false), pending = ref<ReversalExecutionAction | null>(null)
const accountingDate = ref(''), reference = ref(''), comment = ref(''), acknowledged = ref(false), error = ref(''), notice = ref(''), requiresRefresh = ref(false), unconfirmed = ref(false), form = ref<HTMLFormElement | null>(null)
let epoch = 0, controller: AbortController | null = null
const blocked = computed(() => !!props.locked || loading.value || saving.value || requiresRefresh.value || unconfirmed.value)
const details = computed(() => view.value?.operation ?? view.value?.latestPreparation)
const lines = computed(() => view.value?.operation?.lines ?? view.value?.latestPreparation?.candidate?.lines ?? [])
const candidate = computed(() => view.value?.operation ? null : view.value?.latestPreparation?.candidate)
const time = (value: string) => new Date(value).toLocaleString('zh-CN')
function stop() { epoch++; controller?.abort(); controller = null }
function clearForm() { pending.value = null; accountingDate.value = ''; reference.value = ''; comment.value = ''; acknowledged.value = false }
function clearMaterials() { view.value = null; clearForm() }
function syncPending() {
  const prefix = `/applications/${encodeURIComponent(props.applicationId)}/vouchers/${encodeURIComponent(props.operationId)}/reversal-execution/`
  const current = writeRequests.pending().some(entry => ['preparations', 'authorizations', 'actions'].some(suffix => entry.path === prefix + suffix))
  if (unconfirmed.value && !current) requiresRefresh.value = true
  unconfirmed.value = current
}
const unsubscribe = writeRequests.subscribe(syncPending)
/** 父凭证刷新后严格绑定版本，迟到响应和身份切换都不能重新展示旧财务材料。 */
async function load() {
  if (saving.value || loading.value || !props.scopeKey) return
  stop(); const current = epoch, request = new AbortController(); controller = request
  const binding: VoucherReversalBinding = { applicationId: props.applicationId, operationId: props.operationId, roundNo: props.roundNo, applicationVersion: props.applicationVersion, businessVersion: props.businessVersion, operationVersion: props.operationVersion, kind: props.kind }
  loading.value = true; clearMaterials(); error.value = ''
  const timeout = setTimeout(() => { if (current === epoch) { stop(); loading.value = false; requiresRefresh.value = true; error.value = '冲销办理状态读取超时，请刷新原凭证。' } }, 12_000)
  try {
    const result = await api.voucherReversalExecution(binding.applicationId, binding.operationId, binding.roundNo, request.signal)
    if (current !== epoch) return
    view.value = validateReversalExecution(result, binding); requiresRefresh.value = false; syncPending()
  } catch (cause) { if (current === epoch) { clearMaterials(); error.value = reversalExecutionError(cause); requiresRefresh.value = true } }
  finally { clearTimeout(timeout); if (current === epoch) { loading.value = false; controller = null } }
}
function allowed(action: ReversalExecutionAction) {
  return action === 'PREPARE' ? !!view.value?.canPrepare : action === 'AUTHORIZE' ? !!view.value?.latestPreparation?.canAuthorize
    : action === 'QUERY' ? !!view.value?.operation?.canQuery : !!view.value?.operation?.canResendOriginal
}
function prepare(action: ReversalExecutionAction) {
  if (blocked.value || !allowed(action)) return
  clearForm(); pending.value = action; error.value = ''; notice.value = ''; const current = epoch
  void nextTick(() => { if (current === epoch) form.value?.querySelector<HTMLInputElement | HTMLTextAreaElement>('input,textarea')?.focus() })
}
/** 准备只核对，授权和重发必须再次确认；写入不明时保留原请求供统一恢复。 */
async function execute() {
  const value = view.value, action = pending.value; if (!value || !action || blocked.value || !allowed(action)) return
  if ((action === 'AUTHORIZE' || action === 'RESEND_ORIGINAL') && !acknowledged.value) { error.value = '请先确认已核对全部反向分录和办理影响。'; return }
  let input: ReversalExecutionInput
  try { input = action === 'PREPARE' ? reversalPrepareInput(value, accountingDate.value, reference.value, comment.value) : action === 'AUTHORIZE' ? reversalAuthorizeInput(value, comment.value) : reversalOperationInput(value, action, comment.value) }
  catch (cause) { error.value = reversalExecutionError(cause); return }
  const current = epoch; saving.value = true; error.value = ''; emit('busy', true)
  try {
    const result = 'accountingDate' in input ? await api.prepareVoucherReversal(value.applicationId, value.operationId, input)
      : 'preparationId' in input ? await api.authorizeVoucherReversal(value.applicationId, value.operationId, input)
        : await api.voucherReversalOperationAction(value.applicationId, value.operationId, input)
    if (current !== epoch) return
    validateReversalExecutionReceipt(result, value, input); clearForm(); saving.value = false; emit('busy', false)
    if (action === 'AUTHORIZE') { clearMaterials(); requiresRefresh.value = true; emit('changed') }
    else {
      notice.value = action === 'PREPARE' ? '原件与期间核验已排队，刷新后审阅全部分录，再明确授权。' : '原冲销操作已登记，请刷新查看实际处理结果。'
      await load()
    }
  } catch (cause) {
    if (current === epoch) {
      if ([401, 403, 404].includes((cause as { status?: number })?.status ?? 0)) clearMaterials()
      error.value = reversalExecutionError(cause); requiresRefresh.value = true
    }
  } finally { if (current === epoch) { saving.value = false; syncPending(); emit('busy', false) } }
}
watch(() => JSON.stringify([props.scopeKey, props.applicationId, props.operationId, props.roundNo, props.applicationVersion, props.businessVersion, props.operationVersion, props.kind]), () => {
  stop(); clearMaterials(); loading.value = false; saving.value = false; error.value = ''; notice.value = ''; requiresRefresh.value = false
  syncPending(); emit('busy', false); if (props.scopeKey) void load()
}, { immediate: true, flush: 'sync' })
onUnmounted(() => { stop(); unsubscribe(); emit('busy', false) })
</script>

<template>
  <section class="reversal-execution" aria-label="办理 ERP 独立冲销">
    <div class="heading"><h4>办理 ERP 独立冲销</h4><button type="button" class="quiet" :disabled="loading || saving || locked" @click="emit('changed')">刷新冲销办理状态</button></div>
    <p>先选择冲销日期，核对原凭证与开放期间，再由财务审阅全部反向分录并授权执行。</p>
    <p v-if="loading" role="status">正在读取冲销准备与执行状态…</p><p v-if="error" class="error" role="alert">{{ error }}</p><p v-if="notice" role="status">{{ notice }}</p>
    <p v-if="unconfirmed && !saving" class="error" role="alert">上次冲销办理结果尚未确认，请先恢复原操作，再刷新原凭证。</p>
    <template v-if="view">
      <p>原凭证 {{ view.original.voucherReference }} · 原日期 {{ view.original.accountingDate }} · 单边合计 {{ moneyLabel(view.original.total) }}</p>
      <p v-if="view.originalHeld" class="held"><strong>原凭证已停用</strong><br />冲销已获授权，原凭证停止作为后续付款、结算和归档依据。银行资金、借款欠款及业务占用仍需分别核对处理。</p>
      <p v-else-if="!view.latestPreparation">尚未发起本次 ERP 冲销办理。</p>
      <article v-if="view.operation" class="evidence">
        <strong>{{ reversalExecutionLabels[view.operation.status] }}</strong>
        <p>{{ view.operation.authorizedBy }} 于 {{ time(view.operation.createdAt) }} 授权 · 最近更新 {{ time(view.operation.updatedAt) }}</p>
        <p>原冲销编号 {{ view.operation.id }}<br />原发送期限 {{ time(view.operation.sendExpiresAt) }}</p>
        <p v-if="view.operation.failure && view.operation.failure !== 'RECHECK_REQUESTED'">{{ reversalExecutionIssue(view.operation.failure) }}</p>
        <p v-if="view.operation.observation?.rejection">{{ reversalExecutionIssue(view.operation.observation.rejection) }}</p>
        <p v-if="view.operation.status === 'UNKNOWN' || view.operation.status === 'QUERYING'">当前无法确认实际结果，正在按原冲销编号核对。</p>
        <p v-if="view.operation.conflictingObservation" class="error">已保留冲突回执。继续查询不会自动消除会计争议。</p>
        <dl v-if="view.operation.observation?.posting"><div><dt>实际反向凭证</dt><dd>{{ view.operation.observation.posting.voucherReference }}</dd></div><div><dt>实际期间 / 日期</dt><dd>{{ view.operation.observation.posting.periodReference }} / {{ view.operation.observation.posting.accountingDate }}</dd></div></dl>
      </article>
      <article v-else-if="view.latestPreparation" class="evidence">
        <strong>{{ reversalPreparationLabels[view.latestPreparation.status] }}</strong>
        <p v-if="view.latestPreparation.issue">{{ reversalExecutionIssue(view.latestPreparation.issue) }}</p>
        <p v-else-if="view.latestPreparation.status === 'READY' && view.latestPreparation.authorizationIssue">{{ reversalExecutionIssue(view.latestPreparation.authorizationIssue) }}</p>
        <p v-if="candidate">准备有效至 {{ time(candidate.expiresAt) }}<br />会计期间 {{ candidate.periodReference }} · 期间版本 {{ candidate.periodSourceVersion }}<br />原凭证复核版本 {{ candidate.originalRevision }} · {{ time(candidate.originalObservedAt) }}</p>
      </article>
      <dl v-if="details"><div><dt>冲销会计日期</dt><dd>{{ details.accountingDate }}</dd></div><div><dt>核对材料</dt><dd>{{ details.evidenceReference }}</dd></div><div class="wide"><dt>冲销原因</dt><dd>{{ details.reason }}</dd></div></dl>
      <ol v-if="lines.length" class="lines" aria-label="本次拟执行的完整反向分录"><li v-for="line in lines" :key="line.originalLineNo"><strong>原第 {{ line.originalLineNo }} 行 · {{ line.side === 'DEBIT' ? '借方' : '贷方' }} {{ moneyLabel(line.amount) }}</strong><span>科目 {{ line.accountCode }}</span><span v-if="line.sourceLineNo">业务行 {{ line.sourceLineNo }}</span><span v-if="line.costCenter">成本中心 {{ line.costCenter }}</span><span v-if="line.projectCode">项目 {{ line.projectCode }}</span><span v-if="line.advanceId">借款 {{ line.advanceId }}</span></li></ol>
      <div v-if="!pending" class="buttons"><button v-for="action in (['PREPARE', 'AUTHORIZE', 'QUERY', 'RESEND_ORIGINAL'] as const).filter(allowed)" :key="action" type="button" :class="action === 'AUTHORIZE' ? 'primary' : 'quiet'" :disabled="blocked" @click="prepare(action)">{{ reversalExecutionActions[action] }}</button></div>
      <form v-else ref="form" @submit.prevent="execute">
        <h4>{{ reversalExecutionActions[pending] }}</h4>
        <template v-if="pending === 'PREPARE'"><p>本步仅核验原件和期间。准备完成后，需要再次确认才能发送冲销。</p><label>冲销会计日期<input v-model="accountingDate" type="date" :min="view.original.accountingDate" required :disabled="saving" /></label><label>核对材料编号<input v-model="reference" maxlength="128" required :disabled="saving" /></label></template>
        <p v-else-if="pending === 'AUTHORIZE'">确认后按上方日期和全部分录办理 ERP 冲销，并立即停用原凭证。授权不会自动撤回银行付款、释放业务占用或删除封存档案。</p>
        <p v-else-if="pending === 'RESEND_ORIGINAL'">ERP 已明确查无原操作。本次保留原编号、日期和全部分录，按原授权重发。</p>
        <p v-else>按已授权的原冲销编号查询实际结果，保留已知回执与争议。</p>
        <label>办理说明<textarea v-model="comment" rows="3" maxlength="2000" required :disabled="saving" /></label>
        <label v-if="pending === 'AUTHORIZE' || pending === 'RESEND_ORIGINAL'" class="confirmation"><input v-model="acknowledged" type="checkbox" required :disabled="saving" /><span>已核对全部反向分录、会计日期和办理影响，确认{{ pending === 'AUTHORIZE' ? '授权执行' : '按原编号重发' }}。</span></label>
        <div class="buttons"><button class="primary" :disabled="blocked">{{ saving ? '正在登记…' : `确认${reversalExecutionActions[pending]}` }}</button><button type="button" class="quiet" :disabled="saving" @click="clearForm">返回核对</button></div>
      </form>
    </template>
  </section>
</template>

<style scoped>
.reversal-execution{margin-top:18px;padding:16px;border:1px solid var(--line);border-radius:10px;background:var(--paper);min-width:0;overflow-wrap:anywhere}.heading{display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap}h4{font-size:13px;margin:0}p{font-size:12px;line-height:1.8}.error{color:var(--red)}.held{padding:12px;background:var(--soft);border-left:3px solid var(--accent);border-radius:6px}.evidence,form{margin-top:14px;padding-top:14px;border-top:1px solid var(--line)}.evidence>strong{font-size:13px}dl{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px}dt{font-size:11px;color:var(--muted);margin-bottom:6px}dd{margin:0;font-size:12px;font-variant-numeric:tabular-nums}.wide{grid-column:1/-1}.lines{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px;padding:0;list-style:none}.lines li{display:grid;gap:6px;padding:12px;border:1px solid var(--line);border-radius:8px;font-size:11px;line-height:1.7}.lines strong{font-size:12px}label{display:grid;gap:7px;font-size:12px;margin-top:12px}input,textarea{width:100%;min-width:0;padding:10px;border:1px solid var(--line);border-radius:7px;background:white;font:inherit}textarea{resize:vertical}.confirmation{display:flex;align-items:flex-start;gap:10px;line-height:1.8}.confirmation input{width:16px;min-width:16px;margin-top:3px}.buttons{display:flex;gap:10px;flex-wrap:wrap;margin-top:14px}button{min-height:40px}@media(max-width:650px){dl,.lines{grid-template-columns:1fr}}
</style>
