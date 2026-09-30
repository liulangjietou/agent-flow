<script setup lang="ts">
import { computed, nextTick, onUnmounted, ref, watch } from 'vue'
import { api, writeRequests } from '../api'
import SupplierSettlementStatus from './SupplierSettlementStatus.vue'
import SupplierDisputeStatus from './SupplierDisputeStatus.vue'
import SupplierPaymentReturn from './SupplierPaymentReturn.vue'
import SupplierAdjustmentStatus from './SupplierAdjustmentStatus.vue'
import { supplierActionLabels, supplierActionAllowed, supplierFinanceInput, validateSupplierFinance, validateSupplierFinanceReceipt, supplierFinanceError, supplierIssueLabels, reviewLabels, holdLabels, type SupplierFinanceAction, type SupplierFinanceView } from '../supplierFinance'

const props = defineProps<{ requestId: string; applicationId: string; roundNo: number; applicationVersion: number; requestVersion: number; scopeKey: string; locked?: boolean }>()
const emit = defineEmits<{ busy: [value: boolean] }>()
const view = ref<SupplierFinanceView | null>(null), loading = ref(false), saving = ref(false), requiresRefresh = ref(false), unconfirmed = ref(false)
const settlementBusy = ref(false), disputeBusy = ref(false), returnBusy = ref(false), adjustmentBusy = ref(false), settlementRevision = ref(0), returnRevision = ref(0), adjustmentRevision = ref(0)
const error = ref(''), notice = ref(''), pending = ref<SupplierFinanceAction | null>(null), comment = ref(''), form = ref<HTMLFormElement | null>(null)
let epoch = 0, controller: AbortController | null = null
const now = ref(Date.now()), ticker = setInterval(() => { now.value = Date.now() }, 1000)
function syncPending() {
  const active = writeRequests.pending().some(entry => entry.path.startsWith(`/procurement-payments/${encodeURIComponent(props.requestId)}/supplier-payment/`) || entry.path.startsWith('/supplier-payments/') || entry.path.startsWith('/supplier-settlements/') || entry.path.startsWith('/supplier-adjustments/'))
  if (unconfirmed.value && !active) requiresRefresh.value = true
  unconfirmed.value = active
}
const unsubscribe = writeRequests.subscribe(syncPending)
const blocked = computed(() => !!props.locked || loading.value || saving.value || settlementBusy.value || disputeBusy.value || returnBusy.value || adjustmentBusy.value || requiresRefresh.value || unconfirmed.value)
function emitBusy() { emit('busy', saving.value || settlementBusy.value || disputeBusy.value || returnBusy.value || adjustmentBusy.value) }
function settlementActivity(value: boolean) { settlementBusy.value = value; emitBusy() }
function disputeActivity(value: boolean) { disputeBusy.value = value; emitBusy() }
function returnActivity(value: boolean) { returnBusy.value = value; emitBusy() }
function adjustmentActivity(value: boolean) { adjustmentBusy.value = value; emitBusy() }
/** 调整完成后重新读取实际回款和原核销，本次调整面板保留核对位置。 */
function adjustmentChanged() { returnRevision.value++; settlementRevision.value++ }
/** 银行决定保存后重读应付结算，不能沿用裁决前的银行版本。 */
function disputeChanged() { settlementRevision.value++; returnRevision.value++; adjustmentRevision.value++ }
/** 回款冻结影响整个原应付，重新读取主状态和结算能力。 */
function returnChanged() { notice.value = '回款状态已变化，正在重新核对原付款与核销进度。'; void load() }
function stop() { epoch++; controller?.abort(); controller = null }
/** 身份、批准版本或业务绑定变化时，旧响应不能重新显示金融事实和按钮。 */
async function load() {
  if (saving.value || settlementBusy.value || disputeBusy.value || returnBusy.value || adjustmentBusy.value || !props.scopeKey) return
  stop(); const current = epoch, request = new AbortController(); controller = request; loading.value = true
  view.value = null; pending.value = null; comment.value = ''; error.value = ''
  const binding = { requestId: props.requestId, applicationId: props.applicationId, roundNo: props.roundNo, applicationVersion: props.applicationVersion, requestVersion: props.requestVersion }
  const timeout = setTimeout(() => { if (current === epoch) { stop(); loading.value = false; error.value = '供应商付款状态读取超时，请重新查询。' } }, 12_000)
  try {
    const result = await api.supplierFinance(binding.requestId, binding.roundNo, request.signal)
    if (current !== epoch) return
    view.value = validateSupplierFinance(result, binding); requiresRefresh.value = false; syncPending()
  } catch (cause) { if (current === epoch) error.value = supplierFinanceError(cause) }
  finally { clearTimeout(timeout); if (current === epoch) { loading.value = false; controller = null } }
}
function allowed(action: SupplierFinanceAction) { return supplierActionAllowed(view.value, action, now.value) }
function prepare(action: SupplierFinanceAction) {
  if (blocked.value || !allowed(action)) return
  pending.value = action; comment.value = ''; error.value = ''; notice.value = ''; const current = epoch
  void nextTick(() => { if (current === epoch) form.value?.querySelector('textarea')?.focus() })
}
async function execute() {
  const value = view.value, action = pending.value; if (!value || !action || blocked.value) return
  let input
  try { input = supplierFinanceInput(value, action, comment.value) } catch (cause) { error.value = supplierFinanceError(cause); return }
  const current = epoch; saving.value = true; emitBusy(); error.value = ''
  try {
    const receipt = 'reviewId' in input ? await api.authorizeSupplierPayment(value.requestId, input) : 'roundNo' in input ? await api.reviewSupplierPayable(value.requestId, input) : await api.supplierHoldAction(value.authorization!.id, input)
    if (current !== epoch) return
    validateSupplierFinanceReceipt(receipt, value, action)
    saving.value = false; pending.value = null; emitBusy()
    notice.value = action === 'REVIEW' ? '复核已登记，请刷新读取结果，核对后明确授权。' : action === 'AUTHORIZE' ? '财务授权已保存，请刷新原应付预留进度。' : action === 'RETIRE' ? '原授权已安全结束，需要重新复核后才能另行授权。' : '原预留处理已登记，请刷新核对结果。'
    await load()
  } catch (cause) { if (current === epoch) { requiresRefresh.value = true; error.value = supplierFinanceError(cause) } }
  finally { if (current === epoch) { saving.value = false; syncPending(); emitBusy() } }
}
watch(() => JSON.stringify([props.scopeKey, props.requestId, props.applicationId, props.roundNo, props.applicationVersion, props.requestVersion]), () => {
  stop(); view.value = null; loading.value = false; saving.value = false; settlementBusy.value = false; disputeBusy.value = false; returnBusy.value = false; adjustmentBusy.value = false; adjustmentRevision.value = 0; settlementRevision.value = 0; returnRevision.value = 0; requiresRefresh.value = false; pending.value = null; comment.value = ''; error.value = ''; notice.value = ''
  syncPending(); emit('busy', false); if (props.scopeKey) void load()
}, { immediate: true, flush: 'sync' })
onUnmounted(() => { stop(); clearInterval(ticker); unsubscribe(); emit('busy', false) })
const date = (value: string) => new Date(value).toLocaleString('zh-CN')
</script>

