<script setup lang="ts">
import { computed, nextTick, onUnmounted, ref, watch } from 'vue'
import { api, writeRequests } from '../api'
import { moneyLabel } from '../expenses'
import { expenseReturnLabels, expenseReturnCheckLabels, expenseReturnIssue, expenseReturnError, validateExpenseReturn, expenseReturnQueryInput, expenseReturnRegisterInput, validateExpenseReturnReceipt, type ExpenseReturnView, type ExpenseReturnQueryInput, type ExpenseReturnRegisterInput } from '../expensePaymentReturn'
const props = defineProps<{ applicationId: string; reportId: string; roundNo: number; scopeKey: string; locked?: boolean }>()
const emit = defineEmits<{ busy: [value: boolean]; changed: [] }>()
const view = ref<ExpenseReturnView | null>(null), loading = ref(false), saving = ref(false), pending = ref<'QUERY' | 'REGISTER' | null>(null)
const reference = ref(''), comment = ref(''), acknowledged = ref(false), error = ref(''), notice = ref(''), requiresRefresh = ref(false), unconfirmed = ref(false), form = ref<HTMLFormElement | null>(null)
let epoch = 0, controller: AbortController | null = null
const blocked = computed(() => !!props.locked || loading.value || saving.value || requiresRefresh.value || unconfirmed.value)
function stop() { epoch++; controller?.abort(); controller = null }
function clearMaterials() { view.value = null; pending.value = null; reference.value = ''; comment.value = ''; acknowledged.value = false }
function syncPending() {
  const prefix = `/expense-reports/${encodeURIComponent(props.reportId)}/payment-return`
  const current = writeRequests.pending().some(entry => entry.path === prefix + '/checks' || entry.path === prefix + '/registrations')
  if (unconfirmed.value && !current) requiresRefresh.value = true
  unconfirmed.value = current
}
const unsubscribe = writeRequests.subscribe(syncPending)
/** 刷新只读取原件；身份切换后的迟到响应不能恢复敏感材料或旧按钮。 */
async function load() {
  if (saving.value || loading.value || !props.scopeKey) return
  const previous = view.value; stop(); const current = epoch, request = new AbortController(); controller = request
  const binding = { applicationId: props.applicationId, reportId: props.reportId, roundNo: props.roundNo }
  loading.value = true; clearMaterials(); error.value = ''; let changed = false
  const timeout = setTimeout(() => { if (current === epoch) { stop(); loading.value = false; requiresRefresh.value = true; error.value = '报销退回原件读取超时，请刷新重试。' } }, 12_000)
  try {
    const result = await api.expensePaymentReturn(binding.reportId, binding.roundNo, request.signal)
    if (current !== epoch) return
    view.value = validateExpenseReturn(result, binding); requiresRefresh.value = false; syncPending()
    changed = !!previous && (previous.returnVersion !== view.value.returnVersion || previous.settlementVersion !== view.value.settlementVersion)
  } catch (cause) { if (current === epoch) { clearMaterials(); error.value = expenseReturnError(cause); requiresRefresh.value = true } }
  finally { clearTimeout(timeout); if (current === epoch) { loading.value = false; controller = null; if (changed) emit('changed') } }
}
function prepare(action: 'QUERY' | 'REGISTER') {
  if (blocked.value || !(action === 'QUERY' ? view.value?.canQuery : view.value?.latestCheck?.canRegister)) return
  pending.value = action; reference.value = ''; comment.value = ''; acknowledged.value = false; error.value = ''; notice.value = ''; const current = epoch
  void nextTick(() => { if (current === epoch) form.value?.querySelector<HTMLInputElement | HTMLTextAreaElement>('input,textarea')?.focus() })
}
function isRegistration(input: ExpenseReturnQueryInput | ExpenseReturnRegisterInput): input is ExpenseReturnRegisterInput { return 'checkId' in input }
/** 人工核对后只提交原件版本；未知结果沿用原请求和幂等键恢复。 */
async function execute() {
  const value = view.value, action = pending.value; if (!value || !action || blocked.value) return
  if (action === 'REGISTER' && !acknowledged.value) { error.value = '请确认已经核对本次累计原件与新增金额。'; return }
  let input
  try { input = action === 'QUERY' ? expenseReturnQueryInput(value, comment.value) : expenseReturnRegisterInput(value, reference.value, comment.value) }
  catch (cause) { error.value = expenseReturnError(cause); return }
  const current = epoch; saving.value = true; error.value = ''; emit('busy', true)
  try {
    const result = isRegistration(input) ? await api.registerExpensePaymentReturn(value.reportId, input) : await api.queryExpensePaymentReturn(value.reportId, input)
    if (current !== epoch) return
    validateExpenseReturnReceipt(result, value, input); pending.value = null; saving.value = false; emit('busy', false)
    notice.value = action === 'QUERY' ? '查询已登记，请刷新查看银行和会计原件，再明确登记。' : '本次退回复核已保存，原发票、预算、借款冲销与归档记录保留。'
    await load()
  } catch (cause) {
    if (current === epoch) {
      if ([401, 403, 404].includes((cause as { status?: number })?.status ?? 0)) clearMaterials()
      error.value = expenseReturnError(cause); requiresRefresh.value = true
    }
  } finally { if (current === epoch) { saving.value = false; syncPending(); emit('busy', false) } }
}
watch(() => JSON.stringify([props.scopeKey, props.applicationId, props.reportId, props.roundNo]), () => {
  stop(); clearMaterials(); loading.value = false; saving.value = false; error.value = ''; notice.value = ''; requiresRefresh.value = false
  syncPending(); emit('busy', false); if (props.scopeKey) void load()
}, { immediate: true, flush: 'sync' })
onUnmounted(() => { stop(); unsubscribe(); emit('busy', false) })
</script>

