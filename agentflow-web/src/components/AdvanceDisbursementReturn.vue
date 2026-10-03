<script setup lang="ts">
import { computed, nextTick, onUnmounted, ref, watch } from 'vue'
import { api, writeRequests } from '../api'
import { moneyLabel } from '../expenses'
import { disbursementReturnTotal, disbursementReturnLabels, disbursementReturnCheckLabels, disbursementReturnIssue, disbursementReturnError, validateDisbursementReturn, disbursementReturnQueryInput, disbursementResolutionInput, validateDisbursementReturnReceipt, type DisbursementReturnView, type DisbursementReturnQueryInput, type DisbursementResolutionInput } from '../disbursementReturn'
const props = defineProps<{ applicationId: string; advanceId: string; roundNo: number; scopeKey: string; locked?: boolean }>()
const emit = defineEmits<{ busy: [value: boolean]; refreshed: [value: DisbursementReturnView] }>()
const view = ref<DisbursementReturnView | null>(null), loading = ref(false), saving = ref(false), pending = ref<'QUERY' | 'RESOLVE' | null>(null)
const reference = ref(''), comment = ref(''), error = ref(''), notice = ref(''), requiresRefresh = ref(false), unconfirmed = ref(false), form = ref<HTMLFormElement | null>(null)
let epoch = 0, controller: AbortController | null = null
const blocked = computed(() => !!props.locked || loading.value || saving.value || requiresRefresh.value || unconfirmed.value)
const confirmedReturns = computed(() => view.value ? view.value.returns.map(entry => entry.proof) : [])
const candidateReturns = computed(() => view.value?.latestCheck?.evidence ? view.value.latestCheck.evidence.returns : [])
const newReturns = computed(() => candidateReturns.value.filter(entry => !confirmedReturns.value.some(old => old.funding.channel === entry.funding.channel && old.funding.transactionReference === entry.funding.transactionReference)))
function stop() { epoch++; controller?.abort(); controller = null }
function clearMaterials() { view.value = null; pending.value = null; reference.value = ''; comment.value = '' }
function syncPending() {
  const prefix = `/advance-requests/${encodeURIComponent(props.advanceId)}`
  const current = writeRequests.pending().some(entry => entry.path === prefix + '/disbursement-review-checks' || entry.path === prefix + '/disbursement-resolutions')
  if (unconfirmed.value && !current) requiresRefresh.value = true
  unconfirmed.value = current
}
const unsubscribe = writeRequests.subscribe(syncPending)
/** 复核资料只读；刷新不会裁决、续期或自动发送退款。 */
async function load() {
  if (saving.value || loading.value || !props.scopeKey) return
  stop(); const current = epoch, request = new AbortController(); controller = request
  const binding = { applicationId: props.applicationId, advanceId: props.advanceId, roundNo: props.roundNo }
  loading.value = true; clearMaterials(); error.value = ''
  const timeout = setTimeout(() => { if (current === epoch) { stop(); loading.value = false; requiresRefresh.value = true; error.value = '原放款复核读取超时，请刷新重试。' } }, 12_000)
  try {
    const result = await api.advanceDisbursementReview(binding.advanceId, binding.roundNo, request.signal)
    if (current !== epoch) return
    view.value = validateDisbursementReturn(result, binding); requiresRefresh.value = false; syncPending(); emit('refreshed', view.value)
  } catch (cause) { if (current === epoch) { clearMaterials(); error.value = disbursementReturnError(cause); requiresRefresh.value = true } }
  finally { clearTimeout(timeout); if (current === epoch) { loading.value = false; controller = null } }
}
function prepare(action: 'QUERY' | 'RESOLVE') {
  if (blocked.value || !(action === 'QUERY' ? view.value?.canQuery : view.value?.latestCheck?.canResolve)) return
  pending.value = action; reference.value = ''; comment.value = ''; error.value = ''; notice.value = ''; const current = epoch
  void nextTick(() => { if (current === epoch) form.value?.querySelector<HTMLInputElement | HTMLTextAreaElement>('input,textarea')?.focus() })
}
function isResolution(input: DisbursementReturnQueryInput | DisbursementResolutionInput): input is DisbursementResolutionInput { return 'checkId' in input }
/** 采用原放款和完整累计回款依据，未知结果交给原幂等请求恢复。 */
async function execute() {
  const value = view.value, action = pending.value; if (!value || !action || blocked.value) return
  let input
  try { input = action === 'QUERY' ? disbursementReturnQueryInput(value, comment.value) : disbursementResolutionInput(value, reference.value, comment.value) }
  catch (cause) { error.value = disbursementReturnError(cause); return }
  const current = epoch; saving.value = true; error.value = ''; emit('busy', true)
  try {
    const result = isResolution(input) ? await api.resolveAdvanceDisbursement(value.advanceId, input) : await api.queryAdvanceDisbursementReview(value.advanceId, input)
    if (current !== epoch) return
    validateDisbursementReturnReceipt(result, value, input); pending.value = null; saving.value = false; emit('busy', false)
    notice.value = action === 'QUERY' ? '原放款复核查询已登记，请刷新查看原件，核对后再确认。' : '原放款退回复核已保存，银行回款、员工还款与报销占用分别保留。'
    await load()
  } catch (cause) {
    if (current === epoch) {
      if ([401, 403, 404].includes((cause as { status?: number })?.status ?? 0)) clearMaterials()
      error.value = disbursementReturnError(cause); requiresRefresh.value = true
    }
  } finally { if (current === epoch) { saving.value = false; syncPending(); emit('busy', false) } }
}
watch(() => JSON.stringify([props.scopeKey, props.applicationId, props.advanceId, props.roundNo]), () => {
  stop(); clearMaterials(); loading.value = false; saving.value = false; error.value = ''; notice.value = ''; requiresRefresh.value = false
  syncPending(); emit('busy', false); if (props.scopeKey) void load()
}, { immediate: true, flush: 'sync' })
onUnmounted(() => { stop(); unsubscribe(); emit('busy', false) })
</script>

