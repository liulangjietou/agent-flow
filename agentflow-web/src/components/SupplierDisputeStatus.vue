<script setup lang="ts">
import { computed, nextTick, onUnmounted, ref, watch } from 'vue'
import { api, writeRequests } from '../api'
import { paymentOperationLabels, paymentFailureLabels, supplierBankOutcomes, supplierDisputeIssues, supplierDisputeActions, validateSupplierDispute, supplierDisputeAllowed, supplierDisputeInput, validateSupplierDisputeReceipt, supplierDisputeError, type SupplierDisputeAction, type SupplierDisputeView } from '../supplierDispute'

const props = defineProps<{ paymentId: string; requestId: string; applicationId: string; roundNo: number; scopeKey: string; locked?: boolean }>()
const emit = defineEmits<{ busy: [value: boolean]; changed: [] }>()
const view = ref<SupplierDisputeView | null>(null), loading = ref(false), saving = ref(false), unconfirmed = ref(false), requiresRefresh = ref(false)
const pending = ref<SupplierDisputeAction | null>(null), comment = ref(''), evidenceReference = ref(''), error = ref(''), notice = ref(''), form = ref<HTMLFormElement | null>(null)
let epoch = 0, controller: AbortController | null = null
const now = ref(Date.now()), ticker = setInterval(() => { now.value = Date.now() }, 1000)
const blocked = computed(() => !!props.locked || loading.value || saving.value || unconfirmed.value || requiresRefresh.value)
const facts = computed(() => [{ label: '原银行事实', value: view.value?.observed }, { label: '本次待裁决回执', value: view.value?.candidate }])
function syncPending() {
  const active = writeRequests.pending().some(entry => entry.path.startsWith('/supplier-payments/') || entry.path.startsWith('/supplier-settlements/') || entry.path.startsWith(`/procurement-payments/${encodeURIComponent(props.requestId)}/supplier-payment/`))
  if (unconfirmed.value && !active) requiresRefresh.value = true
  unconfirmed.value = active
}
const unsubscribe = writeRequests.subscribe(syncPending)
function stop() { epoch++; controller?.abort(); controller = null }
/** 整体替换当前投影；权限或原付款变化后，迟到响应不能恢复旧事实。 */
async function load() {
  if (saving.value || !props.scopeKey) return
  stop(); const current = epoch, request = new AbortController(); controller = request; loading.value = true
  const binding = { paymentId: props.paymentId, requestId: props.requestId, applicationId: props.applicationId, roundNo: props.roundNo }
  view.value = null; pending.value = null; comment.value = ''; evidenceReference.value = ''; error.value = ''
  const timeout = setTimeout(() => { if (current === epoch) { stop(); loading.value = false; error.value = '原付款裁决读取超时，请重新查询。' } }, 12_000)
  try {
    const result = await api.supplierDispute(binding.paymentId, request.signal)
    if (current !== epoch) return
    view.value = validateSupplierDispute(result, binding); requiresRefresh.value = false; syncPending()
  } catch (cause) { if (current === epoch) error.value = supplierDisputeError(cause) }
  finally { clearTimeout(timeout); if (current === epoch) { loading.value = false; controller = null } }
}
function allowed(action: SupplierDisputeAction) { return supplierDisputeAllowed(view.value, action, now.value) }
function prepare(action: SupplierDisputeAction) {
  if (blocked.value || !allowed(action)) return
  pending.value = action; comment.value = ''; evidenceReference.value = ''; error.value = ''; notice.value = ''; const current = epoch
  void nextTick(() => { if (current === epoch) form.value?.querySelector<HTMLInputElement | HTMLTextAreaElement>('input, textarea')?.focus() })
}
async function execute() {
  const value = view.value, action = pending.value
  if (!value || !action || blocked.value || !allowed(action)) return
  let input
  try { input = supplierDisputeInput(value, action, comment.value, evidenceReference.value) }
  catch (cause) { error.value = supplierDisputeError(cause); return }
  const current = epoch; saving.value = true; emit('busy', true); error.value = ''
  try {
    const receipt = 'outcome' in input ? await api.resolveSupplierDispute(value.paymentId, input) : await api.querySupplierDispute(value.paymentId, input)
    if (current !== epoch) return
    validateSupplierDisputeReceipt(receipt, value, action)
    saving.value = false; pending.value = null; emit('busy', false); emit('changed')
    notice.value = action === 'QUERY' ? '原银行查询已登记，请刷新核对回执。' : '原付款裁决已保存，请核对银行和应付结算状态。'
    await load()
  } catch (cause) { if (current === epoch) { requiresRefresh.value = true; error.value = supplierDisputeError(cause) } }
  finally { if (current === epoch) { saving.value = false; syncPending(); emit('busy', false) } }
}
watch(() => JSON.stringify([props.scopeKey, props.paymentId, props.requestId, props.applicationId, props.roundNo]), () => {
  stop(); view.value = null; loading.value = false; saving.value = false; requiresRefresh.value = false; pending.value = null; comment.value = ''; evidenceReference.value = ''; error.value = ''; notice.value = ''
  syncPending(); emit('busy', false); if (props.scopeKey) void load()
}, { immediate: true, flush: 'sync' })
onUnmounted(() => { stop(); clearInterval(ticker); unsubscribe(); emit('busy', false) })
const date = (value: string) => new Date(value).toLocaleString('zh-CN')
</script>

