<script setup lang="ts">
import { computed, nextTick, onUnmounted, ref, watch } from 'vue'
import { api, writeRequests } from '../api'
import AdvanceRepaymentReview from './AdvanceRepaymentReview.vue'
import AdvanceDisbursementReturn from './AdvanceDisbursementReturn.vue'
import type { DisbursementReturnView } from '../disbursementReturn'
import type { RepaymentReviewView } from '../repaymentReview'
import { moneyLabel } from '../expenses'
import { recordedRepaymentReturns, advanceBalanceLabels, repaymentChannelLabels, repaymentCheckLabels, repaymentEvidenceLabels, repaymentIssue, repaymentError, validateRepaymentView, repaymentQueryInput, repaymentRecordInput, validateRepaymentReceipt, type RepaymentView, type RepaymentRecord } from '../advanceRepayment'
const props = defineProps<{ applicationId: string; advanceId: string; roundNo: number; scopeKey: string; locked?: boolean }>()
const emit = defineEmits<{ busy: [value: boolean] }>()
const view = ref<RepaymentView | null>(null), records = ref<RepaymentRecord[]>([]), loading = ref(false), saving = ref(false), loadingMore = ref(false)
const pending = ref<'QUERY' | 'RECORD' | null>(null), reference = ref(''), comment = ref(''), error = ref(''), notice = ref(''), requiresRefresh = ref(false), unconfirmed = ref(false)
const showDisbursement = ref(false)
const form = ref<HTMLFormElement | null>(null), selectedRepayment = ref<string | null>(null), reviewBusy = ref(false)
let epoch = 0, controller: AbortController | null = null
const blocked = computed(() => !!props.locked || reviewBusy.value || loading.value || loadingMore.value || saving.value || requiresRefresh.value || unconfirmed.value)
function stop() { epoch++; controller?.abort(); controller = null }
function syncPending() {
  const prefix = `/advance-requests/${encodeURIComponent(props.advanceId)}`
  const current = writeRequests.pending().some(entry => entry.path === prefix + '/repayments' || entry.path === prefix + '/repayment-checks')
  if (unconfirmed.value && !current) requiresRefresh.value = true
  unconfirmed.value = current
}
const unsubscribe = writeRequests.subscribe(syncPending)
/** 读取及翻页只更新同一身份和借款的资料，不能自动确认收款。 */
async function load(more = false) {
  if (saving.value || reviewBusy.value || loading.value || loadingMore.value || !props.scopeKey) return
  const beforeId = more ? view.value?.nextBeforeId ?? undefined : undefined
  if (more && (!beforeId || blocked.value)) return
  stop(); const current = epoch, request = new AbortController(); controller = request
  const binding = { applicationId: props.applicationId, advanceId: props.advanceId, roundNo: props.roundNo }
  if (more) loadingMore.value = true
  else { loading.value = true; showDisbursement.value = false; selectedRepayment.value = null; view.value = null; records.value = []; pending.value = null; reference.value = ''; comment.value = '' }
  error.value = ''
  const timeout = setTimeout(() => { if (current === epoch) { stop(); loading.value = false; loadingMore.value = false; requiresRefresh.value = true; error.value = '还款资料读取超时，请刷新重试。' } }, 12_000)
  try {
    const result = await api.advanceRepayments(binding.advanceId, binding.roundNo, beforeId, request.signal)
    if (current !== epoch) return
    const checked = validateRepaymentView(result, binding)
    if (more && checked.records.some(row => records.value.some(previous => previous.id === row.id))) throw new Error('还款历史已变化，请刷新后重新查看。')
    records.value = more ? [...records.value, ...checked.records] : checked.records
    view.value = checked; requiresRefresh.value = false; syncPending()
  } catch (cause) {
    if (current === epoch) {
      // 翻页失权时同时撤下已加载资料，不能让上一页继续暴露原字段内容。
      if ([401, 403, 404].includes((cause as { status?: number })?.status ?? 0)) { view.value = null; records.value = []; selectedRepayment.value = null; pending.value = null; reference.value = ''; comment.value = '' }
      error.value = repaymentError(cause); requiresRefresh.value = true
    }
  }
  finally { clearTimeout(timeout); if (current === epoch) { loading.value = false; loadingMore.value = false; controller = null } }
}
function prepare(action: 'QUERY' | 'RECORD') {
  if (blocked.value || !(action === 'QUERY' ? view.value?.canQuery : view.value?.latestCheck?.canRecord)) return
  pending.value = action; reference.value = ''; comment.value = ''; error.value = ''; notice.value = ''; const current = epoch
  void nextTick(() => { if (current === epoch) form.value?.querySelector<HTMLInputElement | HTMLTextAreaElement>('input,textarea')?.focus() })
}
/** 查询与确认是两次明确操作；动作回执也必须属于原借款和精确版本。 */
async function execute() {
  const value = view.value, action = pending.value; if (!value || !action || blocked.value) return
  let input
  try { input = action === 'QUERY' ? repaymentQueryInput(value, reference.value, comment.value) : repaymentRecordInput(value, comment.value) }
  catch (cause) { error.value = repaymentError(cause); return }
  const current = epoch; saving.value = true; error.value = ''; emit('busy', true)
  try {
    const receipt = 'checkId' in input ? await api.recordAdvanceRepayment(value.advanceId, input) : await api.queryAdvanceRepayment(value.advanceId, input)
    if (current !== epoch) return
    validateRepaymentReceipt(receipt, value, input)
    pending.value = null; saving.value = false; emit('busy', false)
    notice.value = action === 'QUERY' ? '查询已登记，请刷新查看原收款与入账结果，核对后再确认还款。' : '还款已确认，借款余额及原收款记录已保存。'
    await load()
  } catch (cause) {
    if (current === epoch) {
      if ([401, 403, 404].includes((cause as { status?: number })?.status ?? 0)) { view.value = null; records.value = []; selectedRepayment.value = null; pending.value = null; reference.value = ''; comment.value = '' }
      error.value = repaymentError(cause); requiresRefresh.value = true
    }
  }
  finally { if (current === epoch) { saving.value = false; syncPending(); emit('busy', false) } }
}
/** 子复核显示新的余额时同步摘要，并要求原还款操作重新读取自己的完整依据。 */
function reviewRefreshed(value: RepaymentReviewView) {
  if (!view.value || value.advanceId !== view.value.advanceId || value.original.id !== selectedRepayment.value) return
  if (view.value.balance?.version !== value.balance.version) {
    requiresRefresh.value = true; view.value.latestCheck = null; pending.value = null; reference.value = ''; comment.value = ''
    notice.value = '原还款复核已更新，办理其他还款前请刷新还款记录。'
  }
  view.value.balance = value.balance
  records.value = records.value.map(row => row.id === value.original.id ? value.original : row)
}
/** 原放款退回更新余额后，旧还款候选必须重新读取，不能沿用旧版本。 */
function disbursementRefreshed(value: DisbursementReturnView) {
  if (!view.value || value.advanceId !== view.value.advanceId) return
  if (view.value.balance?.version !== value.balance.version) {
    requiresRefresh.value = true; view.value.latestCheck = null; pending.value = null; reference.value = ''; comment.value = ''
    notice.value = '原放款复核已更新，办理员工还款前请刷新还款记录。'
  }
  view.value.balance = value.balance
}
function reviewSaving(value: boolean) { reviewBusy.value = value; emit('busy', value) }
watch(() => JSON.stringify([props.scopeKey, props.applicationId, props.advanceId, props.roundNo]), () => {
  stop(); showDisbursement.value = false; selectedRepayment.value = null; reviewBusy.value = false; view.value = null; records.value = []; loading.value = false; loadingMore.value = false; saving.value = false
  pending.value = null; reference.value = ''; comment.value = ''; error.value = ''; notice.value = ''; requiresRefresh.value = false
  syncPending(); emit('busy', false); if (props.scopeKey) void load()
}, { immediate: true, flush: 'sync' })
onUnmounted(() => { stop(); unsubscribe(); emit('busy', false) })
</script>

