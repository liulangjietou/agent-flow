<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import { api } from '../api'
import { ExpensePageQuery, type ExpenseContent } from '../expenses'
import { fillExpenseLineFromInvoice, invoiceFillChoices, invoiceFillCurrencyConfirmation, type InvoiceFillField } from '../expenseDraft'
import { extractionError, extractionFields, extractionMatches, type ExtractionDetail, type ExtractionPage } from '../invoiceExtraction'
import type { InvoiceItem } from '../invoiceWallet'

const content = defineModel<ExpenseContent>({ required: true })
const props = defineProps<{ scopeKey: string; reportId?: string; locked?: boolean }>()
const emit = defineEmits<{ busy: [value: boolean] }>()
const open = ref(false), lineNo = ref(0), invoiceId = ref('')
const invoice = ref<InvoiceItem | null>(null), history = ref<ExtractionPage | null>(null), detail = ref<ExtractionDetail | null>(null)
const selected = ref<InvoiceFillField[]>([]), currencyConfirmed = ref(false), loading = ref(false), applying = ref(false), error = ref(''), notice = ref('')
let active = true, generation = 0, applyGeneration = 0, controller: AbortController | null = null
const invoices = reactive(new ExpensePageQuery(async (filter, signal) => {
  const original = context.value
  try { return await api.invoices(filter, signal) }
  catch (cause) { if (active && original === context.value && [401, 403, 404].includes((cause as { status?: number })?.status ?? 0)) clearSource(); throw cause }
}))
const context = computed(() => JSON.stringify([props.scopeKey, props.reportId, content.value.legalEntityId]))
const target = computed(() => content.value.lines.find(line => line.lineNo === lineNo.value))
const choices = computed(() => detail.value && target.value ? invoiceFillChoices(detail.value, target.value) : [])
const needsCurrencyConfirmation = computed(() => !!target.value && !!detail.value && invoiceFillCurrencyConfirmation(target.value, detail.value, selected.value))
const selectedCurrency = computed(() => selected.value.includes('CURRENCY') ? detail.value?.review?.selected?.find(field => field.field === 'CURRENCY')?.value : target.value?.claimedGross.currency)
const locked = computed(() => !!props.locked || applying.value)
const canApply = computed(() => !locked.value && !loading.value && !!target.value && !!detail.value && !!invoice.value && selected.value.length > 0
  && (!needsCurrencyConfirmation.value || currencyConfirmed.value))
const confirmedRuns = computed(() => history.value?.items.filter(run => run.status === 'CONFIRMED') ?? [])
const date = (value: string) => new Date(value).toLocaleString('zh-CN')