<template>
  <section class="disbursement-return" aria-label="原放款复核与银行退回">
    <div class="heading"><h4>原放款复核与银行退回</h4><button type="button" class="quiet" :disabled="loading || saving || locked" @click="notice = ''; load()">刷新放款复核</button></div>
    <p v-if="loading" role="status">正在读取原放款和银行退回依据…</p><p v-if="error" class="error" role="alert">{{ error }}</p><p v-if="notice" role="status">{{ notice }}</p>
    <p v-if="unconfirmed && !saving" class="error" role="alert">上次放款复核结果尚未确认，请先恢复原操作，再刷新核对。</p>
    <template v-if="view">
      <p>原放款 {{ view.original.paymentReference }} · {{ moneyLabel(view.original.amount) }}<br />{{ new Date(view.original.paidAt).toLocaleString() }}</p>
      <p>银行退回公司账户的原放款单独减少借款欠款。原付款状态、实际入款和借款贷方分录需要共同核对。</p>
      <p v-if="confirmedReturns.length">累计已确认银行退回 {{ moneyLabel(disbursementReturnTotal(confirmedReturns, view.original.amount.currency)) }} · {{ confirmedReturns.length }} 笔</p>
      <p v-for="entry in confirmedReturns" :key="entry.funding.transactionReference">已确认银行退回 {{ moneyLabel(entry.funding.amount) }} · 流水 {{ entry.funding.transactionReference }}<br />贷方凭证 {{ entry.posting.voucherReference }} / {{ entry.posting.entryReference }}</p>
      <p v-if="view.latestDecision">最近确认：{{ disbursementReturnLabels[view.latestDecision.outcome] }}<br />{{ view.latestDecision.resolvedBy }} · {{ new Date(view.latestDecision.resolvedAt).toLocaleString() }} · 材料 {{ view.latestDecision.evidenceReference }}</p>
      <article v-if="view.latestCheck" class="evidence">
        <strong>{{ disbursementReturnCheckLabels[view.latestCheck.status] }}</strong>
        <template v-if="view.latestCheck.evidence">
          <p>{{ disbursementReturnLabels[view.latestCheck.evidence.status] }}</p>
          <dl v-for="entry in candidateReturns" :key="entry.funding.transactionReference"><div><dt>银行实际退回</dt><dd>{{ moneyLabel(entry.funding.amount) }}</dd></div><div><dt>回款流水</dt><dd>{{ entry.funding.transactionReference }}</dd></div><div><dt>借款贷方凭证 / 分录</dt><dd>{{ entry.posting.voucherReference }} / {{ entry.posting.entryReference }}</dd></div><div><dt>公司收款时间</dt><dd>{{ new Date(entry.funding.receivedAt).toLocaleString() }}</dd></div><div><dt>入账日期</dt><dd>{{ entry.posting.accountingDate }}</dd></div></dl>
          <p>本次依据有效至 {{ new Date(view.latestCheck.evidence.validUntil).toLocaleString() }}</p>
        </template>
        <p v-if="view.latestCheck.issue">{{ disbursementReturnIssue(view.latestCheck.issue) }}</p>
        <p v-else-if="view.latestCheck.confirmationIssue && view.latestCheck.status === 'CHECKED'">{{ disbursementReturnIssue(view.latestCheck.confirmationIssue) }}</p>
      </article>
      <div v-if="!pending" class="buttons"><button v-if="view.canQuery" type="button" class="quiet" :disabled="blocked" @click="prepare('QUERY')">查询原放款与银行退回</button><button v-if="view.latestCheck?.canResolve" type="button" class="primary" :disabled="blocked" @click="prepare('RESOLVE')">核对并确认放款复核</button></div>
      <form v-else ref="form" @submit.prevent="execute">
        <h4>{{ pending === 'QUERY' ? '读取原放款与银行退回依据' : '确认原放款复核结论' }}</h4>
        <p v-if="pending === 'QUERY'">读取这笔原放款当前状态和累计银行回款，结果需要独立财务核对确认。</p>
        <p v-else-if="candidateReturns.length">累计实际退回 {{ moneyLabel(disbursementReturnTotal(candidateReturns, view.original.amount.currency)) }}，采用上方 {{ candidateReturns.length }} 笔银行入款与借款贷方分录。本次新增确认 {{ moneyLabel(disbursementReturnTotal(newReturns, view.original.amount.currency)) }}，减少同额未还款；此前已确认记录保留。</p>
        <p v-else>确认原放款仍然有效，本次解除原放款冻结；员工还款的独立冻结继续保留。</p>
        <label v-if="pending === 'RESOLVE'">核验材料编号<input v-model="reference" maxlength="128" required :disabled="saving" /></label>
        <label>核对说明<textarea v-model="comment" rows="3" maxlength="2000" required :disabled="saving" /></label>
        <div class="buttons"><button class="primary" :disabled="blocked">{{ saving ? '正在登记…' : pending === 'QUERY' ? '登记放款复核查询' : '确认并保存放款结论' }}</button><button type="button" class="quiet" :disabled="saving" @click="pending = null; reference = ''; comment = ''">取消</button></div>
      </form>
    </template>
  </section>
</template>

<style scoped>
.disbursement-return{margin-top:16px;padding:16px;border:1px solid var(--line);border-radius:10px;background:var(--paper);min-width:0;overflow-wrap:anywhere}.heading{display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap}h4{font-size:13px;margin:0}p{font-size:12px;line-height:1.8}.error{color:var(--red)}.evidence,form{margin-top:14px;padding-top:14px;border-top:1px solid var(--line)}.evidence>strong{font-size:13px}dl{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px}dt{font-size:11px;color:var(--muted);margin-bottom:6px}dd{margin:0;font-size:12px;font-variant-numeric:tabular-nums}label{display:grid;gap:7px;font-size:12px;margin-top:12px}input,textarea{width:100%;padding:10px;border:1px solid var(--line);border-radius:7px;background:white;font:inherit}textarea{resize:vertical}.buttons{display:flex;gap:10px;flex-wrap:wrap;margin-top:14px}button{min-height:40px}@media(max-width:650px){dl{grid-template-columns:1fr}}
</style>
