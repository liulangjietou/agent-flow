<script setup lang="ts">
import { computed, nextTick, onUnmounted, ref, watch } from 'vue'
import { api, writeRequests } from '../api'
import { moneyLabel } from '../expenses'
import { supplierReturnLabels, supplierReturnCheckLabels, supplierReturnIssue, supplierReturnError, validateSupplierReturn, supplierReturnAllowed, supplierReturnInput, validateSupplierReturnReceipt, type SupplierReturnView } from '../supplierReturn'
const props = defineProps<{ paymentId: string; requestId: string; applicationId: string; roundNo: number; scopeKey: string; locked?: boolean }>()
const emit = defineEmits<{ busy: [value: boolean]; changed: [] }>()
const view = ref<SupplierReturnView | null>(null), loading = ref(false), saving = ref(false), pending = ref<'QUERY' | 'REGISTER' | null>(null)
const reference = ref(''), comment = ref(''), acknowledged = ref(false), error = ref(''), notice = ref(''), requiresRefresh = ref(false), unconfirmed = ref(false), form = ref<HTMLFormElement | null>(null)
const beforeVersion = ref<number | undefined>(), now = ref(Date.now()), ticker = setInterval(() => { now.value = Date.now() }, 1000)
let epoch = 0, controller: AbortController | null = null
const blocked = computed(() => !!props.locked || loading.value || saving.value || requiresRefresh.value || unconfirmed.value)
function allowed(action: 'QUERY' | 'REGISTER') { return supplierReturnAllowed(view.value, action, now.value) }
function stop() { epoch++; controller?.abort(); controller = null }
function clearMaterials() { view.value = null; pending.value = null; reference.value = ''; comment.value = ''; acknowledged.value = false }
function syncPending() {
  const prefix = `/supplier-payments/${encodeURIComponent(props.paymentId)}/returns/`
  const current = writeRequests.pending().some(entry => entry.path === prefix + 'checks' || entry.path === prefix + 'registrations' || entry.path.startsWith('/supplier-adjustments/') || entry.path.endsWith('/adjustment-preparations'))
  if (unconfirmed.value && !current) requiresRefresh.value = true
  unconfirmed.value = current
}
const unsubscribe = writeRequests.subscribe(syncPending)
/** 身份变化使所有迟到响应失效；历史分页仍读取当前实际资金总额。 */
async function load(cursor?: number) {
  if (saving.value || loading.value || !props.scopeKey) return
  const previous = view.value; stop(); const current = epoch, request = new AbortController(); controller = request
  const binding = { paymentId: props.paymentId, requestId: props.requestId, applicationId: props.applicationId, roundNo: props.roundNo }
  loading.value = true; clearMaterials(); error.value = ''; let changed = false
  const timeout = setTimeout(() => { if (current === epoch) { stop(); loading.value = false; requiresRefresh.value = true; error.value = '回款依据读取超时，请刷新重试。' } }, 12_000)
  try {
    const result = await api.supplierReturns(binding.paymentId, cursor, request.signal)
    if (current !== epoch) return
    view.value = validateSupplierReturn(result, binding, cursor); beforeVersion.value = cursor; requiresRefresh.value = false; syncPending()
    changed = !!previous && (previous.returnVersion !== view.value.returnVersion || previous.operationVersion !== view.value.operationVersion)
  } catch (cause) { if (current === epoch) { clearMaterials(); error.value = supplierReturnError(cause); requiresRefresh.value = true } }
  finally { clearTimeout(timeout); if (current === epoch) { loading.value = false; controller = null; if (changed) emit('changed') } }
}
function prepare(action: 'QUERY' | 'REGISTER') {
  if (blocked.value || !allowed(action)) return
  pending.value = action; reference.value = ''; comment.value = ''; acknowledged.value = false; error.value = ''; notice.value = ''; const current = epoch
  void nextTick(() => { if (current === epoch) form.value?.querySelector<HTMLInputElement | HTMLTextAreaElement>('input,textarea')?.focus() })
}
/** 选择操作只展示核对表单；未知结果使用原幂等请求恢复。 */
async function execute() {
  const value = view.value, action = pending.value; if (!value || !action || blocked.value) return
  if (action === 'REGISTER' && !acknowledged.value) { error.value = '请确认已核对累计入款与本次新增金额。'; return }
  let input
  try { input = supplierReturnInput(value, action, comment.value, reference.value) } catch (cause) { error.value = supplierReturnError(cause); return }
  const current = epoch; saving.value = true; error.value = ''; emit('busy', true)
  try {
    const result = 'checkId' in input ? await api.registerSupplierReturns(value.paymentId, input) : await api.querySupplierReturns(value.paymentId, input)
    if (current !== epoch) return
    validateSupplierReturnReceipt(result, value, input); pending.value = null; saving.value = false; emit('busy', false)
    notice.value = action === 'QUERY' ? '回款查询已登记，请刷新查看实际入款，再核对登记。' : '回款决定已保存。账务调整单独办理，原付款和核销记录保留。'
    await load()
  } catch (cause) {
    if (current === epoch) {
      if ([401, 403, 404].includes((cause as { status?: number })?.status ?? 0)) clearMaterials()
      error.value = supplierReturnError(cause); requiresRefresh.value = true
    }
  } finally { if (current === epoch) { saving.value = false; syncPending(); emit('busy', false) } }
}
watch(() => JSON.stringify([props.scopeKey, props.paymentId, props.requestId, props.applicationId, props.roundNo]), () => {
  stop(); clearMaterials(); beforeVersion.value = undefined; loading.value = false; saving.value = false; error.value = ''; notice.value = ''; requiresRefresh.value = false
  syncPending(); emit('busy', false); if (props.scopeKey) void load()
}, { immediate: true, flush: 'sync' })
onUnmounted(() => { stop(); clearInterval(ticker); unsubscribe(); emit('busy', false) })
const date = (value: string) => new Date(value).toLocaleString('zh-CN')
</script>