<template>
  <section class="supplier-finance" aria-label="供应商原应付付款办理">
    <div class="supplier-heading"><div><p class="supplier-eyebrow">原应付 · 独立财务办理</p><h3>供应商付款进度</h3></div><button class="quiet" type="button" :disabled="loading || saving || settlementBusy || returnBusy || disputeBusy || adjustmentBusy || locked" @click="notice = ''; load()">刷新办理状态</button></div>
    <p class="supplier-help">核对当前应付后授权预留。预留成功后仍需由独立出纳付款，到账与结算分别确认。</p>
    <p v-if="loading" role="status" class="supplier-help">正在核对本轮权限与办理状态…</p>
    <p v-if="error" role="alert" class="supplier-error">{{ error }}</p><p v-if="notice" role="status" class="supplier-help">{{ notice }}</p>
    <p v-if="unconfirmed && !saving" role="alert" class="supplier-error">上次操作结果未确认，请在未确认操作中恢复原请求后刷新。</p>
    <p v-if="requiresRefresh && !unconfirmed && !error" role="status" class="supplier-help">办理记录已变化，请刷新核对原预留状态。</p>
    <template v-if="view">
      <div class="supplier-overview"><div><span>本轮批准金额</span><strong>{{ view.approvedAmount?.currency }} {{ view.approvedAmount?.value }}</strong></div><div><span>原应付预留</span><strong>{{ view.hold ? holdLabels[view.hold.status] : '尚未授权预留' }}</strong></div></div>
      <article v-if="view.review" class="supplier-evidence" aria-label="本人应付复核">
        <h4>本人应付复核 · {{ reviewLabels[view.review.status] }}</h4>
        <div v-if="view.review.outstanding" class="supplier-facts"><p>当前未付余额<strong>{{ view.review.outstanding.currency }} {{ view.review.outstanding.value }}</strong></p><p>原累计已付<strong>{{ view.review.settled?.currency }} {{ view.review.settled?.value }}</strong></p><p>供应商账户<strong>{{ view.review.maskedAccount }}</strong></p></div>
        <p v-if="view.review.validUntil" class="supplier-help">本次复核依据有效至 {{ date(view.review.validUntil) }}<span v-if="view.review.status === 'READY' && Date.parse(view.review.validUntil) <= now"> · 已到期，请重新复核</span></p>
        <p v-if="view.review.issue" class="supplier-help">{{ supplierIssueLabels[view.review.issue] }}</p>
      </article>
      <article v-if="view.authorization" class="supplier-evidence" aria-label="原财务授权">
        <h4>{{ view.authorization.retiredAt ? '原授权已结束' : '原财务授权' }}</h4>
        <p class="supplier-help">{{ view.authorization.authorizedBy }} · {{ date(view.authorization.authorizedAt) }}<br />授权有效至 {{ date(view.authorization.expiresAt) }}<br />供应商账户 {{ view.authorization.maskedAccount }}</p>
        <p v-if="view.authorization.retiredAt" class="supplier-help">{{ date(view.authorization.retiredAt) }} · {{ view.authorization.retirementBasis === 'NEVER_DISPATCHED' ? '确认从未外发预留' : '原预留已被明确拒绝' }}</p>
        <p v-if="view.hold?.observedAt" class="supplier-help">最近预留查询 {{ date(view.hold.observedAt) }}</p><p v-if="view.hold?.failure" class="supplier-help">{{ supplierIssueLabels[view.hold.failure] }}</p>
      </article>
      <div v-if="!pending" class="supplier-buttons"><button v-for="action in (['REVIEW', 'AUTHORIZE', 'QUERY', 'RETRY', 'RETIRE'] as const)" v-show="allowed(action)" :key="action" type="button" :class="action === 'AUTHORIZE' ? 'primary' : 'quiet'" :disabled="blocked" @click="prepare(action)">{{ supplierActionLabels[action] }}</button></div>
      <form v-else ref="form" class="supplier-confirm" @submit.prevent="execute">
        <h4>{{ supplierActionLabels[pending] }}</h4>
        <p v-if="pending === 'REVIEW'">从原财务系统读取本次批准对应的应付。读取完成后，请核对余额和供应商账户，再确认财务授权。</p>
        <p v-else-if="pending === 'AUTHORIZE'">确认批准金额 {{ view.approvedAmount?.currency }} {{ view.approvedAmount?.value }}，账户 {{ view.review?.maskedAccount }}。本次授权有效 24 小时，预留须在本次复核依据有效期内办理。</p>
        <p v-else-if="pending === 'RETIRE'">依据原记录安全结束这次授权。采购批准、原应付占用和历史记录保留；再次授权需重新复核。</p>
        <p v-else-if="pending === 'RETRY'">已查询原预留且暂未查到。核对后使用原编号重试，金额、账户与原财务目标保持一致。</p>
        <p v-else>查询同一原预留，核对已经产生的结果。查询不会新建另一笔预留。</p>
        <label>操作说明<textarea v-model="comment" rows="3" maxlength="2000" required :disabled="saving" /></label>
        <div class="supplier-buttons"><button type="button" class="quiet" :disabled="saving" @click="pending = null">返回核对</button><button type="submit" class="primary" :disabled="blocked || !allowed(pending)">{{ saving ? '正在保存…' : '确认并提交' }}</button></div>
      </form>
      <SupplierDisputeStatus v-if="view.authorization" :payment-id="view.authorization.id" :request-id="requestId" :application-id="applicationId" :round-no="roundNo" :scope-key="scopeKey" :locked="locked || saving || loading || settlementBusy || returnBusy || adjustmentBusy" @busy="disputeActivity" @changed="disputeChanged" />
      <SupplierPaymentReturn v-if="view.authorization" :key="returnRevision" :payment-id="view.authorization.id" :request-id="requestId" :application-id="applicationId" :round-no="roundNo" :scope-key="scopeKey" :locked="locked || saving || loading || disputeBusy || settlementBusy || adjustmentBusy" @busy="returnActivity" @changed="returnChanged" />
      <SupplierSettlementStatus v-if="view.authorization" :key="settlementRevision" :payment-id="view.authorization.id" :request-id="requestId" :application-id="applicationId" :round-no="roundNo" :scope-key="scopeKey" :locked="locked || saving || loading || disputeBusy || returnBusy || adjustmentBusy" @busy="settlementActivity" />
      <SupplierAdjustmentStatus v-if="view.authorization" :key="adjustmentRevision" :payment-id="view.authorization.id" :request-id="requestId" :application-id="applicationId" :round-no="roundNo" :scope-key="scopeKey" :locked="locked || saving || loading || disputeBusy || returnBusy || settlementBusy" @busy="adjustmentActivity" @changed="adjustmentChanged" />
    </template>
  </section>