<template>
  <section class="supplier-dispute" aria-label="供应商原付款裁决">
    <div class="dispute-heading"><div><p class="dispute-eyebrow">原银行交易 · 独立财务核对</p><h4>原付款争议</h4></div><button type="button" class="quiet" :disabled="loading || saving || locked" @click="notice = ''; load()">刷新原付款</button></div>
    <p class="dispute-help">裁决采用银行近期回执，并保留原付款和既有核销记录。退回后的资金及账务调整分别办理。</p>
    <p v-if="loading" role="status" class="dispute-help">正在读取原银行事实与历史裁决…</p>
    <p v-if="error" role="alert" class="dispute-warning">{{ error }}</p><p v-if="notice" role="status" class="dispute-help">{{ notice }}</p>
    <p v-if="unconfirmed && !saving" role="alert" class="dispute-warning">上次办理结果未确认，请在未确认操作中恢复原请求后刷新。</p>
    <p v-if="requiresRefresh && !unconfirmed && !error" role="status" class="dispute-help">请刷新核对恢复后的原银行状态。</p>
    <template v-if="view">
      <p class="dispute-state">{{ view.status ? paymentOperationLabels[view.status] : '尚未登记银行付款' }}</p>
      <div v-if="view.observed || view.candidate" class="dispute-facts">
        <div v-for="fact in facts" :key="fact.label"><span>{{ fact.label }}</span><template v-if="fact.value"><strong>{{ supplierBankOutcomes[fact.value.outcome] }}</strong><dl><dt>银行交易号</dt><dd>{{ fact.value.paymentReference || '未返回' }}</dd><dt>银行回单</dt><dd>{{ fact.value.receiptReference || '未返回' }}</dd><template v-if="fact.value.completedAt"><dt>银行完成时间</dt><dd>{{ date(fact.value.completedAt) }}</dd></template><dt>核对时间</dt><dd>{{ date(fact.value.observedAt) }}</dd></dl><p v-if="fact.value.failure" class="dispute-help">{{ paymentFailureLabels[fact.value.failure] }}</p></template><strong v-else>暂无回执</strong></div>
      </div>
      <p v-if="view.issue" role="status" class="dispute-warning">{{ supplierDisputeIssues[view.issue] }}</p>
      <p v-else-if="view.candidate && Date.parse(view.candidate.validUntil) <= now" role="status" class="dispute-warning">当前核对依据已到期，请查询原银行交易。</p>
      <p v-if="view.latest" class="dispute-help">最近裁决：{{ supplierBankOutcomes[view.latest.outcome] }} · {{ view.latest.resolvedBy }} · {{ date(view.latest.resolvedAt) }}<br />对账凭据 {{ view.latest.evidenceReference }}</p>
      <div v-if="!pending" class="dispute-buttons"><button v-for="action in (['QUERY', 'RESOLVE'] as const)" v-show="allowed(action)" :key="action" type="button" class="quiet" :disabled="blocked" @click="prepare(action)">{{ supplierDisputeActions[action] }}</button></div>
      <form v-if="pending" ref="form" class="dispute-confirm" @submit.prevent="execute">
        <h4>{{ supplierDisputeActions[pending] }}</h4>
        <template v-if="pending === 'RESOLVE' && view.candidate"><p>确认采用「{{ supplierBankOutcomes[view.candidate.outcome] }}」，银行交易号 {{ view.candidate.paymentReference }}<span v-if="view.candidate.receiptReference">，回单 {{ view.candidate.receiptReference }}</span>。</p><p class="dispute-help">核对依据有效至 {{ date(view.candidate.validUntil) }}。</p><label>外部对账凭据编号<input v-model="evidenceReference" maxlength="128" required :disabled="saving" autocomplete="off" /></label></template>
        <p v-else>查询同一原银行交易的实际处理结果。</p>
        <label>操作说明<textarea v-model="comment" rows="3" maxlength="2000" required :disabled="saving" /></label>
        <div class="dispute-buttons"><button type="button" class="quiet" :disabled="saving" @click="pending = null; comment = ''; evidenceReference = ''">返回核对</button><button type="submit" class="primary" :disabled="blocked || !allowed(pending)">{{ saving ? '正在保存…' : '确认并提交' }}</button></div>
      </form>
    </template>
  </section>