<template>
  <section class="repayment" aria-label="借款还款与结清">
    <div class="repayment-heading"><h3>借款还款与结清</h3><button type="button" class="quiet" :disabled="loading || loadingMore || saving || reviewBusy || locked" @click="notice = ''; load()">刷新还款记录</button></div>
    <p class="help">员工还款、还款退回、原放款退回和报销冲销分别记载，共同核算未还款。</p>
    <p v-if="loading" class="help" role="status">正在核对借款余额与原收款记录…</p>
    <p v-if="error" class="error" role="alert">{{ error }}</p><p v-if="notice" class="help" role="status">{{ notice }}</p>
    <p v-if="unconfirmed && !saving" class="error" role="alert">上次操作结果尚未确认，请先恢复原操作，再刷新核对余额。</p>
    <template v-if="view">
      <p v-if="!view.balance" class="help">本轮尚无可核对的实际放款余额。</p>
      <template v-else>
        <div class="balance-heading"><strong>{{ advanceBalanceLabels[view.balance.status] }}</strong><span>第 {{ view.roundNo }} 轮借款</span></div>
        <dl class="balance-grid"><div><dt>实际放款</dt><dd>{{ moneyLabel(view.balance.paid) }}</dd></div><div><dt>已报销冲销</dt><dd>{{ moneyLabel(view.balance.offset) }}</dd></div><div><dt>净有效还款</dt><dd>{{ moneyLabel(view.balance.repaid) }}</dd></div><div><dt>累计原收款</dt><dd>{{ moneyLabel(view.balance.receivedRepayments) }}</dd></div><div><dt>还款已退回</dt><dd>{{ moneyLabel(view.balance.returnedRepayments) }}</dd></div><div v-if="view.balance.returnedDisbursements"><dt>原放款已退回</dt><dd>{{ moneyLabel(view.balance.returnedDisbursements) }}</dd></div><div><dt>未还款</dt><dd class="remaining">{{ moneyLabel(view.balance.outstanding) }}</dd></div><div><dt>当前预留</dt><dd>{{ moneyLabel(view.balance.reserved) }}</dd></div><div><dt>可用余额</dt><dd>{{ moneyLabel(view.balance.available) }}</dd></div></dl>
        <p v-if="view.balance.status === 'VOUCHER_REVIEW'" class="error">原借款挂账或付款凭证存在争议，后续使用已暂停。请在原凭证中完成财务核对；现有还款、预留和冲销记录保留。</p>
        <p v-if="view.balance.status === 'REPAYMENT_REVIEW'" class="error">已确认还款的外部记录发生变化，后续使用已暂停。原还款和报销冲销保留，请完成财务核对。</p>
        <article v-if="view.latestCheck" class="receipt" aria-label="原还款凭据查询结果">
          <h4>{{ repaymentCheckLabels[view.latestCheck.status] }}</h4><p>收款编号 {{ view.latestCheck.receiptReference }}</p>
          <template v-if="view.latestCheck.evidence">
            <p><strong>{{ repaymentEvidenceLabels[view.latestCheck.evidence.status] }}</strong></p>
            <dl v-if="view.latestCheck.evidence.funding && view.latestCheck.evidence.posting" class="evidence-grid"><div><dt>原收款金额</dt><dd>{{ moneyLabel(view.latestCheck.evidence.funding.amount) }}</dd></div><div><dt>收款方式</dt><dd>{{ repaymentChannelLabels[view.latestCheck.evidence.funding.channel] }}</dd></div><div><dt>资金流水</dt><dd>{{ view.latestCheck.evidence.funding.transactionReference }}</dd></div><div><dt>已入账凭证 / 分录</dt><dd>{{ view.latestCheck.evidence.posting.voucherReference }} / {{ view.latestCheck.evidence.posting.entryReference }}</dd></div><div><dt>收款时间</dt><dd>{{ new Date(view.latestCheck.evidence.funding.receivedAt).toLocaleString() }}</dd></div><div><dt>入账日期</dt><dd>{{ view.latestCheck.evidence.posting.accountingDate }}</dd></div></dl>
            <p>本次查询有效至 {{ new Date(view.latestCheck.evidence.validUntil).toLocaleString() }}</p>
          </template>
          <p v-if="view.latestCheck.issue">{{ repaymentIssue(view.latestCheck.issue) }}</p>
          <p v-else-if="view.latestCheck.confirmationIssue && view.latestCheck.status === 'CHECKED'">{{ repaymentIssue(view.latestCheck.confirmationIssue) }}</p>
        </article>
        <div v-if="!pending" class="buttons"><button v-if="view.canQuery" type="button" class="quiet" :disabled="blocked" @click="prepare('QUERY')">查询还款凭据</button><button v-if="view.latestCheck?.canRecord" type="button" class="primary" :disabled="blocked" @click="prepare('RECORD')">核对并确认还款</button></div>
        <form v-else ref="form" class="confirmation" @submit.prevent="execute">
          <h4>{{ pending === 'QUERY' ? '查询原收款凭据' : '确认本次还款' }}</h4>
          <p v-if="pending === 'QUERY'">请输入已完成收款的编号，核对原借款对应的收款与员工借款入账记录。</p>
          <p v-else>确认将 {{ moneyLabel(view.latestCheck!.evidence!.funding!.amount) }} 计入本笔借款还款，采用上方资金流水及已入账凭证。当前报销预留将继续保留。</p>
          <label v-if="pending === 'QUERY'">原收款编号<input v-model="reference" type="text" maxlength="128" required :disabled="saving" /></label>
          <label>核对说明<textarea v-model="comment" rows="3" maxlength="2000" required :disabled="saving" /></label>
          <div class="buttons"><button class="primary" :disabled="blocked">{{ saving ? '正在登记…' : pending === 'QUERY' ? '登记查询' : '确认并保存还款' }}</button><button type="button" class="quiet" :disabled="saving" @click="pending = null; reference = ''; comment = ''">取消</button></div>
        </form>
        <div v-if="view.balance" class="buttons"><button type="button" class="quiet" :disabled="saving || reviewBusy || locked || loadingMore" @click="showDisbursement = !showDisbursement; selectedRepayment = null">{{ showDisbursement ? '收起原放款复核' : '核对原放款与银行退回' }}</button></div>
        <AdvanceDisbursementReturn v-if="showDisbursement" :application-id="applicationId" :advance-id="advanceId" :round-no="roundNo" :scope-key="scopeKey" :locked="locked || saving || loadingMore" @busy="reviewSaving" @refreshed="disbursementRefreshed" />
        <div class="history"><h4>已确认还款</h4><p v-if="!records.length" class="help">暂无已确认还款。</p><article v-for="row in records" :key="row.id" class="history-row"><div><strong>{{ moneyLabel(row.amount) }}</strong><span>{{ repaymentChannelLabels[row.channel] }}</span></div><p>收款 {{ row.receiptReference }} · {{ new Date(row.receivedAt).toLocaleString() }}<br />凭证 {{ row.voucherReference }} / {{ row.entryReference }} · {{ row.accountingDate }}<br />确认 {{ row.recordedBy }} · {{ new Date(row.recordedAt).toLocaleString() }}</p><p v-if="row.reviewRequired" class="error">本笔还款待核对</p><p v-for="entry in recordedRepaymentReturns(row)" :key="JSON.stringify([entry.fundsReturn.channel, entry.fundsReturn.transactionReference])">已确认退回 {{ moneyLabel(entry.fundsReturn.amount) }} · {{ entry.posting.voucherReference }} / {{ entry.posting.entryReference }}</p><button type="button" class="quiet" :disabled="saving || reviewBusy || locked || loadingMore" @click="selectedRepayment = selectedRepayment === row.id ? null : row.id; showDisbursement = false">{{ selectedRepayment === row.id ? '收起复核详情' : '查看原还款与复核' }}</button><AdvanceRepaymentReview v-if="selectedRepayment === row.id" :application-id="applicationId" :advance-id="advanceId" :repayment-id="row.id" :round-no="roundNo" :scope-key="scopeKey" :locked="locked || saving || loadingMore" @busy="reviewSaving" @refreshed="reviewRefreshed" /></article><button v-if="view.nextBeforeId" type="button" class="quiet" :disabled="blocked" @click="load(true)">{{ loadingMore ? '正在读取…' : '更早还款记录' }}</button></div>
      </template>
    </template>
  </section>
