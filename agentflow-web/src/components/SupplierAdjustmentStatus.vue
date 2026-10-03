<script setup lang="ts">
import { computed, nextTick, onUnmounted, ref, watch } from 'vue'
import { api, writeRequests } from '../api'
import SupplierAdjustmentDisputeStatus from './SupplierAdjustmentDisputeStatus.vue'
import { paymentOperationLabels, adjustmentLabels, adjustmentPreparationLabels, adjustmentActionLabels, adjustmentRejectionLabels, adjustmentIssueLabels, validateSupplierAdjustment, supplierAdjustmentAllowed, supplierAdjustmentPreparationInput, supplierAdjustmentActionInput, validateSupplierAdjustmentReceipt, supplierAdjustmentError, type SupplierAdjustmentAction, type SupplierAdjustmentView } from '../supplierAdjustment'

const props = defineProps<{ paymentId: string; requestId: string; applicationId: string; roundNo: number; scopeKey: string; locked?: boolean }>()
const emit = defineEmits<{ busy: [value: boolean]; changed: [] }>()
const view = ref<SupplierAdjustmentView | null>(null), loading = ref(false), saving = ref(false), unconfirmed = ref(false), requiresRefresh = ref(false)
const disputeId = ref<string | null>(null), disputeBusy = ref(false)
const error = ref(''), notice = ref(''), comment = ref(''), accountingDate = ref(''), beforeId = ref<string | undefined>()
const pending = ref<{ action: 'PREPARE' | SupplierAdjustmentAction; id?: string } | null>(null), form = ref<HTMLFormElement | null>(null)
let epoch = 0, controller: AbortController | null = null
const blocked = computed(() => !!props.locked || loading.value || saving.value || disputeBusy.value || unconfirmed.value || requiresRefresh.value)
const active = computed(() => view.value?.items.find(item => item.id === view.value?.activeAdjustmentId))
const selected = computed(() => view.value?.items.find(item => item.id === pending.value?.id))
function syncPending() {
  const active = writeRequests.pending().some(entry => entry.path.startsWith('/supplier-payments/') || entry.path.startsWith('/supplier-settlements/') || entry.path.startsWith('/supplier-adjustments/') || entry.path.startsWith(`/procurement-payments/${encodeURIComponent(props.requestId)}/supplier-payment/`))
  if (unconfirmed.value && !active) requiresRefresh.value = true
  unconfirmed.value = active
}
const unsubscribe = writeRequests.subscribe(syncPending)
function stop() { epoch++; controller?.abort(); controller = null }
/** 历史整页替换，避免把旧权限或旧银行版本拼接到当前调整记录。 */
async function load(cursor?: string) {
  if (saving.value || disputeBusy.value || !props.scopeKey) return
  const previous = view.value; let changed = false
  stop(); const current = epoch, request = new AbortController(); controller = request; loading.value = true
  const binding = { paymentId: props.paymentId, requestId: props.requestId, applicationId: props.applicationId, roundNo: props.roundNo }
  view.value = null; disputeId.value = null; pending.value = null; comment.value = ''; accountingDate.value = ''; error.value = ''; beforeId.value = cursor
  const timeout = setTimeout(() => { if (current === epoch) { stop(); loading.value = false; error.value = '调整状态读取超时，请重新查询。' } }, 12_000)
  try {
    const result = validateSupplierAdjustment(await api.supplierAdjustments(binding.paymentId, cursor, request.signal), binding)
    if (current !== epoch) return
    if (cursor && (result.nextBeforeId === cursor || result.items.some(item => item.id === cursor))) throw new Error('历史游标未前进，请返回最新状态。')
    view.value = result; requiresRefresh.value = false; syncPending()
    changed = !!previous && (previous.returnVersion !== result.returnVersion || previous.bank?.version !== result.bank?.version)
  } catch (cause) { if (current === epoch) error.value = supplierAdjustmentError(cause) }
  finally { clearTimeout(timeout); if (current === epoch) { loading.value = false; controller = null; if (changed) emit('changed') } }
}
function disputeActivity(value: boolean) { disputeBusy.value = value; emit('busy', saving.value || value) }
function toggleDispute(id: string) { if (!blocked.value && !pending.value) disputeId.value = disputeId.value === id ? null : id }
async function disputeChanged() {
  const refresh = load(), current = epoch
  await refresh; if (epoch === current && view.value) emit('changed')
}
function allowed(action: 'PREPARE' | SupplierAdjustmentAction, id?: string) {
  return action === 'PREPARE' ? !!view.value?.canPrepare && beforeId.value === undefined : supplierAdjustmentAllowed(view.value, id ?? '', action)
}
function prepare(action: 'PREPARE' | SupplierAdjustmentAction, id?: string) {
  if (blocked.value || !allowed(action, id)) return
  pending.value = { action, id }; comment.value = ''; accountingDate.value = ''; error.value = ''; notice.value = ''; const current = epoch
  void nextTick(() => { if (current === epoch) form.value?.querySelector<HTMLInputElement | HTMLTextAreaElement>('input, textarea')?.focus() })
}
async function execute() {
  const value = view.value, intent = pending.value
  if (!value || !intent || blocked.value || !allowed(intent.action, intent.id)) return
  let input
  try { input = intent.action === 'PREPARE' ? supplierAdjustmentPreparationInput(value, accountingDate.value, comment.value) : supplierAdjustmentActionInput(value, intent.id!, intent.action, comment.value) }
  catch (cause) { error.value = supplierAdjustmentError(cause); return }
  const current = epoch; saving.value = true; emit('busy', true); error.value = ''
  try {
    const receipt = 'accountingDate' in input ? await api.prepareSupplierAdjustment(value.paymentId, input) : await api.supplierAdjustmentAction(intent.id!, input)
    if (current !== epoch) return
    validateSupplierAdjustmentReceipt(receipt, value, intent.action, intent.id)
    saving.value = false; pending.value = null; emit('busy', false)
    notice.value = intent.action === 'PREPARE' ? '调整准备已保存，请刷新核对 ERP 调整与本地完成结果。' : intent.action === 'RETIRE' ? '本次调整已安全结束；原银行付款和历史保留。重新登记时请明确选择会计日期。' : '原调整办理已登记，请刷新核对结果。'
    await load()
  } catch (cause) { if (current === epoch) { requiresRefresh.value = true; error.value = supplierAdjustmentError(cause) } }
  finally { if (current === epoch) { saving.value = false; syncPending(); emit('busy', false) } }
}
watch(() => JSON.stringify([props.scopeKey, props.paymentId, props.requestId, props.applicationId, props.roundNo]), () => {
  stop(); view.value = null; disputeId.value = null; disputeBusy.value = false; loading.value = false; saving.value = false; requiresRefresh.value = false; beforeId.value = undefined; pending.value = null; comment.value = ''; accountingDate.value = ''; error.value = ''; notice.value = ''
  syncPending(); emit('busy', false); if (props.scopeKey) void load()
}, { immediate: true, flush: 'sync' })
onUnmounted(() => { stop(); unsubscribe(); emit('busy', false) })
const date = (value: string) => new Date(value).toLocaleString('zh-CN')
</script>