<template>
  <section class="supplier-return" aria-label="供应商付款回款复核">
    <div class="heading"><h4>供应商付款回款复核</h4><button type="button" class="quiet" :disabled="loading || saving || locked" @click="notice = ''; load()">刷新实际回款</button></div>
    <p v-if="loading" role="status">正在读取原付款与实际回款…</p><p v-if="error" class="error" role="alert">{{ error }}</p><p v-if="notice" role="status">{{ notice }}</p>
    <p v-if="unconfirmed && !saving" class="error" role="alert">上次回款操作尚未确认，请先恢复原操作，再刷新核对。</p>
    <template v-if="view">
      <p v-if="!view.original">尚无已确认成功的原银行付款，当前没有可核对的回款。</p>
      <template v-else>
        <div class="amounts"><article><small>原付款金额</small><strong>{{ moneyLabel(view.original.amount) }}</strong></article><article><small>累计已登记回款</small><strong>{{ moneyLabel(view.totalReturned!) }}</strong></article><article><small>原付款扣除回款</small><strong>{{ moneyLabel(view.netPaid!) }}</strong></article></div>
        <p>原交易 {{ view.original.paymentReference }} · 回单 {{ view.original.receiptReference }}<br />{{ date(view.original.paidAt) }}</p>
        <p>实际入款与 ERP 调整分别核对。登记回款会保留原付款、核销和完成记录，不会重新开放付款。</p>
        <p v-if="view.reviewRequired" class="review" role="status">回款仍需复核或独立账务调整，原应付继续暂停新增付款和核销。</p>
        <p v-for="entry in view.returns" :key="entry.funding.transactionReference">已登记 {{ moneyLabel(entry.funding.amount) }} · 流水 {{ entry.funding.transactionReference }} · {{ date(entry.funding.receivedAt) }}</p>
        <details v-if="view.registrations.length || beforeVersion"><summary>回款登记记录</summary><p v-for="registration in view.registrations" :key="registration.id">{{ supplierReturnLabels[registration.outcome] }} · 当时累计 {{ moneyLabel(registration.totalReturned) }}<br />{{ registration.registeredBy }} · {{ date(registration.registeredAt) }} · 材料 {{ registration.evidenceReference }}<br />{{ registration.reason }}</p><div class="buttons"><button v-if="view.nextBeforeVersion" type="button" class="quiet" :disabled="blocked" @click="load(view.nextBeforeVersion)">查看更早记录</button><button v-if="beforeVersion" type="button" class="quiet" :disabled="loading || saving || locked" @click="load()">返回最新记录</button></div></details>
        <article v-if="view.latestCheck" class="evidence">
          <strong>{{ supplierReturnCheckLabels[view.latestCheck.status] }}</strong>
          <template v-if="view.latestCheck.evidence">
            <p>{{ supplierReturnLabels[view.latestCheck.evidence.outcome] }}</p><p>本次依据累计 {{ moneyLabel(view.latestCheck.evidence.totalReturned) }} · 新增待登记 {{ moneyLabel(view.latestCheck.evidence.newReturned) }}</p>
            <dl v-for="funding in view.latestCheck.evidence.returns" :key="funding.transactionReference"><div><dt>实际回款</dt><dd>{{ moneyLabel(funding.amount) }}</dd></div><div><dt>公司入款流水</dt><dd>{{ funding.transactionReference }}</dd></div><div><dt>公司收款时间</dt><dd>{{ date(funding.receivedAt) }}</dd></div></dl>
            <p>依据有效至 {{ date(view.latestCheck.evidence.validUntil) }}<span v-if="Date.parse(view.latestCheck.evidence.validUntil) <= now"> · 已到期，请重新查询</span></p>
          </template>
          <p v-if="view.latestCheck.issue">{{ supplierReturnIssue(view.latestCheck.issue) }}</p><p v-else-if="view.latestCheck.registrationIssue && view.latestCheck.status === 'CHECKED'">{{ supplierReturnIssue(view.latestCheck.registrationIssue) }}</p>
        </article>
        <div v-if="!pending" class="buttons"><button v-if="view.canQuery" type="button" class="quiet" :disabled="blocked || !allowed('QUERY')" @click="prepare('QUERY')">查询原付款实际回款</button><button v-if="view.latestCheck?.canRegister" type="button" class="primary" :disabled="blocked || !allowed('REGISTER')" @click="prepare('REGISTER')">核对并登记回款</button></div>
        <form v-else ref="form" @submit.prevent="execute">
          <h4>{{ pending === 'QUERY' ? '读取原付款与累计入款' : '确认本次回款决定' }}</h4>
          <p v-if="pending === 'QUERY'">查询完成后，请核对原交易及公司实际收到的资金，再明确登记。</p><p v-else-if="view.latestCheck?.evidence?.returns.length">本次累计 {{ moneyLabel(view.latestCheck.evidence.totalReturned) }}，新增登记 {{ moneyLabel(view.latestCheck.evidence.newReturned) }}。登记后仍须单独办理账务调整。</p><p v-else>确认原付款仍有效且没有发生回款；其他银行或核销问题继续单独处理。</p>
          <label v-if="pending === 'REGISTER'">核验材料编号<input v-model="reference" maxlength="128" required :disabled="saving" /></label><label>核对说明<textarea v-model="comment" rows="3" maxlength="2000" required :disabled="saving" /></label>
          <label v-if="pending === 'REGISTER'" class="ack"><input v-model="acknowledged" type="checkbox" required :disabled="saving" />已核对累计入款与本次新增金额</label>
          <div class="buttons"><button type="submit" class="primary" :disabled="blocked || !allowed(pending) || pending === 'REGISTER' && !acknowledged">{{ saving ? '正在登记…' : pending === 'QUERY' ? '登记回款查询' : '确认并保存回款' }}</button><button type="button" class="quiet" :disabled="saving" @click="pending = null; reference = ''; comment = ''; acknowledged = false">取消</button></div>
        </form>
      </template>
    </template>
  </section>
