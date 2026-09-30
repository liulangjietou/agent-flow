<script setup lang="ts">
import { computed, nextTick, onUnmounted, ref, watch } from 'vue'
import { api, writeRequests } from '../api'
import { adjustmentLabels, adjustmentRejectionLabels, adjustmentDisputeOutcomes, adjustmentDisputeIssues, validateAdjustmentDispute, adjustmentDisputeAllowed, adjustmentDisputeInput, validateAdjustmentDisputeReceipt, adjustmentDisputeError, type AdjustmentDisputeView } from '../supplierAdjustmentDispute'

const props = defineProps<{ adjustmentId: string; paymentId: string; requestId: string; applicationId: string; roundNo: number; amount: { value: string; currency: string }; returnedAmount: { value: string; currency: string }; totalReturned: { value: string; currency: string }; netPaid: { value: string; currency: string }; recognizesOriginalPayment: boolean; scopeKey: string; locked?: boolean }>()
const emit = defineEmits<{ busy: [value: boolean]; changed: [] }>()
const view = ref<AdjustmentDisputeView | null>(null), loading = ref(false), saving = ref(false), unconfirmed = ref(false), requiresRefresh = ref(false), pending = ref(false)
const comment = ref(''), evidenceReference = ref(''), error = ref(''), notice = ref(''), form = ref<HTMLFormElement | null>(null)
let epoch = 0, controller: AbortController | null = null
const now = ref(Date.now()), ticker = setInterval(() => { now.value = Date.now() }, 1000)
const blocked = computed(() => !!props.locked || loading.value || saving.value || unconfirmed.value || requiresRefresh.value)
const facts = computed(() => [{ label: '原 ERP 事实', value: view.value?.observed }, { label: '本次待裁决回执', value: view.value?.candidate }])
function syncPending() {
  const active = writeRequests.pending().some(entry => entry.path.startsWith('/supplier-payments/') || entry.path.startsWith('/supplier-settlements/') || entry.path.startsWith('/supplier-adjustments/') || entry.path.startsWith(`/procurement-payments/${encodeURIComponent(props.requestId)}/supplier-payment/`))
  if (unconfirmed.value && !active) requiresRefresh.value = true
  unconfirmed.value = active
}
const unsubscribe = writeRequests.subscribe(syncPending)
function stop() { epoch++; controller?.abort(); controller = null }
/** 身份与调整版本变化时整体替换，迟到响应不能恢复旧权限或候选。 */
async function load() {
  if (saving.value || !props.scopeKey) return
  stop(); const current = epoch, request = new AbortController(); controller = request; loading.value = true
  const binding = { adjustmentId: props.adjustmentId, paymentId: props.paymentId, requestId: props.requestId, applicationId: props.applicationId, roundNo: props.roundNo, amount: { ...props.amount }, returnedAmount: { ...props.returnedAmount }, totalReturned: { ...props.totalReturned }, netPaid: { ...props.netPaid }, recognizesOriginalPayment: props.recognizesOriginalPayment }
  view.value = null; pending.value = false; comment.value = ''; evidenceReference.value = ''; error.value = ''
  const timeout = setTimeout(() => { if (current === epoch) { stop(); loading.value = false; error.value = '原调整争议读取超时，请重新读取。' } }, 12_000)
  try {
    const response = await api.supplierAdjustmentDispute(binding.adjustmentId, request.signal)
    if (current !== epoch) return
    view.value = validateAdjustmentDispute(response, binding); requiresRefresh.value = false; syncPending()
  } catch (cause) { if (current === epoch) error.value = adjustmentDisputeError(cause) }
  finally { clearTimeout(timeout); if (current === epoch) { loading.value = false; controller = null } }
}
function allowed() { return adjustmentDisputeAllowed(view.value, now.value) }
function prepare() {
  if (blocked.value || !allowed()) return
  pending.value = true; comment.value = ''; evidenceReference.value = ''; error.value = ''; notice.value = ''; const current = epoch
  void nextTick(() => { if (current === epoch) form.value?.querySelector<HTMLInputElement>('input')?.focus() })
}
async function execute() {
  const value = view.value
  if (!value || !pending.value || blocked.value || !allowed()) return
  let input
  try { input = adjustmentDisputeInput(value, comment.value, evidenceReference.value) }
  catch (cause) { error.value = adjustmentDisputeError(cause); return }
  const current = epoch; saving.value = true; emit('busy', true); error.value = ''
  try {
    const receipt = await api.resolveSupplierAdjustmentDispute(value.adjustmentId, input)
    if (current !== epoch) return
    validateAdjustmentDisputeReceipt(receipt, value)
    saving.value = false; pending.value = false; emit('busy', false)
    notice.value = '原调整裁决已保存，请核对银行、ERP 和本地完成状态。'
    // 父面板刷新会卸载本面板，完成本次读取后再通知，避免卸载后启动新请求。
    const refresh = load(), refreshedEpoch = epoch
    await refresh; if (epoch === refreshedEpoch && view.value) emit('changed')
  } catch (cause) { if (current === epoch) { requiresRefresh.value = true; error.value = adjustmentDisputeError(cause) } }
  finally { if (current === epoch) { saving.value = false; syncPending(); emit('busy', false) } }
}
watch(() => JSON.stringify([props.scopeKey, props.adjustmentId, props.paymentId, props.requestId, props.applicationId, props.roundNo, props.amount, props.returnedAmount, props.totalReturned, props.netPaid, props.recognizesOriginalPayment]), () => {
  stop(); view.value = null; loading.value = false; saving.value = false; requiresRefresh.value = false; pending.value = false; comment.value = ''; evidenceReference.value = ''; error.value = ''; notice.value = ''
  syncPending(); emit('busy', false); if (props.scopeKey) void load()
}, { immediate: true, flush: 'sync' })
onUnmounted(() => { stop(); clearInterval(ticker); unsubscribe(); emit('busy', false) })
const date = (value: string) => new Date(value).toLocaleString('zh-CN')
</script>

