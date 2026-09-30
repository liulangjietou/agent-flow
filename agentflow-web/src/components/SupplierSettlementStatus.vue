<script setup lang="ts">
import { computed, nextTick, onUnmounted, ref, watch } from 'vue'
import { api, writeRequests } from '../api'
import SupplierSettlementDisputeStatus from './SupplierSettlementDisputeStatus.vue'
import { paymentOperationLabels, supplierSettlementLabels, settlementPreparationLabels, settlementActionLabels, settlementRejectionLabels, settlementIssueLabels, validateSupplierSettlement, supplierSettlementAllowed, supplierSettlementPreparationInput, supplierSettlementActionInput, validateSupplierSettlementReceipt, supplierSettlementError, type SupplierSettlementAction, type SupplierSettlementView } from '../supplierSettlement'

const props = defineProps<{ paymentId: string; requestId: string; applicationId: string; roundNo: number; scopeKey: string; locked?: boolean }>()
const emit = defineEmits<{ busy: [value: boolean] }>()
const view = ref<SupplierSettlementView | null>(null), loading = ref(false), saving = ref(false), unconfirmed = ref(false), requiresRefresh = ref(false)
const disputeId = ref<string | null>(null), disputeBusy = ref(false)
const error = ref(''), notice = ref(''), comment = ref(''), accountingDate = ref(''), beforeId = ref<string | undefined>()
const pending = ref<{ action: 'PREPARE' | SupplierSettlementAction; id?: string } | null>(null), form = ref<HTMLFormElement | null>(null)
let epoch = 0, controller: AbortController | null = null
const blocked = computed(() => !!props.locked || loading.value || saving.value || disputeBusy.value || unconfirmed.value || requiresRefresh.value)
const active = computed(() => view.value?.items.find(item => item.id === view.value?.activeSettlementId))
const selected = computed(() => view.value?.items.find(item => item.id === pending.value?.id))
function syncPending() {
  const active = writeRequests.pending().some(entry => entry.path.startsWith('/supplier-payments/') || entry.path.startsWith('/supplier-settlements/') || entry.path.startsWith(`/procurement-payments/${encodeURIComponent(props.requestId)}/supplier-payment/`))
  if (unconfirmed.value && !active) requiresRefresh.value = true
  unconfirmed.value = active
}
const unsubscribe = writeRequests.subscribe(syncPending)
function disputeActivity(value: boolean) { disputeBusy.value = value; emit('busy', saving.value || value) }
function openDispute(id: string) { if (!blocked.value && !pending.value) disputeId.value = disputeId.value === id ? null : id }
function stop() { epoch++; controller?.abort(); controller = null }
/** 历史整页替换，避免把旧权限或旧银行版本拼接到当前结算记录。 */
async function load(cursor?: string) {
  if (saving.value || disputeBusy.value || !props.scopeKey) return
  stop(); const current = epoch, request = new AbortController(); controller = request; loading.value = true
  const binding = { paymentId: props.paymentId, requestId: props.requestId, applicationId: props.applicationId, roundNo: props.roundNo }
  view.value = null; pending.value = null; comment.value = ''; accountingDate.value = ''; error.value = ''; beforeId.value = cursor
  const timeout = setTimeout(() => { if (current === epoch) { stop(); loading.value = false; error.value = '结算状态读取超时，请重新查询。' } }, 12_000)
  try {
    const result = validateSupplierSettlement(await api.supplierSettlements(binding.paymentId, cursor, request.signal), binding)
    if (current !== epoch) return
    if (cursor && (result.nextBeforeId === cursor || result.items.some(item => item.id === cursor))) throw new Error('历史游标未前进，请返回最新状态。')
    view.value = result; requiresRefresh.value = false; syncPending()
  } catch (cause) { if (current === epoch) error.value = supplierSettlementError(cause) }
  finally { clearTimeout(timeout); if (current === epoch) { loading.value = false; controller = null } }
}
function allowed(action: 'PREPARE' | SupplierSettlementAction, id?: string) {
  return action === 'PREPARE' ? !!view.value?.canPrepare && beforeId.value === undefined : supplierSettlementAllowed(view.value, id ?? '', action)
}
function prepare(action: 'PREPARE' | SupplierSettlementAction, id?: string) {
  if (blocked.value || !allowed(action, id)) return
  pending.value = { action, id }; comment.value = ''; accountingDate.value = ''; error.value = ''; notice.value = ''; const current = epoch
  void nextTick(() => { if (current === epoch) form.value?.querySelector<HTMLInputElement | HTMLTextAreaElement>('input, textarea')?.focus() })
}
async function execute() {
  const value = view.value, intent = pending.value
  if (!value || !intent || blocked.value || !allowed(intent.action, intent.id)) return
  let input
  try { input = intent.action === 'PREPARE' ? supplierSettlementPreparationInput(value, accountingDate.value, comment.value) : supplierSettlementActionInput(value, intent.id!, intent.action, comment.value) }
  catch (cause) { error.value = supplierSettlementError(cause); return }
  const current = epoch; saving.value = true; emit('busy', true); error.value = ''
  try {
    const receipt = 'accountingDate' in input ? await api.prepareSupplierSettlement(value.paymentId, input) : await api.supplierSettlementAction(intent.id!, input)
    if (current !== epoch) return
    validateSupplierSettlementReceipt(receipt, value, intent.action, intent.id)
    saving.value = false; pending.value = null; emit('busy', false)
    notice.value = intent.action === 'PREPARE' ? '结算准备已保存，请刷新核对 ERP 核销与本地完成结果。' : intent.action === 'RETIRE' ? '本次核销已安全结束；原银行付款和历史保留。重新登记时请明确选择会计日期。' : '原核销办理已登记，请刷新核对结果。'
    await load()
  } catch (cause) { if (current === epoch) { requiresRefresh.value = true; error.value = supplierSettlementError(cause) } }
  finally { if (current === epoch) { saving.value = false; syncPending(); emit('busy', false) } }
}
watch(() => JSON.stringify([props.scopeKey, props.paymentId, props.requestId, props.applicationId, props.roundNo]), () => {
  stop(); view.value = null; loading.value = false; saving.value = false; disputeBusy.value = false; disputeId.value = null; requiresRefresh.value = false; beforeId.value = undefined; pending.value = null; comment.value = ''; accountingDate.value = ''; error.value = ''; notice.value = ''
  syncPending(); emit('busy', false); if (props.scopeKey) void load()
}, { immediate: true, flush: 'sync' })
onUnmounted(() => { stop(); unsubscribe(); emit('busy', false) })
const date = (value: string) => new Date(value).toLocaleString('zh-CN')
</script>