</template>

<style scoped>
.supplier-finance{margin-top:26px;padding:22px;border:1px solid var(--line);border-radius:12px;background:var(--paper);min-width:0}.supplier-heading{display:flex;justify-content:space-between;gap:14px;align-items:center}.supplier-heading h3{font-size:17px;margin:4px 0}.supplier-eyebrow{color:var(--muted);font-size:11px;letter-spacing:.08em;margin:0}.supplier-help{font-size:12px;color:var(--muted);line-height:1.9;overflow-wrap:anywhere}.supplier-overview{display:grid;grid-template-columns:1fr 1fr;gap:20px;border-block:1px solid var(--line);padding:20px 0;margin-top:18px}.supplier-overview div{display:grid;gap:8px}.supplier-overview span{font-size:11px;color:var(--muted)}.supplier-overview strong{font-size:17px;font-variant-numeric:tabular-nums}.supplier-evidence{padding:14px 0;border-bottom:1px solid var(--line)}.supplier-evidence h4,.supplier-confirm h4{font-size:13px;margin:0 0 8px}.supplier-facts{display:flex;flex-wrap:wrap;gap:14px 30px}.supplier-facts p{font-size:11px;color:var(--muted);display:grid;gap:7px;margin:8px 0}.supplier-facts strong{font-size:13px;color:var(--ink);font-variant-numeric:tabular-nums}.supplier-error{padding:12px;background:#fff0ed;color:var(--red);font-size:12px;line-height:1.8;border-radius:7px}.supplier-buttons{display:flex;gap:10px;flex-wrap:wrap;margin-top:16px}.supplier-confirm{margin-top:18px;padding:16px;border:1px solid var(--line);border-radius:8px;font-size:12px;line-height:1.8}.supplier-confirm label{display:grid;gap:8px}.supplier-confirm textarea{width:100%;padding:12px;border:1px solid var(--line);border-radius:7px;resize:vertical;font:inherit}@media(max-width:600px){.supplier-finance{padding:16px}.supplier-heading{align-items:flex-start}.supplier-overview{grid-template-columns:1fr}.supplier-facts{gap:12px 20px}}
</style>