function stopRead() { generation++; controller?.abort(); controller = null; loading.value = false }
function clearSource() {
  stopRead(); applyGeneration++; invoiceId.value = ''; invoice.value = null; history.value = null; detail.value = null
  selected.value = []; currencyConfirmed.value = false; applying.value = false; error.value = ''; notice.value = ''
}
/** 来源读取只服务于当前账号、单据与选择；超时及迟到响应不能修改费用输入。 */
async function read<T>(fetch: (signal: AbortSignal) => Promise<T>, use: (value: T) => void) {
  stopRead(); const version = generation, original = context.value, request = new AbortController(); controller = request
  const current = () => active && version === generation && original === context.value
  loading.value = true; error.value = ''; let timer: ReturnType<typeof setTimeout> | undefined
  const timeout = new Promise<never>((_, reject) => {
    timer = setTimeout(() => { request.abort(); reject(new Error('来源读取超时，请重新核对原记录。')) }, 12_000)
  })
  try { const value = await Promise.race([fetch(request.signal), timeout]); if (current()) use(value) }
  catch (cause) {
    if (current()) {
      if ([401, 403, 404].includes((cause as { status?: number })?.status ?? 0)) { clearSource(); invoices.clear() }
      error.value = extractionError(cause)
    }
  } finally { clearTimeout(timer); if (current()) { loading.value = false; controller = null } }
}
function loadInvoices(more = false) { if (!locked.value && props.scopeKey) return invoices.load(props.scopeKey, undefined, more) }
async function selectInvoice(id: string, page = 0) {
  if (locked.value) return
  clearSource(); invoiceId.value = id
  await read(signal => Promise.all([api.invoice(id, signal), api.invoiceExtractionRuns(id, page, signal)]), ([item, result]) => {
    if (item.id !== id || item.original.status !== 'READY') throw new Error('原件当前不可读取，请返回票夹核对。')
    invoice.value = item; history.value = result
  })
}
async function selectRun(id: string) {
  if (locked.value || !invoice.value || !confirmedRuns.value.some(run => run.id === id)) return
  const original = invoice.value
  detail.value = null; selected.value = []; currencyConfirmed.value = false; notice.value = ''
  await read(signal => api.invoiceExtractionRun(original.id, id, signal), value => {
    if (value.status !== 'CONFIRMED' || !extractionMatches(value.input, original)) throw new Error('这条记录当前不可用于填报，请重新核对本人确认结果。')
    detail.value = value
  })
}
/** 带入前重新读取本人来源；等待期间任何行修改或来源变化都要求重新选择。 */
async function apply() {
  if (locked.value || loading.value || !target.value || !invoice.value || !detail.value) return
  const row = target.value, before = JSON.stringify(row), source = invoice.value, run = detail.value, fields = [...selected.value], currencyConsent = currencyConfirmed.value
  try { fillExpenseLineFromInvoice(row, source, run, fields, currencyConsent) }
  catch (cause) { error.value = extractionError(cause); return }
  const attempt = ++applyGeneration
  applying.value = true; notice.value = ''
  try {
    await read(signal => Promise.all([api.invoice(source.id, signal), api.invoiceExtractionRun(source.id, run.id, signal)]), ([freshInvoice, freshRun]) => {
      if (props.locked || target.value?.lineNo !== row.lineNo || JSON.stringify(target.value) !== before) throw new Error('目标费用行已修改，本次没有带入，请重新核对。')
      if (freshRun.version !== run.version || JSON.stringify(freshRun.review) !== JSON.stringify(run.review)) throw new Error('本人确认记录已变化，本次没有带入，请重新选择。')
      const filled = fillExpenseLineFromInvoice(row, freshInvoice, freshRun, fields, currencyConsent)
      content.value = { ...content.value, lines: content.value.lines.map(line => line.lineNo === row.lineNo ? filled : line) }
      invoice.value = freshInvoice; detail.value = freshRun; selected.value = []; currencyConfirmed.value = false
      notice.value = `已将勾选字段带入第 ${row.lineNo} 行，尚未保存。请核对费用日期、可抵扣税额和分摊金额，再保存草稿。`
    })
  } finally { if (active && attempt === applyGeneration) applying.value = false }
}
watch(context, () => { clearSource(); invoices.clear(); open.value = false; lineNo.value = 0 }, { flush: 'sync' })
watch(open, value => { clearSource(); invoices.clear(); if (value) void loadInvoices() })
watch(lineNo, () => { selected.value = []; currencyConfirmed.value = false; error.value = ''; notice.value = '' }, { flush: 'sync' })
watch(() => [target.value?.claimedGross.currency, selected.value.join(',')], () => { currencyConfirmed.value = false }, { flush: 'sync' })
watch(applying, value => emit('busy', value), { flush: 'sync' })
onUnmounted(() => { active = false; stopRead(); invoices.clear(); emit('busy', false) })
</script>