<template>
  <section class="supplier-settlement" aria-label="供应商应付结算">
    <div class="settlement-heading"><div><p class="settlement-eyebrow">到账后 · 独立财务核销</p><h4>原应付结算</h4></div><button class="quiet" type="button" :disabled="loading || saving || disputeBusy || locked" @click="notice = ''; load()">{{ beforeId ? '返回最新状态' : '刷新结算状态' }}</button></div>
    <p class="settlement-help">银行到账后，由财务选择会计日期。ERP 核销和本地应付完成分别确认。</p>
    <p v-if="loading" role="status" class="settlement-help">正在读取原付款与结算记录…</p>
    <p v-if="error" role="alert" class="settlement-error">{{ error }}</p><p v-if="notice" role="status" class="settlement-help">{{ notice }}</p>
    <p v-if="unconfirmed && !saving" role="alert" class="settlement-error">上次办理结果未确认，请在未确认操作中恢复原请求后刷新。</p>
    <p v-if="requiresRefresh && !unconfirmed && !error" role="status" class="settlement-help">请刷新，核对恢复后的原核销。</p>
    <template v-if="view">
      <div class="settlement-stages">
        <div><span>银行付款</span><strong>{{ view.bank ? paymentOperationLabels[view.bank.status] : '尚未登记银行付款' }}</strong><small v-if="view.bank?.disputed">银行回执存在争议</small></div>
        <div><span>ERP 核销</span><strong>{{ active ? supplierSettlementLabels[active.status] : view.activeSettlementId ? '原核销在其他历史页' : view.preparation ? settlementPreparationLabels[view.preparation.status] : '尚未登记结算' }}</strong><small v-if="active?.disputed">核销事实存在争议</small></div>
        <div><span>本地应付完成</span><strong>{{ view.completion ? '已登记完成凭据' : '尚未完成' }}</strong><small v-if="view.completion">{{ date(view.completion.completedAt) }}</small></div>
      </div>
      <p class="settlement-help">{{ view.supplierName }} · {{ view.amount.currency }} {{ view.amount.value }} · 收款账户 {{ view.maskedPayeeAccount }}</p>
      <p v-if="view.bank?.receiptReference" class="settlement-help">原银行回单 {{ view.bank.receiptReference }}</p>
      <p v-if="view.completion && (view.bank?.status !== 'SUCCEEDED' || active && active.status !== 'SETTLED')" role="status" class="settlement-warning">历史完成凭据保留；当前银行或 ERP 结果仍需核对。</p>
      <div v-if="view.preparation" class="settlement-preparation"><strong>{{ settlementPreparationLabels[view.preparation.status] }}</strong><span>会计日期 {{ view.preparation.accountingDate }} · {{ view.preparation.financeActor }}</span><p v-if="view.preparation.issue" class="settlement-help">{{ settlementIssueLabels[view.preparation.issue] }}</p></div>
      <button v-if="!pending && allowed('PREPARE')" type="button" class="primary" :disabled="blocked" @click="prepare('PREPARE')">登记应付结算</button>
      <p v-if="beforeId" class="settlement-help">当前查看较早的核销记录，返回最新状态可查看当前办理进度。</p>
      <ol v-if="view.items.length" class="settlement-history" aria-label="原付款核销历史">
        <li v-for="item in view.items" :key="item.id" class="settlement-record">
          <div class="settlement-record-heading"><strong>{{ item.retirement ? '已安全结束' : supplierSettlementLabels[item.status] }}</strong><span>{{ item.accountingDate }} · {{ item.financeActor }}</span></div>
          <p class="settlement-help">会计期间 {{ item.periodReference }} · {{ date(item.updatedAt) }}</p>
          <p v-if="item.posting" class="settlement-help">ERP 核销号 {{ item.posting.settlementReference }}<br />付款凭证 {{ item.posting.voucherReference }} · {{ item.posting.amount.currency }} {{ item.posting.amount.value }}</p>
          <p v-if="item.rejection" class="settlement-warning">{{ settlementRejectionLabels[item.rejection] }}</p><p v-if="item.issue" class="settlement-help">{{ settlementIssueLabels[item.issue] }}</p>
          <p v-if="item.retirement" class="settlement-help">{{ item.retirement.basis === 'NEVER_DISPATCHED' ? '确认从未外发核销' : 'ERP 已明确拒绝本次核销' }} · {{ item.retirement.retiredBy }} · {{ date(item.retirement.retiredAt) }}</p>
          <div v-if="!pending" class="settlement-buttons"><button v-for="action in (['QUERY', 'RETRY', 'RETIRE'] as const)" v-show="allowed(action, item.id)" :key="action" type="button" class="quiet" :disabled="blocked" @click="prepare(action, item.id)">{{ settlementActionLabels[action] }}</button></div>
          <button v-if="item.dispatches > 0 && !pending" type="button" class="quiet settlement-dispute-toggle" :disabled="blocked" @click="openDispute(item.id)">{{ disputeId === item.id ? '收起核销回执与裁决' : '核对核销回执与裁决' }}</button>
          <SupplierSettlementDisputeStatus v-if="disputeId === item.id" :settlement-id="item.id" :payment-id="paymentId" :request-id="requestId" :application-id="applicationId" :round-no="roundNo" :amount="view.amount" :scope-key="scopeKey + ':' + item.version" :locked="locked || saving || loading || !!pending || requiresRefresh || unconfirmed" @busy="disputeActivity" @changed="load(beforeId)" />
        </li>
      </ol>
      <button v-if="view.nextBeforeId && !pending" class="quiet" type="button" :disabled="loading || saving || disputeBusy || locked" @click="load(view.nextBeforeId)">查看更早核销</button>
      <form v-if="pending" ref="form" class="settlement-confirm" @submit.prevent="execute">
        <h4>{{ settlementActionLabels[pending.action] }}</h4>
        <p>确认供应商 {{ view.supplierName }}，金额 {{ view.amount.currency }} {{ view.amount.value }}，收款账户 {{ view.maskedPayeeAccount }}。</p>
        <template v-if="pending.action === 'PREPARE'"><label>本次会计日期<input v-model="accountingDate" type="date" :min="view.minimumAccountingDate ?? undefined" required :disabled="saving" /></label><p class="settlement-help">不能早于 {{ view.minimumAccountingDate }}（原法人当地到账日期）。保存后读取所选会计期间，不会自动改换日期。</p></template>
        <p v-else-if="pending.action === 'RETRY'">原核销暂未查到。确认按原编号、原会计日期 {{ selected?.accountingDate }} 重试，金额、付款和财务目标保持一致。</p>
        <p v-else-if="pending.action === 'RETIRE'">根据从未外发或 ERP 明确拒绝的依据结束本次尝试。原银行付款、原应付和历史记录保留。</p>
        <p v-else>查询同一原核销的实际结果，不会重新发送结算。</p>
        <label>操作说明<textarea v-model="comment" rows="3" maxlength="2000" required :disabled="saving" /></label>
        <div class="settlement-buttons"><button type="button" class="quiet" :disabled="saving" @click="pending = null; comment = ''; accountingDate = ''">返回核对</button><button type="submit" class="primary" :disabled="blocked || !allowed(pending.action, pending.id)">{{ saving ? '正在保存…' : '确认并提交' }}</button></div>
      </form>
    </template>
  </section>