<template>
  <section class="supplier-adjustment" aria-label="供应商回款账务调整">
    <div class="adjustment-heading"><div><p class="adjustment-eyebrow">实际回款后 · 独立财务记账</p><h4>回款账务调整</h4></div><button class="quiet" type="button" :disabled="loading || saving || disputeBusy || locked" @click="notice = ''; load()">{{ beforeId ? '返回最新状态' : '刷新调整状态' }}</button></div>
    <p class="adjustment-help">已登记的实际回款需另行记账。财务确认会计日期后，分别核对 ERP 调整与本地完成结果。</p>
    <p v-if="loading" role="status" class="adjustment-help">正在核对原付款、登记回款与调整历史…</p>
    <p v-if="error" role="alert" class="adjustment-error">{{ error }}</p><p v-if="notice" role="status" class="adjustment-help">{{ notice }}</p>
    <p v-if="unconfirmed && !saving" role="alert" class="adjustment-error">上次办理结果未确认，请在未确认操作中恢复原请求后刷新。</p>
    <p v-if="requiresRefresh && !unconfirmed && !error" role="status" class="adjustment-help">请刷新，核对恢复后的原调整。</p>
    <template v-if="view">
      <div class="adjustment-amounts" aria-label="回款记账金额"><div><span>已登记回款</span><strong>{{ view.totalReturned.currency }} {{ view.totalReturned.value }}</strong></div><div><span>已完成入账</span><strong>{{ view.accountedReturned.currency }} {{ view.accountedReturned.value }}</strong></div><div><span>待入账回款</span><strong>{{ view.pendingReturned.currency }} {{ view.pendingReturned.value }}</strong></div></div>
      <p class="adjustment-help">{{ view.supplierName }} · 原付款 {{ view.amount.currency }} {{ view.amount.value }} · 扣除回款后 {{ view.netPaid.currency }} {{ view.netPaid.value }}<br />原收款账户 {{ view.maskedPayeeAccount }}</p>
      <div class="adjustment-stages">
        <div><span>原银行当前状态</span><strong>{{ view.bank ? paymentOperationLabels[view.bank.status] : '尚未登记银行付款' }}</strong><small v-if="view.bank?.disputed">银行回执存在争议</small></div>
        <div><span>本次 ERP 调整</span><strong>{{ active ? adjustmentLabels[active.status] : view.activeAdjustmentId ? '原调整在其他历史页' : view.canPrepare ? '待财务确认记账日期' : view.completion ? '请核对已完成调整历史' : view.preparation ? adjustmentPreparationLabels[view.preparation.status] : '尚未登记调整' }}</strong></div>
        <div><span>最近本地完成</span><strong>{{ view.completion ? '已保存独立完成凭据' : '尚未完成调整' }}</strong><small v-if="view.completion">{{ date(view.completion.completedAt) }}</small></div>
      </div>
      <p v-if="view.reviewRequired" role="status" class="adjustment-warning">原应付仍需复核。请核对实际回款与各次调整结果，历史完成凭据保留。</p>
      <p v-if="view.returnVersion === 0" class="adjustment-help">登记原付款的实际回款后，可在这里确认独立调整。</p>
      <div v-if="view.preparation" class="adjustment-preparation"><strong>{{ adjustmentPreparationLabels[view.preparation.status] }}</strong><span>会计日期 {{ view.preparation.accountingDate }} · {{ view.preparation.financeActor }}</span><p v-if="view.preparation.issue" class="adjustment-help">{{ adjustmentIssueLabels[view.preparation.issue] }}</p></div>
      <button v-if="!pending && allowed('PREPARE')" type="button" class="primary" :disabled="blocked" @click="prepare('PREPARE')">登记独立调整</button>
      <p v-if="beforeId" class="adjustment-help">当前查看较早的调整记录，资金合计仍对应原付款的最新登记情况。</p>
      <ol v-if="view.items.length" class="adjustment-history" aria-label="原付款独立调整历史">
        <li v-for="item in view.items" :key="item.id" class="adjustment-record">
          <div class="adjustment-record-heading"><strong>{{ item.retirement ? '已安全结束' : adjustmentLabels[item.status] }}</strong><span>{{ item.accountingDate }} · {{ item.financeActor }}</span></div>
          <p class="adjustment-help">本次回款 {{ item.returnedAmount.currency }} {{ item.returnedAmount.value }} · 累计回款 {{ item.totalReturned.currency }} {{ item.totalReturned.value }}<br />{{ item.recognizesOriginalPayment ? '本次同时确认原付款并结清原预留' : '本次处理新增回款，保留既有付款记账' }}<br />会计期间 {{ item.periodReference }} · {{ date(item.updatedAt) }}</p>
          <template v-if="item.posting"><p class="adjustment-help">ERP 调整号 {{ item.posting.adjustmentReference }}<br />原付款凭证 {{ item.posting.recognitionVoucherReference }}<br />原应付累计已付：{{ item.posting.payableSettledBefore.value }} → {{ item.posting.payableSettledAfter.value }} {{ item.posting.payableSettledAfter.currency }}</p>
            <ul class="adjustment-entries" aria-label="回款记账分录"><li v-for="entry in item.posting.entries" :key="entry.transactionReference"><strong>{{ entry.amount.currency }} {{ entry.amount.value }}</strong><span>银行流水 {{ entry.transactionReference }}</span><span>回款凭证 {{ entry.voucherReference }} · 分录 {{ entry.entryReference }}</span></li></ul>
            <p class="adjustment-help">{{ item.completion ? '本地记账已完成 · ' + date(item.completion.completedAt) : 'ERP 凭据已取得，本地记账尚未完成，请刷新核对。' }}</p>
          </template>
          <p v-if="item.completion && item.status !== 'ADJUSTED'" class="adjustment-warning">此前本地完成记录保留，当前 ERP 结果仍需核对。</p>
          <p v-if="item.rejection" class="adjustment-warning">{{ adjustmentRejectionLabels[item.rejection] }}</p><p v-if="item.issue" class="adjustment-help">{{ adjustmentIssueLabels[item.issue] }}</p>
          <p v-if="item.retirement" class="adjustment-help">{{ item.retirement.basis === 'NEVER_DISPATCHED' ? '确认从未外发调整' : 'ERP 已明确拒绝本次调整' }} · {{ item.retirement.retiredBy }} · {{ date(item.retirement.retiredAt) }}</p>
          <div v-if="!pending" class="adjustment-buttons"><button v-for="action in (['QUERY', 'RETRY', 'RETIRE'] as const)" v-show="allowed(action, item.id)" :key="action" type="button" class="quiet" :disabled="blocked" @click="prepare(action, item.id)">{{ adjustmentActionLabels[action] }}</button></div>
          <button v-if="item.dispatches > 0 && !item.retirement" type="button" class="quiet" :disabled="blocked || !!pending" @click="toggleDispute(item.id)">{{ disputeId === item.id ? '收起调整裁决' : '核对调整回执与裁决' }}</button>
          <SupplierAdjustmentDisputeStatus v-if="disputeId === item.id" :adjustment-id="item.id" :payment-id="paymentId" :request-id="requestId" :application-id="applicationId" :round-no="roundNo" :amount="view.amount" :returned-amount="item.returnedAmount" :total-returned="item.totalReturned" :net-paid="item.netPaid" :recognizes-original-payment="item.recognizesOriginalPayment" :scope-key="scopeKey" :locked="locked || saving || loading || !!pending" @busy="disputeActivity" @changed="disputeChanged" />
        </li>
      </ol>
      <button v-if="view.nextBeforeId && !pending" class="quiet" type="button" :disabled="loading || saving || disputeBusy || locked" @click="load(view.nextBeforeId)">查看更早调整</button>
      <form v-if="pending" ref="form" class="adjustment-confirm" @submit.prevent="execute">
        <h4>{{ adjustmentActionLabels[pending.action] }}</h4>
        <p>供应商 {{ view.supplierName }}，原付款 {{ view.amount.currency }} {{ view.amount.value }}，收款账户 {{ view.maskedPayeeAccount }}。</p>
        <template v-if="pending.action === 'PREPARE'"><p>本次待入账回款 {{ view.pendingReturned.currency }} {{ view.pendingReturned.value }}，已入账 {{ view.accountedReturned.currency }} {{ view.accountedReturned.value }}。</p><label>本次会计日期<input v-model="accountingDate" type="date" :min="view.minimumAccountingDate ?? undefined" required :disabled="saving" /></label><p class="adjustment-help">不能早于 {{ view.minimumAccountingDate }}（原资金与前次记账日期）。保存后核对所选会计期间。</p></template>
        <p v-else-if="pending.action === 'RETRY'">原调整暂未查到。确认按原编号、会计日期 {{ selected?.accountingDate }} 和原回款 {{ selected?.returnedAmount.currency }} {{ selected?.returnedAmount.value }} 重试。</p>
        <p v-else-if="pending.action === 'RETIRE'">根据从未外发或 ERP 明确无影响拒绝的依据结束本次尝试。原付款、登记回款和既有记账保留。</p>
        <p v-else>读取同一原调整的实际结果，核对会计日期 {{ selected?.accountingDate }} 与原回款 {{ selected?.returnedAmount.currency }} {{ selected?.returnedAmount.value }}。</p>
        <label>操作说明<textarea v-model="comment" rows="3" maxlength="2000" required :disabled="saving" /></label>
        <div class="adjustment-buttons"><button type="button" class="quiet" :disabled="saving" @click="pending = null; comment = ''; accountingDate = ''">返回核对</button><button type="submit" class="primary" :disabled="blocked || !allowed(pending.action, pending.id)">{{ saving ? '正在保存…' : '确认并提交' }}</button></div>
      </form>
    </template>
  </section>