</template>

<style scoped>
.repayment{margin-top:24px;padding-top:20px;border-top:1px solid var(--line);min-width:0}.repayment-heading,.balance-heading{display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap}.repayment-heading h3{font-size:15px;margin:0}.help,.error,.receipt p{font-size:12px;line-height:1.8}.help{color:var(--muted)}.error{color:var(--red)}.balance-heading{margin-top:18px;font-size:13px}.balance-heading span{font-size:12px;color:var(--muted)}.balance-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:14px;padding:18px;background:var(--paper);border-radius:10px}.balance-grid dt,.evidence-grid dt{font-size:11px;color:var(--muted);margin-bottom:6px}.balance-grid dd,.evidence-grid dd{margin:0;font-size:13px;font-variant-numeric:tabular-nums;overflow-wrap:anywhere}.remaining{font-weight:700;color:var(--green)}.receipt,.confirmation{margin-top:16px;padding:16px;border:1px solid var(--line);border-radius:10px;background:#f5f9f7;overflow-wrap:anywhere}.receipt h4,.confirmation h4,.history h4{font-size:13px;margin:0}.evidence-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px}.confirmation p{font-size:12px;line-height:1.8}.confirmation label{display:grid;gap:7px;font-size:12px;margin-top:12px}.confirmation input,.confirmation textarea{width:100%;padding:10px;border:1px solid var(--line);border-radius:7px;font:inherit;background:white}.confirmation textarea{resize:vertical}.buttons{display:flex;flex-wrap:wrap;gap:10px;margin-top:14px}.repayment button{min-height:40px}.history{margin-top:22px}.history-row{padding:14px 0;border-bottom:1px solid var(--line);overflow-wrap:anywhere}.history-row>div{display:flex;gap:14px;align-items:center;font-size:13px}.history-row span,.history-row p{font-size:12px;color:var(--muted);line-height:1.8}.history-row p{margin-bottom:0}.history>button{margin-top:12px}@media(max-width:650px){.balance-grid{grid-template-columns:repeat(2,minmax(0,1fr))}.evidence-grid{grid-template-columns:1fr}}
</style>