</template>

<style scoped>
.settlement-dispute-toggle{margin-top:12px}
.supplier-settlement{margin-top:24px;padding-top:22px;border-top:1px solid var(--line);min-width:0}.settlement-heading,.settlement-record-heading{display:flex;justify-content:space-between;gap:14px;align-items:center}.settlement-heading h4{font-size:16px;margin:4px 0}.settlement-eyebrow{font-size:11px;color:var(--muted);margin:0}.settlement-help{font-size:12px;line-height:1.9;color:var(--muted);overflow-wrap:anywhere}.settlement-stages{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:16px;padding:18px 0;border-bottom:1px solid var(--line)}.settlement-stages>div{display:flex;flex-direction:column;gap:8px}.settlement-stages span,.settlement-stages small{font-size:11px;color:var(--muted);line-height:1.7}.settlement-stages strong{font-size:13px;line-height:1.7}.settlement-stages small{overflow-wrap:anywhere}.settlement-preparation{display:grid;gap:8px;padding:12px 0;font-size:12px}.settlement-preparation span,.settlement-record-heading span{font-size:11px;color:var(--muted)}.settlement-history{list-style:none;padding:0;margin:16px 0}.settlement-record{padding:16px 0;border-bottom:1px solid var(--line);font-size:12px;overflow-wrap:anywhere}.settlement-buttons{display:flex;flex-wrap:wrap;gap:10px;margin-top:14px}.settlement-error,.settlement-warning{padding:12px;border-radius:7px;font-size:12px;line-height:1.8;background:#fff0ed;color:var(--red)}.settlement-warning{background:var(--paper);color:var(--ink);border-left:3px solid var(--red)}.settlement-confirm{padding:16px;margin-top:18px;border:1px solid var(--line);border-radius:8px;font-size:12px;line-height:1.9}.settlement-confirm h4{margin:0 0 8px;font-size:13px}.settlement-confirm label{display:grid;gap:8px}.settlement-confirm input,.settlement-confirm textarea{width:100%;box-sizing:border-box;padding:10px;border:1px solid var(--line);border-radius:6px;font:inherit;color:var(--ink);background:var(--paper)}.settlement-confirm input{max-width:250px}.settlement-confirm textarea{resize:vertical}@media(max-width:650px){.settlement-stages{grid-template-columns:1fr}.settlement-heading,.settlement-record-heading{align-items:flex-start;flex-wrap:wrap}}
</style>