<template>
  <section class="expense-return" aria-label="报销付款退回复核">
    <div class="heading"><h4>报销付款退回复核</h4><button type="button" class="quiet" :disabled="loading || saving || locked" @click="notice = ''; load()">刷新报销退回</button></div>
    <p v-if="loading" role="status">正在读取原报销付款与银行退回依据…</p><p v-if="error" class="error" role="alert">{{ error }}</p><p v-if="notice" role="status">{{ notice }}</p>
    <p v-if="unconfirmed && !saving" class="error" role="alert">上次报销退回操作尚未确认，请先恢复原操作，再刷新核对。</p>
    <template v-if="view">
      <div class="amounts"><article><small>原净付款</small><strong>{{ moneyLabel(view.original.amount) }}</strong></article><article><small>累计已登记退回</small><strong>{{ moneyLabel(view.totalReturned) }}</strong></article><article><small>原付款扣除退回</small><strong>{{ moneyLabel(view.netPaid) }}</strong></article></div>
      <p>原交易 {{ view.original.paymentReference }} · {{ new Date(view.original.paidAt).toLocaleString() }}</p>
      <p>银行实际入款与员工应付贷方分录逐笔登记。已退回金额等待独立后续办理，原发票、预算和借款冲销记录保留。</p>
      <p v-if="view.reviewRequired" class="review" role="status">本报销的资金退回仍需处理，结算与新归档保持暂停。</p>
      <p v-for="entry in view.returns" :key="entry.proof.funding.transactionReference">已登记 {{ moneyLabel(entry.proof.funding.amount) }} · 流水 {{ entry.proof.funding.transactionReference }}<br />应付贷方 {{ entry.proof.posting.voucherReference }} / {{ entry.proof.posting.entryReference }} · 科目 {{ entry.proof.posting.accountCode }}</p>
      <details v-if="view.registrations.length"><summary>查看 {{ view.registrations.length }} 次登记记录</summary><p v-for="registration in view.registrations" :key="registration.id">{{ expenseReturnLabels[registration.outcome] }} · 当时累计 {{ moneyLabel(registration.totalReturned) }}<br />{{ registration.registeredBy }} · {{ new Date(registration.registeredAt).toLocaleString() }} · 材料 {{ registration.evidenceReference }}<br />{{ registration.reason }}</p></details>
      <article v-if="view.latestCheck" class="evidence">
        <strong>{{ expenseReturnCheckLabels[view.latestCheck.status] }}</strong>
        <template v-if="view.latestCheck.evidence">
          <p>{{ expenseReturnLabels[view.latestCheck.evidence.status] }}</p>
          <p>本次证据累计退回 {{ moneyLabel(view.latestCheck.evidence.totalReturned) }} · 新增待登记 {{ moneyLabel(view.latestCheck.evidence.newReturned) }}</p>
          <dl v-for="entry in view.latestCheck.evidence.returns" :key="entry.funding.transactionReference"><div><dt>银行实际退回</dt><dd>{{ moneyLabel(entry.funding.amount) }}</dd></div><div><dt>入款流水</dt><dd>{{ entry.funding.transactionReference }}</dd></div><div><dt>员工应付贷方凭证 / 分录</dt><dd>{{ entry.posting.voucherReference }} / {{ entry.posting.entryReference }}</dd></div><div><dt>原员工应付科目</dt><dd>{{ entry.posting.accountCode }}</dd></div><div><dt>公司收款时间</dt><dd>{{ new Date(entry.funding.receivedAt).toLocaleString() }}</dd></div><div><dt>入账日期</dt><dd>{{ entry.posting.accountingDate }}</dd></div></dl>
          <p>本次依据有效至 {{ new Date(view.latestCheck.evidence.validUntil).toLocaleString() }}</p>
        </template>
        <p v-if="view.latestCheck.issue">{{ expenseReturnIssue(view.latestCheck.issue) }}</p>
        <p v-else-if="view.latestCheck.registrationIssue && view.latestCheck.status === 'CHECKED'">{{ expenseReturnIssue(view.latestCheck.registrationIssue) }}</p>
      </article>
      <div v-if="!pending" class="buttons"><button v-if="view.canQuery" type="button" class="quiet" :disabled="blocked" @click="prepare('QUERY')">查询原报销与银行退回</button><button v-if="view.latestCheck?.canRegister" type="button" class="primary" :disabled="blocked" @click="prepare('REGISTER')">核对并登记报销退回</button></div>
      <form v-else ref="form" @submit.prevent="execute">
        <h4>{{ pending === 'QUERY' ? '读取原付款与累计退回依据' : '确认报销退回复核结果' }}</h4>
        <p v-if="pending === 'QUERY'">查询原付款当前状态及公司实际入款，查询完成后需要核对原件再登记。</p>
        <p v-else-if="view.latestCheck?.evidence?.returns.length">本次依据累计 {{ moneyLabel(view.latestCheck.evidence.totalReturned) }}，新增登记 {{ moneyLabel(view.latestCheck.evidence.newReturned) }}。登记后继续保留待处理状态，原核销资源不重新开放。</p>
        <p v-else>确认原付款仍有效且没有发生银行退回；其他付款、凭证或批准问题仍须分别处理。</p>
        <label v-if="pending === 'REGISTER'">核验材料编号<input v-model="reference" maxlength="128" required :disabled="saving" /></label>
        <label>核对说明<textarea v-model="comment" rows="3" maxlength="2000" required :disabled="saving" /></label>
        <label v-if="pending === 'REGISTER'" class="ack"><input v-model="acknowledged" type="checkbox" required :disabled="saving" />已核对累计原件与本次新增金额</label>
        <div class="buttons"><button class="primary" :disabled="blocked || pending === 'REGISTER' && !acknowledged">{{ saving ? '正在登记…' : pending === 'QUERY' ? '登记报销退回查询' : '确认并保存报销退回' }}</button><button type="button" class="quiet" :disabled="saving" @click="pending = null; reference = ''; comment = ''; acknowledged = false">取消</button></div>
      </form>
    </template>
  </section>
