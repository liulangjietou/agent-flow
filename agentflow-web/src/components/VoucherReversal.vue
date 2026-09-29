<script setup lang="ts">
import { computed, nextTick, onUnmounted, ref, watch } from 'vue'
import { api, writeRequests } from '../api'
import { moneyLabel } from '../expenses'
import { reversalCheckLabels, reversalIssue, reversalError, validateVoucherReversal, reversalQueryInput, reversalRecordInput, validateReversalReceipt, type VoucherReversalBinding, type VoucherReversalView, type ReversalQueryInput, type ReversalRecordInput } from '../voucherReversal'

const props = defineProps<{ applicationId: string; operationId: string; roundNo: number; applicationVersion: number; businessVersion: number; operationVersion: number; kind: 'EMPLOYEE_ADVANCE' | 'EXPENSE_ACCRUAL' | 'PAYMENT'; scopeKey: string; locked?: boolean }>()
const emit = defineEmits<{ busy: [value: boolean]; recorded: [] }>()
const view = ref<VoucherReversalView | null>(null), loading = ref(false), saving = ref(false), pending = ref<'QUERY' | 'RECORD' | null>(null)
const reference = ref(''), comment = ref(''), error = ref(''), notice = ref(''), requiresRefresh = ref(false), unconfirmed = ref(false), form = ref<HTMLFormElement | null>(null)
let epoch = 0, controller: AbortController | null = null
const blocked = computed(() => !!props.locked || loading.value || saving.value || requiresRefresh.value || unconfirmed.value)
const posting = computed(() => view.value?.record?.reversal ?? view.value?.latestCheck?.evidence?.reversal)
function stop() { epoch++; controller?.abort(); controller = null }
function clearMaterials() { view.value = null; pending.value = null; reference.value = ''; comment.value = '' }
function syncPending() {
  const prefix = `/applications/${encodeURIComponent(props.applicationId)}/vouchers/${encodeURIComponent(props.operationId)}/reversal`
  const current = writeRequests.pending().some(entry => entry.path === prefix + '/checks' || entry.path === prefix + '/records')
  if (unconfirmed.value && !current) requiresRefresh.value = true
  unconfirmed.value = current
}
const unsubscribe = writeRequests.subscribe(syncPending)
/** 原轮次或身份变化立即清除材料，迟到响应不能恢复旧凭证。 */
async function load() {
  if (saving.value || loading.value || !props.scopeKey) return
  stop(); const current = epoch, request = new AbortController(); controller = request
  const binding: VoucherReversalBinding = { applicationId: props.applicationId, operationId: props.operationId, roundNo: props.roundNo, applicationVersion: props.applicationVersion, businessVersion: props.businessVersion, operationVersion: props.operationVersion, kind: props.kind }
  loading.value = true; clearMaterials(); error.value = ''
  const timeout = setTimeout(() => { if (current === epoch) { stop(); loading.value = false; requiresRefresh.value = true; error.value = '独立冲销读取超时，请刷新重试。' } }, 12_000)
  try {
    const result = await api.voucherReversal(binding.applicationId, binding.operationId, binding.roundNo, request.signal)
    if (current !== epoch) return
    view.value = validateVoucherReversal(result, binding); requiresRefresh.value = false; syncPending()
  } catch (cause) { if (current === epoch) { clearMaterials(); error.value = reversalError(cause); requiresRefresh.value = true } }
  finally { clearTimeout(timeout); if (current === epoch) { loading.value = false; controller = null } }
}
function prepare(action: 'QUERY' | 'RECORD') {
  if (blocked.value || !(action === 'QUERY' ? view.value?.canQuery : view.value?.latestCheck?.canRecord)) return
  pending.value = action; reference.value = ''; comment.value = ''; error.value = ''; notice.value = ''; const current = epoch
  void nextTick(() => { if (current === epoch) form.value?.querySelector<HTMLInputElement | HTMLTextAreaElement>('input,textarea')?.focus() })
}
/** 人工只采纳已展示反向分录，未知写入结果沿原幂等请求恢复。 */
function isRecord(input: ReversalQueryInput | ReversalRecordInput): input is ReversalRecordInput { return 'checkId' in input }
async function execute() {
  const value = view.value, action = pending.value; if (!value || !action || blocked.value) return
  let input
  try { input = action === 'QUERY' ? reversalQueryInput(value, comment.value) : reversalRecordInput(value, reference.value, comment.value) }
  catch (cause) { error.value = reversalError(cause); return }
  const current = epoch; saving.value = true; error.value = ''; emit('busy', true)
  try {
    const result = isRecord(input) ? await api.recordVoucherReversal(value.applicationId, value.operationId, input) : await api.queryVoucherReversal(value.applicationId, value.operationId, input)
    if (current !== epoch) return
    validateReversalReceipt(result, value, input); pending.value = null; saving.value = false; emit('busy', false)
    notice.value = action === 'QUERY' ? '冲销核验已排队，请刷新查看反向分录，再核对登记。' : '独立冲销凭证已登记，原资金与业务占用继续分别核对。'
    await load()
    if (action === 'RECORD' && view.value?.record) emit('recorded')
  } catch (cause) {
    if (current === epoch) {
      if ([401, 403, 404].includes((cause as { status?: number })?.status ?? 0)) clearMaterials()
      error.value = reversalError(cause); requiresRefresh.value = true
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
  <section class="voucher-reversal" aria-label="独立冲销凭证">
    <div class="heading"><h4>独立冲销凭证</h4><button type="button" class="quiet" :disabled="loading || saving || locked" @click="notice = ''; load()">刷新独立冲销</button></div>
    <p>核对原凭证之外实际过账的反向分录。登记后，原借款、银行付款、报销占用与封存档案仍按各自依据办理。</p>
    <p v-if="loading" role="status">正在读取冲销核验与登记记录…</p><p v-if="error" class="error" role="alert">{{ error }}</p><p v-if="notice" role="status">{{ notice }}</p>
    <p v-if="unconfirmed && !saving" class="error" role="alert">上次冲销操作结果尚未确认，请先恢复原操作，再刷新核对。</p>
    <template v-if="view">
      <p>原凭证 {{ view.original.voucherReference }} · 单边合计 {{ moneyLabel(view.original.total) }}</p>
      <p v-if="view.record" class="recorded"><strong>独立冲销已登记</strong><br />{{ view.record.recordedBy }} · {{ new Date(view.record.recordedAt).toLocaleString() }} · 材料 {{ view.record.evidenceReference }}</p>
      <p v-else>尚无独立冲销登记。原凭证显示已冲销，还需要核对另一张凭证的全部反向分录。</p>
      <article v-if="view.latestCheck && !view.record" class="evidence">
        <strong>{{ reversalCheckLabels[view.latestCheck.status] }}</strong>
        <p v-if="view.latestCheck.evidence">{{ view.latestCheck.evidence.status === 'VERIFIED' ? '完整反向凭证已核实，等待财务确认。' : 'ERP 尚未提供完整、可登记的反向凭证。' }}<br />依据有效至 {{ new Date(view.latestCheck.evidence.validUntil).toLocaleString() }}</p>
        <p v-if="view.latestCheck.issue">{{ reversalIssue(view.latestCheck.issue) }}</p><p v-else-if="view.latestCheck.confirmationIssue && view.latestCheck.status === 'CHECKED'">{{ reversalIssue(view.latestCheck.confirmationIssue) }}</p>
      </article>
      <template v-if="posting">
        <dl><div><dt>反向凭证号</dt><dd>{{ posting.voucherReference }}</dd></div><div><dt>反向过账号</dt><dd>{{ posting.postingReference }}</dd></div><div><dt>会计期间 / 日期</dt><dd>{{ posting.periodReference }} / {{ posting.accountingDate }}</dd></div><div><dt>实际过账时间</dt><dd>{{ new Date(posting.postedAt).toLocaleString() }}</dd></div></dl>
        <ol class="lines" aria-label="完整反向分录"><li v-for="line in posting.lines" :key="line.originalLineNo"><strong>原第 {{ line.originalLineNo }} 行 · {{ line.side === 'DEBIT' ? '借方' : '贷方' }} {{ moneyLabel(line.amount) }}</strong><span>科目 {{ line.accountCode }} · 分录 {{ line.entryReference }}</span><span v-if="line.sourceLineNo">业务行 {{ line.sourceLineNo }}</span><span v-if="line.costCenter">成本中心 {{ line.costCenter }}</span><span v-if="line.projectCode">项目 {{ line.projectCode }}</span><span v-if="line.advanceId">借款 {{ line.advanceId }}</span></li></ol>
      </template>
      <div v-if="!pending" class="buttons"><button v-if="view.canQuery" type="button" class="quiet" :disabled="blocked" @click="prepare('QUERY')">核验独立冲销凭证</button><button v-if="view.latestCheck?.canRecord" type="button" class="primary" :disabled="blocked" @click="prepare('RECORD')">核对并登记冲销</button></div>
      <form v-else ref="form" @submit.prevent="execute">
        <h4>{{ pending === 'QUERY' ? '读取 ERP 独立冲销凭证' : '登记已核实的反向凭证' }}</h4>
        <p>{{ pending === 'QUERY' ? '读取这张原凭证对应的另一张完整反向凭证，查询完成后再核对确认。' : '采用上方全部反向分录，保存不可变的冲销登记。登记完成后不能将原凭证重新确认成有效过账。' }}</p>
        <label v-if="pending === 'RECORD'">核验材料编号<input v-model="reference" maxlength="128" required :disabled="saving" /></label>
        <label>核对说明<textarea v-model="comment" rows="3" maxlength="2000" required :disabled="saving" /></label>
        <div class="buttons"><button class="primary" :disabled="blocked">{{ saving ? '正在登记…' : pending === 'QUERY' ? '登记冲销核验查询' : '确认并保存冲销登记' }}</button><button type="button" class="quiet" :disabled="saving" @click="pending = null; reference = ''; comment = ''">取消</button></div>
      </form>
    </template>
  </section>
</template>

<style scoped>
.voucher-reversal{margin-top:18px;padding:16px;border:1px solid var(--line);border-radius:10px;background:var(--paper);min-width:0;overflow-wrap:anywhere}.heading{display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap}h4{font-size:13px;margin:0}p{font-size:12px;line-height:1.8}.error{color:var(--red)}.recorded{padding:12px;background:var(--soft);border-radius:8px}.evidence,form{margin-top:14px;padding-top:14px;border-top:1px solid var(--line)}.evidence>strong{font-size:13px}dl{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px}dt{font-size:11px;color:var(--muted);margin-bottom:6px}dd{margin:0;font-size:12px;font-variant-numeric:tabular-nums}.lines{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px;padding:0;list-style:none}.lines li{display:grid;gap:6px;padding:12px;border:1px solid var(--line);border-radius:8px;font-size:11px;line-height:1.7}.lines strong{font-size:12px}label{display:grid;gap:7px;font-size:12px;margin-top:12px}input,textarea{width:100%;padding:10px;border:1px solid var(--line);border-radius:7px;background:white;font:inherit}textarea{resize:vertical}.buttons{display:flex;gap:10px;flex-wrap:wrap;margin-top:14px}button{min-height:40px}@media(max-width:650px){dl,.lines{grid-template-columns:1fr}}
</style>