<template>
  <section class="expense-invoice-assist" aria-label="已确认票面辅助填报">
    <button type="button" class="secondary" :disabled="locked || !content.lines.length" :aria-expanded="open" @click="open = !open">{{ open ? '收起票面填报参考' : '从已确认票面带入金额或币种' }}</button>
    <div v-if="open" class="fill-body">
      <div class="fill-heading"><h4>选择来源，对照后带入</h4><span>仅修改未保存输入</span></div>
      <p class="fill-help">请先在个人票夹提取并确认票面。这里使用你保存的本人确认值；正式引用发票仍需先查验，再从下方原件选择中添加。</p>
      <label class="fill-target">带入费用行<select v-model="lineNo" :disabled="locked"><option :value="0">请选择费用行</option><option v-for="line in content.lines" :key="line.lineNo" :value="line.lineNo">第 {{ line.lineNo }} 行 · {{ line.description || '尚未填写说明' }}</option></select></label>
      <p v-if="error" class="fill-error" role="alert">{{ error }}</p><p v-if="notice" class="fill-notice" role="status">{{ notice }}</p>
      <div class="fill-columns">
        <section aria-label="选择本人票面来源"><div class="fill-toolbar"><h5>本人票夹</h5><button type="button" class="quiet" :disabled="locked || invoices.loading" @click="loadInvoices()">刷新票面来源</button></div>
          <p v-if="invoices.error" class="fill-error" role="alert">{{ invoices.error }}</p>
          <p v-if="invoices.loading" class="fill-help" role="status">正在读取本人票夹…</p>
          <ul class="fill-invoices"><li v-for="item in invoices.items" :key="item.id"><button type="button" :aria-pressed="invoiceId === item.id" :disabled="locked || item.original.status !== 'READY'" @click="selectInvoice(item.id)"><strong>{{ item.original.filename }}</strong><span>{{ item.original.format }} · {{ item.original.status === 'READY' ? '查看已确认票面' : '原件尚未就绪' }}</span></button></li></ul>
          <p v-if="!invoices.loading && !invoices.error && !invoices.items.length" class="fill-help">票夹暂无原件，请先在个人票夹完成提取和确认。</p>
          <button v-if="invoices.nextBeforeId" type="button" class="quiet" :disabled="locked || invoices.loading" @click="loadInvoices(true)">加载更多票面来源</button>
        </section>
        <section aria-label="选择本人确认记录">
          <h5>本人确认记录</h5><p v-if="!invoiceId" class="fill-help">先选择一份票据，再选择你已确认的记录。</p>
          <p v-if="loading" class="fill-help" role="status">正在核对本人来源…</p>
          <template v-if="history"><ul class="fill-invoices"><li v-for="run in confirmedRuns" :key="run.id"><button type="button" :disabled="locked || loading" :aria-pressed="detail?.id === run.id" @click="selectRun(run.id)"><strong>{{ date(run.createdAt) }}</strong><span>本人已确认 · 版本 {{ run.version }}</span></button></li></ul>
            <p v-if="!confirmedRuns.length" class="fill-help">本页没有本人已确认的记录；未复核或已放弃的结果不能带入。</p>
            <div v-if="history.total > history.pageSize || history.page > 0" class="fill-toolbar"><button type="button" :disabled="locked || loading || history.page === 0" @click="selectInvoice(invoiceId, history.page - 1)">上一页</button><span>第 {{ history.page + 1 }} 页</span><button type="button" :disabled="locked || loading || (history.page + 1) * history.pageSize >= history.total" @click="selectInvoice(invoiceId, history.page + 1)">下一页</button></div>
          </template>
        </section>
      </div>
      <section v-if="detail" class="fill-review" aria-label="核对带入字段">
        <p class="fill-help">来源：{{ invoice?.original.filename }} · {{ detail.review?.at ? date(detail.review.at) : '' }} 本人确认</p>
        <details><summary>查看已确认票面与来源摘录</summary><dl><template v-for="field in detail.review?.selected" :key="field.field"><dt>{{ extractionFields[field.field] }}</dt><dd>{{ field.value }}<small>原始提取：{{ detail.suggestion?.proposals.find(proposal => proposal.field === field.field)?.value }}</small><blockquote v-for="(source, index) in detail.suggestion?.proposals.find(proposal => proposal.field === field.field)?.evidence" :key="index">{{ source.quote }}</blockquote></dd></template></dl><p class="fill-reference">确认记录 {{ detail.id }} · 原件 {{ detail.input.originalId }}</p></details>
        <p class="fill-help">开票日期不等于费用发生日期；票面税额不等于可抵扣税额。上述字段请结合实际业务填写。</p>
        <p v-if="!target" class="fill-help">请选择要带入的费用行。</p>
        <template v-else>
          <label v-for="choice in choices" :key="choice.field" class="fill-choice"><input v-model="selected" type="checkbox" :value="choice.field" :disabled="locked || loading || !!choice.unavailable" :aria-label="`带入${choice.label}`" /><span><strong>{{ choice.label }}</strong><span>当前：{{ choice.current || '未填写' }} → 本人确认：{{ choice.value }}</span><small v-if="choice.unavailable">{{ choice.unavailable }}</small></span></label>
          <p v-if="!choices.length" class="fill-help">这条记录未确认可带入的金额或币种，其他票面信息可供人工核对。</p>
          <label v-if="needsCurrencyConfirmation" class="fill-choice fill-currency"><input v-model="currencyConfirmed" type="checkbox" :disabled="locked || loading" /><span>我已核对所选金额及本行税额、分摊均按 {{ selectedCurrency || '待填写币种' }} 填写；本次不换算汇率</span></label>
          <button type="button" class="primary" :disabled="!canApply" @click="apply">{{ applying ? '正在重新核对来源…' : '带入勾选字段，继续编辑' }}</button>
        </template>
      </section>
    </div>
  </section>