</template>

<style scoped>
.expense-return{margin-top:18px;padding:16px;border:1px solid var(--line);border-radius:10px;background:var(--paper);min-width:0;overflow-wrap:anywhere}.heading{display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap}h4{font-size:13px;margin:0}p{font-size:12px;line-height:1.8}.error{color:var(--red)}.review{padding:10px 12px;border-left:3px solid var(--teal);background:white}.amounts{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px;margin:16px 0}.amounts article{padding:12px;border:1px solid var(--line);border-radius:8px;background:white}.amounts small{display:block;font-size:11px;color:var(--muted)}.amounts strong{display:block;margin-top:8px;font-size:15px;font-variant-numeric:tabular-nums}.evidence,form{margin-top:14px;padding-top:14px;border-top:1px solid var(--line)}.evidence>strong{font-size:13px}dl{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px}dt{font-size:11px;color:var(--muted);margin-bottom:6px}dd{margin:0;font-size:12px;font-variant-numeric:tabular-nums}label{display:grid;gap:7px;font-size:12px;margin-top:12px}input,textarea{width:100%;padding:10px;border:1px solid var(--line);border-radius:7px;background:white;font:inherit}textarea{resize:vertical}.ack{display:flex;align-items:center;gap:9px}.ack input{width:18px;height:18px}.buttons{display:flex;gap:10px;flex-wrap:wrap;margin-top:14px}button{min-height:40px}summary{font-size:12px;cursor:pointer;padding:9px 0}@media(max-width:650px){dl,.amounts{grid-template-columns:1fr}}
</style>