</template>

<style scoped>
.supplier-return{margin-top:18px;padding:16px;border:1px solid var(--line);border-radius:10px;background:var(--paper);min-width:0;overflow-wrap:anywhere}.heading{display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap}h4{font-size:13px;margin:0}p{font-size:12px;line-height:1.8}.error{color:var(--red)}.review{padding:10px 12px;border-left:3px solid var(--teal);background:white}.amounts{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px;margin:16px 0}.amounts article{padding:12px;border:1px solid var(--line);border-radius:8px;background:white}.amounts small{display:block;font-size:11px;color:var(--muted)}.amounts strong{display:block;margin-top:8px;font-size:15px;font-variant-numeric:tabular-nums}.evidence,form{margin-top:14px;padding-top:14px;border-top:1px solid var(--line)}.evidence>strong{font-size:13px}dl{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px}dt{font-size:11px;color:var(--muted);margin-bottom:6px}dd{margin:0;font-size:12px;font-variant-numeric:tabular-nums}label{display:grid;gap:7px;font-size:12px;margin-top:12px}input,textarea{width:100%;padding:10px;border:1px solid var(--line);border-radius:7px;background:white;font:inherit}textarea{resize:vertical}.ack{display:flex;align-items:center;gap:9px}.ack input{width:18px;height:18px}.buttons{display:flex;gap:10px;flex-wrap:wrap;margin-top:14px}button{min-height:40px}summary{font-size:12px;cursor:pointer;padding:9px 0}@media(max-width:650px){dl,.amounts{grid-template-columns:1fr}}
</style>