</template>

<style scoped>

.supplier-adjustment{margin-top:24px;padding-top:22px;border-top:1px solid var(--line);min-width:0}.adjustment-heading,.adjustment-record-heading{display:flex;justify-content:space-between;gap:14px;align-items:center}.adjustment-heading h4{font-size:16px;margin:4px 0}.adjustment-eyebrow{font-size:11px;color:var(--muted);margin:0}.adjustment-help{font-size:12px;line-height:1.9;color:var(--muted);overflow-wrap:anywhere}.adjustment-stages{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:16px;padding:18px 0;border-bottom:1px solid var(--line)}.adjustment-stages>div{display:flex;flex-direction:column;gap:8px}.adjustment-stages span,.adjustment-stages small{font-size:11px;color:var(--muted);line-height:1.7}.adjustment-stages strong{font-size:13px;line-height:1.7}.adjustment-stages small{overflow-wrap:anywhere}.adjustment-preparation{display:grid;gap:8px;padding:12px 0;font-size:12px}.adjustment-preparation span,.adjustment-record-heading span{font-size:11px;color:var(--muted)}.adjustment-history{list-style:none;padding:0;margin:16px 0}.adjustment-record{padding:16px 0;border-bottom:1px solid var(--line);font-size:12px;overflow-wrap:anywhere}.adjustment-buttons{display:flex;flex-wrap:wrap;gap:10px;margin-top:14px}.adjustment-error,.adjustment-warning{padding:12px;border-radius:7px;font-size:12px;line-height:1.8;background:#fff0ed;color:var(--red)}.adjustment-warning{background:var(--paper);color:var(--ink);border-left:3px solid var(--red)}.adjustment-confirm{padding:16px;margin-top:18px;border:1px solid var(--line);border-radius:8px;font-size:12px;line-height:1.9}.adjustment-confirm h4{margin:0 0 8px;font-size:13px}.adjustment-confirm label{display:grid;gap:8px}.adjustment-confirm input,.adjustment-confirm textarea{width:100%;box-sizing:border-box;padding:10px;border:1px solid var(--line);border-radius:6px;font:inherit;color:var(--ink);background:var(--paper)}.adjustment-confirm input{max-width:250px}.adjustment-confirm textarea{resize:vertical}@media(max-width:650px){.adjustment-stages{grid-template-columns:1fr}.adjustment-heading,.adjustment-record-heading{align-items:flex-start;flex-wrap:wrap}}

.adjustment-amounts{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:16px;margin:18px 0;padding:16px;background:var(--paper);border:1px solid var(--line);border-radius:8px}.adjustment-amounts>div{display:grid;gap:8px}.adjustment-amounts span{font-size:11px;color:var(--muted)}.adjustment-amounts strong{font-size:16px;font-variant-numeric:tabular-nums}.adjustment-entries{padding-left:18px;line-height:1.9}.adjustment-entries li{margin:8px 0}.adjustment-entries span{display:block;color:var(--muted)}@media(max-width:650px){.adjustment-amounts{grid-template-columns:1fr}}
</style>