<template>
  <section class="erp-dispute" aria-label="原 ERP 调整裁决">
    <div class="erp-heading"><h5>核对原 ERP 回执</h5><button class="quiet" type="button" :disabled="loading || saving || locked" @click="notice = ''; load()">重新读取裁决状态</button></div>
    <p class="erp-help">需要新回执时，请使用上方「查询原调整」。财务核对原付款凭证、每笔入款分录和前后余额后明确裁决。</p>
    <p v-if="loading" role="status" class="erp-help">正在读取原调整回执…</p>
    <p v-if="error" role="alert" class="erp-warning">{{ error }}</p><p v-if="notice" role="status" class="erp-help">{{ notice }}</p>
    <p v-if="unconfirmed && !saving" role="alert" class="erp-warning">上次裁决结果未确认，请恢复原请求后刷新结算状态。</p>
    <p v-if="requiresRefresh && !unconfirmed && !error" role="status" class="erp-help">请重新读取，核对恢复后的原裁决。</p>
    <template v-if="view">
      <strong>{{ adjustmentLabels[view.status] }}</strong>
      <div class="erp-facts"><div v-for="fact in facts" :key="fact.label"><span>{{ fact.label }}</span><template v-if="fact.value"><strong>{{ adjustmentDisputeOutcomes[fact.value.outcome] }}</strong>
        <dl><template v-if="fact.value.posting"><dt>ERP 调整号</dt><dd>{{ fact.value.posting.adjustmentReference }}</dd><dt>原付款凭证</dt><dd>{{ fact.value.posting.recognitionVoucherReference }}</dd><dt>本次回款调整</dt><dd>{{ fact.value.posting.returnedAmount.currency }} {{ fact.value.posting.returnedAmount.value }}</dd><dt>原应付累计已付</dt><dd>{{ fact.value.posting.payableSettledBefore.value }} → {{ fact.value.posting.payableSettledAfter.value }}</dd><dt>会计日期与期间</dt><dd>{{ fact.value.posting.accountingDate }} · {{ fact.value.posting.periodReference }}</dd>
          <template v-for="entry in fact.value.posting.entries" :key="entry.transactionReference"><dt>实际入款与调整分录</dt><dd>{{ entry.amount.currency }} {{ entry.amount.value }} · {{ entry.transactionReference }}<br />{{ entry.voucherReference }} · {{ entry.entryReference }}</dd></template>
        </template><dt>核对时间</dt><dd>{{ date(fact.value.observedAt) }}</dd></dl>
        <p v-if="fact.value.rejection" class="erp-help">{{ adjustmentRejectionLabels[fact.value.rejection] }}</p></template><strong v-else>暂无回执</strong></div></div>
      <p v-if="view.issue" role="status" class="erp-warning">{{ adjustmentDisputeIssues[view.issue] }}</p>
      <p v-else-if="view.candidate && Date.parse(view.candidate.validUntil) <= now" role="status" class="erp-warning">核对依据已到期，请查询原调整。</p>
      <p v-if="view.latest" class="erp-help">最近裁决：{{ adjustmentDisputeOutcomes[view.latest.outcome] }} · {{ view.latest.resolvedBy }} · {{ date(view.latest.resolvedAt) }}<br />对账凭据 {{ view.latest.evidenceReference }}</p>
      <button v-if="!pending && allowed()" class="quiet" type="button" :disabled="blocked" @click="prepare">确认原调整裁决</button>
      <form v-if="pending && view.candidate" ref="form" class="erp-confirm" @submit.prevent="execute">
        <h5>确认采用原调整终态</h5><p>采用「{{ adjustmentDisputeOutcomes[view.candidate.outcome] }}」<span v-if="view.candidate.posting">，付款凭证 {{ view.candidate.posting.recognitionVoucherReference }}</span>。</p>
        <p class="erp-help">核对依据有效至 {{ date(view.candidate.validUntil) }}。原银行结果和历史完成凭据分别保留。</p>
        <label>外部对账凭据编号<input v-model="evidenceReference" maxlength="128" required :disabled="saving" autocomplete="off" /></label>
        <label>裁决说明<textarea v-model="comment" rows="3" maxlength="2000" required :disabled="saving" /></label>
        <div class="erp-buttons"><button class="quiet" type="button" :disabled="saving" @click="pending = false; comment = ''; evidenceReference = ''">返回核对</button><button class="primary" type="submit" :disabled="blocked || !allowed()">{{ saving ? '正在保存…' : '确认并保存裁决' }}</button></div>
      </form>
    </template>
  </section>