</template>

<style scoped>
.supplier-dispute{margin-top:24px;padding-top:22px;border-top:1px solid var(--line);min-width:0}.dispute-heading{display:flex;justify-content:space-between;align-items:center;gap:14px}.dispute-heading h4{font-size:16px;margin:4px 0}.dispute-eyebrow{font-size:11px;color:var(--muted);margin:0}.dispute-help{font-size:12px;line-height:1.9;color:var(--muted);overflow-wrap:anywhere}.dispute-state{font-size:13px;font-weight:600}.dispute-facts{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:20px;padding:16px 0}.dispute-facts>div{display:flex;flex-direction:column;gap:8px;min-width:0}.dispute-facts span,.dispute-facts dt{font-size:11px;color:var(--muted)}.dispute-facts strong{font-size:13px}.dispute-facts dl{display:grid;gap:6px;margin:4px 0}.dispute-facts dd{font-size:12px;margin:0 0 6px;line-height:1.7;overflow-wrap:anywhere}.dispute-warning{padding:12px;background:var(--paper);border-left:3px solid var(--red);font-size:12px;line-height:1.8;overflow-wrap:anywhere}.dispute-buttons{display:flex;flex-wrap:wrap;gap:10px;margin-top:14px}.dispute-confirm{padding:16px;margin-top:18px;border:1px solid var(--line);border-radius:8px;font-size:12px;line-height:1.9;overflow-wrap:anywhere}.dispute-confirm h4{margin:0 0 8px;font-size:13px}.dispute-confirm label{display:grid;gap:8px;margin-top:12px}.dispute-confirm input,.dispute-confirm textarea{width:100%;box-sizing:border-box;padding:10px;border:1px solid var(--line);border-radius:6px;font:inherit;color:var(--ink);background:var(--paper)}.dispute-confirm textarea{resize:vertical}@media(max-width:650px){.dispute-facts{grid-template-columns:1fr}.dispute-heading{align-items:flex-start;flex-wrap:wrap}}
</style>