</template>

<style scoped>
.expense-invoice-assist{margin:22px 0;font-size:12px;line-height:1.8;min-width:0}.fill-body{margin-top:14px;padding:20px;background:var(--paper);border:1px solid var(--line);border-radius:10px}.fill-heading,.fill-toolbar{display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap}.fill-heading h4{font-size:16px;margin:0}.fill-heading>span{color:var(--deep);background:var(--soft);padding:4px 8px;border-radius:5px}.fill-help{color:var(--muted);margin:10px 0;overflow-wrap:anywhere}.fill-target{display:grid;gap:8px;margin:16px 0}.fill-target select{width:100%;min-width:0;padding:10px;border:1px solid var(--line);border-radius:7px;background:white;color:var(--ink)}.fill-columns{display:grid;grid-template-columns:1fr 1fr;gap:20px;padding:12px 0}.fill-columns section{min-width:0}.fill-columns h5{font-size:13px;margin:0}.fill-invoices{padding:0;list-style:none;margin:10px 0;max-height:270px;overflow:auto}.fill-invoices li+li{margin-top:7px}.fill-invoices button{width:100%;text-align:left;padding:12px;border:1px solid var(--line);border-radius:7px;background:white;font-size:12px}.fill-invoices button[aria-pressed=true]{border-color:var(--deep);background:var(--soft)}.fill-invoices strong,.fill-invoices span{display:block;overflow-wrap:anywhere}.fill-invoices span{font-size:11px;color:var(--muted)}.fill-review{margin-top:14px;border-top:1px solid var(--line);padding-top:14px}.fill-review summary{cursor:pointer;color:var(--deep)}.fill-review dl{display:grid;grid-template-columns:120px minmax(0,1fr);gap:10px}.fill-review dd{margin:0;overflow-wrap:anywhere;white-space:pre-wrap}.fill-review small{display:block;color:var(--muted)}.fill-review blockquote{margin:6px 0;border-left:2px solid var(--teal);padding-left:10px}.fill-reference{font-size:10px;overflow-wrap:anywhere;color:var(--muted)}.fill-choice{display:flex;gap:9px;align-items:flex-start;margin:15px 0;cursor:pointer}.fill-choice input{flex-shrink:0;width:16px;height:16px;margin-top:4px;accent-color:var(--deep)}.fill-choice>span{min-width:0;overflow-wrap:anywhere}.fill-choice strong,.fill-choice span>span{display:block}.fill-choice span>span{color:var(--muted)}.fill-currency{padding:12px;background:#fff9ef;border-radius:7px}.fill-error{color:var(--red)}.fill-notice{color:var(--deep);padding:12px;background:var(--soft);border-radius:7px}.expense-invoice-assist :is(button,input,select,summary):focus-visible{outline:3px solid var(--teal);outline-offset:3px}@media(max-width:700px){.fill-body{padding:14px}.fill-columns{grid-template-columns:1fr;gap:18px}.fill-review dl{grid-template-columns:1fr;gap:4px}.fill-review dd{margin-bottom:10px}}
</style>