</template>

<style scoped>
.erp-dispute{margin-top:18px;padding:16px;background:var(--paper);border:1px solid var(--line);border-radius:8px;min-width:0}.erp-heading{display:flex;justify-content:space-between;align-items:center;gap:12px}.erp-heading h5,.erp-confirm h5{font-size:13px;margin:0}.erp-help{font-size:12px;color:var(--muted);line-height:1.9;overflow-wrap:anywhere}.erp-facts{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:20px;padding:16px 0}.erp-facts>div{display:flex;flex-direction:column;gap:8px;min-width:0}.erp-facts span,.erp-facts dt{font-size:11px;color:var(--muted)}.erp-facts strong{font-size:13px}.erp-facts dl{display:grid;gap:6px;margin:4px 0}.erp-facts dd{margin:0 0 6px;font-size:12px;line-height:1.7;overflow-wrap:anywhere}.erp-warning{padding:12px;border-left:3px solid var(--red);line-height:1.8;font-size:12px;overflow-wrap:anywhere}.erp-confirm{border-top:1px solid var(--line);padding-top:16px;margin-top:16px;font-size:12px;line-height:1.9}.erp-confirm label{display:grid;gap:8px;margin-top:12px}.erp-confirm input,.erp-confirm textarea{width:100%;box-sizing:border-box;padding:10px;border:1px solid var(--line);border-radius:6px;font:inherit;color:var(--ink);background:var(--paper)}.erp-confirm textarea{resize:vertical}.erp-buttons{display:flex;flex-wrap:wrap;gap:10px;margin-top:14px}@media(max-width:650px){.erp-facts{grid-template-columns:1fr}.erp-heading{flex-wrap:wrap;align-items:flex-start}}
</style>
