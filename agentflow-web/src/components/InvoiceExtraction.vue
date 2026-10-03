<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import { api } from '../api'
import type { InvoiceItem } from '../invoiceWallet'
import { extractionDrafts, extractionError, extractionFields, extractionMatches, extractionMethods, extractionPath, extractionSelections,
  extractionStatuses, acknowledgeExtraction, focusedExtraction, rememberExtraction,
  type ExtractionOptions, type ExtractionPage, type ExtractionDetail, type ExtractionField, type ExtractionReview } from '../invoiceExtraction'

const props = defineProps<{ item: InvoiceItem; scopeKey: string; refreshVersion: number; locked?: boolean }>()
const emit = defineEmits<{ busy: [value: boolean]; dirty: [value: boolean] }>()
const options = ref<ExtractionOptions | null>(null), page = ref<ExtractionPage | null>(null), detail = ref<ExtractionDetail | null>(null)
const selectedId = ref(''), externalSendConfirmed = ref(false), selected = ref<ExtractionField[]>([]), values = ref<Partial<Record<ExtractionField, string>>>({})
const comment = ref(''), initialValues = ref('{}'), editVersion = ref(0), sending = ref(false), error = ref(''), notice = ref('')
const loading = reactive({ input: false, list: false, detail: false }), errors = reactive({ input: '', list: '', detail: '' })
type Resource = keyof typeof loading
const requests: Record<Resource, { generation: number; controller: AbortController | null }> = {
  input: { generation: 0, controller: null }, list: { generation: 0, controller: null }, detail: { generation: 0, controller: null }
}
let active = true, restoring = false
const context = computed(() => JSON.stringify([props.scopeKey, props.item.id]))
const dirty = computed(() => selected.value.length > 0 || comment.value.length > 0 || JSON.stringify(values.value) !== initialValues.value)
const locked = computed(() => !!props.locked || sending.value)
const running = computed(() => page.value?.items.some(item => item.status === 'QUEUED' || item.status === 'RUNNING') || detail.value?.status === 'QUEUED' || detail.value?.status === 'RUNNING')
const canGenerate = computed(() => !locked.value && !dirty.value && !loading.input && !loading.list && !running.value && !!options.value?.enabled
  && extractionMatches(options.value.input, props.item) && (options.value.method === 'STRUCTURED_XML' || externalSendConfirmed.value))
const canNavigate = computed(() => !locked.value && !dirty.value)
const canReview = computed(() => !locked.value && !loading.detail && detail.value?.status === 'COMPLETED' && editVersion.value === detail.value.version)
const canConfirm = computed(() => canReview.value && !!detail.value?.canConfirm && extractionMatches(detail.value.input, props.item))
const staleEdits = computed(() => dirty.value && detail.value !== null && editVersion.value !== detail.value.version)
const confidenceLabels = { LOW: '低', MEDIUM: '中', HIGH: '高' }
const date = (value: string) => new Date(value).toLocaleString('zh-CN')

function abortReads() {
  for (const name of Object.keys(requests) as Resource[]) { requests[name].generation++; requests[name].controller?.abort(); loading[name] = false }
}
function resetReview(restore = true) {
  restoring = true
  values.value = Object.fromEntries((detail.value?.suggestion?.proposals ?? []).map(p => [p.field, p.value]))
  initialValues.value = JSON.stringify(values.value); selected.value = []; comment.value = ''; editVersion.value = detail.value?.version ?? 0
  const draft = restore ? extractionDrafts.get(props.scopeKey, props.item.id) : null
  if (draft && draft.runId === detail.value?.id) { values.value = draft.values; selected.value = draft.selected; comment.value = draft.comment; editVersion.value = draft.version }
  restoring = false
}
function reset() {
  abortReads(); options.value = null; page.value = null; detail.value = null; selectedId.value = ''; externalSendConfirmed.value = false
  resetReview(false); sending.value = false; error.value = ''; notice.value = ''; errors.input = ''; errors.list = ''; errors.detail = ''
}
/** 每类读取有独立序号和超时；身份变化、旧票据和迟到响应不能回填。 */
async function read<T>(name: Resource, fetch: (signal: AbortSignal) => Promise<T>, apply: (value: T) => void) {
  if (!active) return
  const slot = requests[name], generation = ++slot.generation, original = context.value
  slot.controller?.abort(); const controller = new AbortController(); slot.controller = controller
  loading[name] = true; errors[name] = ''; let timedOut = false
  const current = () => active && original === context.value && slot.generation === generation
  let timer: ReturnType<typeof setTimeout> | undefined
  const timeout = new Promise<never>((_, reject) => {
    timer = setTimeout(() => {
      timedOut = true; controller.abort()
      if (current()) { loading[name] = false; errors[name] = '读取超时，请刷新原记录。' }
      reject(new Error('读取超时，请刷新原记录。'))
    }, 12_000)
  })
  try { const value = await Promise.race([fetch(controller.signal), timeout]); if (current() && !timedOut) apply(value) }
  catch (cause) {
    if (current() && !timedOut) {
      const status = (cause as { status?: number })?.status
      if (status === 401 || status === 403 || status === 404) reset()
      errors[name] = extractionError(cause)
    }
  } finally { clearTimeout(timer); if (current()) loading[name] = false }
}
function loadInput() {
  options.value = null; externalSendConfirmed.value = false
  return read('input', signal => api.invoiceExtractionInput(props.item.id, signal), value => {
    if (!extractionMatches(value.input, props.item)) throw new Error('原件身份已变化，请先刷新票据。')
    options.value = value
  })
}
async function loadPage(number = 0, afterWrite = false) {
  if (!active || !afterWrite && !canNavigate.value) return
  page.value = null
  await read('list', signal => api.invoiceExtractionRuns(props.item.id, number, signal), value => { page.value = value })
}
async function loadDetail(id: string, afterWrite = false) {
  if (!active || !afterWrite && !canNavigate.value) return
  selectedId.value = id; detail.value = null; resetReview(false)
  rememberExtraction(props.scopeKey, props.item.id, id)
  await read('detail', signal => api.invoiceExtractionRun(props.item.id, id, signal), value => {
    detail.value = value; resetReview()
    const row = page.value?.items.find(row => row.id === value.id)
    if (row) { row.status = value.status; row.version = value.version }
  })
}
/** 刷新父票据或恢复原请求时保留未发送修订，由原版本决定是否仍可确认。 */
function refresh() {
  error.value = ''; notice.value = ''
  const original = context.value
  void loadInput(); void loadPage(page.value?.page ?? 0, true).then(() => {
    if (!active || original !== context.value) return
    const id = focusedExtraction(props.scopeKey, props.item.id) || selectedId.value
    if (id) void loadDetail(id, true)
  })
}
function discardEdits() {
  if (locked.value) return
  extractionDrafts.clear(props.scopeKey, props.item.id); resetReview(false); error.value = ''; notice.value = '已清除本地修订，原始提取记录仍保留。'
}
async function generate() {
  if (!canGenerate.value || !options.value) return
  const original = context.value, invoiceId = props.item.id, available = options.value
  sending.value = true; error.value = ''; notice.value = ''
  try {
    const receipt = await api.generateInvoiceExtraction(invoiceId, { expectedOriginalId: available.input.originalId, expectedOriginalDigest: available.input.originalDigest,
      method: available.method, targetDigest: available.targetDigest, externalSendConfirmed: available.method === 'MODEL' && externalSendConfirmed.value })
    if (!active || original !== context.value) return
    externalSendConfirmed.value = false; rememberExtraction(props.scopeKey, invoiceId, receipt.id)
    notice.value = '已登记提取任务。可刷新本条结果，完成后仍需逐字段核对。'
    await loadPage(0, true)
    if (active && original === context.value) await loadDetail(receipt.id, true)
  } catch (cause) { if (active && original === context.value) { error.value = extractionError(cause); externalSendConfirmed.value = false } }
  finally { if (active && original === context.value) sending.value = false }
}
async function review(action: 'CONFIRM' | 'DISMISS') {
  if (!canReview.value || !detail.value || action === 'CONFIRM' && !canConfirm.value) return
  const original = context.value, invoiceId = props.item.id, id = detail.value.id
  let body: ExtractionReview
  try {
    if (comment.value.length > 2000) throw new Error('核对说明最多 2,000 字符。')
    body = { expectedRunVersion: editVersion.value, action, comment: comment.value,
      ...(action === 'CONFIRM' ? { selected: extractionSelections(detail.value, selected.value, values.value) } : {}) }
  } catch (cause) { error.value = extractionError(cause); return }
  sending.value = true; error.value = ''; notice.value = ''
  try {
    const receipt = await api.reviewInvoiceExtraction(invoiceId, id, body)
    if (!active || original !== context.value) return
    acknowledgeExtraction(props.scopeKey, extractionPath(invoiceId) + '/' + encodeURIComponent(id) + '/review', JSON.stringify(body), receipt)
    resetReview(false)
    notice.value = action === 'CONFIRM' ? '已保存勾选字段的本人确认值。发票查验、报销占用和财务金额保持不变。' : '已放弃本次结果，原始提取内容仍保留。'
    await loadPage(page.value?.page ?? 0, true)
    if (active && original === context.value) await loadDetail(id, true)
  } catch (cause) { if (active && original === context.value) error.value = extractionError(cause) }
  finally { if (active && original === context.value) sending.value = false }
}
watch([selected, values, comment], () => {
  if (restoring || !detail.value) return
  if (dirty.value) extractionDrafts.set(props.scopeKey, props.item.id, { runId: detail.value.id, version: editVersion.value, selected: selected.value, values: values.value, comment: comment.value })
  else extractionDrafts.clear(props.scopeKey, props.item.id)
}, { deep: true, flush: 'sync' })
watch(context, () => { reset(); if (props.scopeKey) refresh() }, { immediate: true, flush: 'sync' })
watch(() => props.refreshVersion, refresh)
watch(() => [props.item.original.id, props.item.original.sha256, props.item.original.status], () => { void loadInput() })
watch(sending, value => emit('busy', value), { flush: 'sync' })
watch(dirty, value => emit('dirty', value), { immediate: true, flush: 'sync' })
onUnmounted(() => { active = false; abortReads(); emit('busy', false); emit('dirty', false) })
</script>

<template>
  <section class="invoice-extraction" aria-label="票面提取与本人复核">
    <div class="extraction-heading"><div><h3>提取票面，逐项核对</h3><p>对照原件保留你确认的字段，查验结论仍以正式查验为准。</p></div><span class="extraction-tag">本人确认后保存</span></div>
    <p v-if="error" class="extraction-error" role="alert">{{ error }}</p><p v-if="notice" class="extraction-notice" role="status">{{ notice }}</p>
    <section class="extraction-source" aria-label="本次处理来源">
      <div class="extraction-toolbar"><h4>本次处理来源</h4><button type="button" class="quiet" :disabled="locked || loading.input" @click="loadInput">重新核对来源</button></div>
      <p v-if="loading.input" role="status">正在检查完整原件与处理方式…</p><p v-if="errors.input" class="extraction-error" role="alert">{{ errors.input }}</p>
      <template v-if="options">
        <p><strong>{{ extractionMethods[options.method] }}</strong> · {{ options.input.format }} · {{ options.input.pageCount }} {{ options.input.format === 'XML' ? '个来源单元' : '页' }}</p>
        <p v-if="options.method === 'STRUCTURED_XML'">已识别结构化 XML，直接读取明确字段；本次不发送给模型。</p>
        <template v-else>
          <p v-if="options.enabled" class="extraction-destination">发送至 {{ options.providerId }} · {{ options.model }} · {{ options.destination }}</p>
          <p v-else role="status">{{ extractionError({ code: options.unavailableCode }) }}</p>
          <p v-if="options.transmission === 'XML_TEXT'">将发送完整 XML 正文、元素名和属性，其中可能含购销双方信息。</p>
          <p v-else>将发送完整原文件，包括票面、内部元数据及 PDF 中的内嵌内容。</p>
          <label class="extraction-consent"><input v-model="externalSendConfirmed" type="checkbox" :disabled="locked || dirty || !options.enabled" />我已核对原件，同意本次发送上述完整内容至显示的模型目的地</label>
        </template>
        <button type="button" class="secondary" :disabled="!canGenerate" @click="generate">{{ sending ? '正在保存请求…' : options.method === 'STRUCTURED_XML' ? '提取本地 XML 字段' : '发送原件并提取票面' }}</button>
        <p v-if="running">已有任务在执行，请从下方原记录刷新结果。</p>
      </template>
    </section>
    <section class="extraction-history" aria-label="票面提取记录">
      <div class="extraction-toolbar"><h4>提取记录</h4><button type="button" class="quiet" :disabled="!canNavigate || loading.list" @click="loadPage(page?.page ?? 0)">刷新记录列表</button></div>
      <p v-if="loading.list" role="status">正在读取本人记录…</p><p v-if="errors.list" class="extraction-error" role="alert">{{ errors.list }}</p>
      <template v-if="page">
        <p v-if="!page.items.length">暂无提取记录。核对上方来源后可发起第一次提取。</p>
        <ul v-else class="extraction-runs"><li v-for="run in page.items" :key="run.id"><button type="button" :aria-pressed="selectedId === run.id" :disabled="!canNavigate" @click="loadDetail(run.id)"><strong>{{ extractionStatuses[run.status] }}</strong><span>{{ extractionMethods[run.method] }} · {{ date(run.createdAt) }}</span></button></li></ul>
        <div v-if="page.total > page.pageSize" class="extraction-pagination"><button type="button" :disabled="!canNavigate || page.page === 0" @click="loadPage(page.page - 1)">上一页</button><span>第 {{ page.page + 1 }} 页 · 共 {{ page.total }} 条</span><button type="button" :disabled="!canNavigate || (page.page + 1) * page.pageSize >= page.total" @click="loadPage(page.page + 1)">下一页</button></div>
      </template>
      <div v-if="selectedId" class="extraction-toolbar"><h4>本条结果</h4><button type="button" class="quiet" :disabled="!canNavigate || loading.detail" @click="loadDetail(selectedId)">刷新本条结果</button></div>
      <p v-if="loading.detail" role="status">正在读取本条结果…</p><p v-if="errors.detail" class="extraction-error" role="alert">{{ errors.detail }}</p>
      <template v-if="detail">
        <p><strong>{{ extractionStatuses[detail.status] }}</strong> · {{ extractionMethods[detail.method] }} · {{ date(detail.createdAt) }}</p>
        <p v-if="detail.status === 'QUEUED' || detail.status === 'RUNNING'">任务在后台执行，刷新本条结果即可查看进度，不需要再次发送原件。</p>
        <p v-if="detail.failure" class="extraction-error" role="status">{{ extractionError({ code: detail.failure }) }}</p>
        <details class="extraction-origin"><summary>原件与处理信息</summary><dl><dt>原件编号</dt><dd>{{ detail.input.originalId }}</dd><dt>原件摘要</dt><dd>{{ detail.input.originalDigest }}</dd><dt>实际页数</dt><dd>{{ detail.input.pageCount }}</dd><template v-if="detail.suggestion"><dt>{{ detail.method === 'MODEL' ? '模型提供方 / 处理版本' : '解析器 / 处理版本' }}</dt><dd>{{ detail.suggestion.providerId }} / {{ detail.suggestion.processorVersion }}</dd></template></dl></details>
        <template v-if="detail.suggestion">
          <p>{{ detail.method === 'MODEL' ? '模型把握是模型自评，不代表准确率。请对照原件核对值和摘录。' : '结构化字段来自本地映射，元素位置可供对照；直接读取不代表票据真实有效。' }}</p>
          <p v-if="!detail.suggestion.proposals.length">本次没有识别到有依据的字段，可下载原件核对或放弃本次结果。</p>
          <div class="extraction-comparison">
            <article v-for="proposal in detail.suggestion.proposals" :key="proposal.field" class="extraction-field">
              <div class="extraction-original"><h5>{{ extractionFields[proposal.field] }}</h5><small>原始提取值</small><p>{{ proposal.value }}</p><small>{{ detail.method === 'MODEL' ? '模型自评：' + confidenceLabels[proposal.confidence] : '结构化字段直接读取' }}</small>
                <details><summary>查看来源摘录</summary><div v-for="(evidence, index) in proposal.evidence" :key="index"><small>{{ detail.input.format === 'XML' ? '来源单元' : '原件页码' }} {{ evidence.page }}</small><blockquote>{{ evidence.quote }}</blockquote><code v-if="evidence.xmlPath">{{ evidence.xmlPath }}</code></div></details>
              </div>
              <div class="extraction-human">
                <template v-if="detail.status === 'COMPLETED'">
                  <label class="extraction-choice"><input v-model="selected" type="checkbox" :value="proposal.field" :disabled="!canConfirm" :aria-label="`确认${extractionFields[proposal.field]}`" />保存本人确认值</label>
                  <label class="extraction-value"><span>{{ extractionFields[proposal.field] }}确认值</span><input v-model="values[proposal.field]" type="text" :maxlength="proposal.field.endsWith('_NAME') ? 256 : 64" :disabled="!canConfirm || !selected.includes(proposal.field)" autocomplete="off" spellcheck="false" /></label>
                </template>
                <template v-else><small>本人确认值</small><p>{{ detail.review?.selected?.find(value => value.field === proposal.field)?.value ?? '未确认此字段' }}</p></template>
              </div>
            </article>
          </div>
          <p v-if="detail.status === 'COMPLETED' && !detail.canConfirm" class="extraction-warning">原件当前不可用于确认；你仍可放弃本次结果。</p>
          <p v-if="staleEdits" class="extraction-warning">原记录已更新，本地未保存修订不能再提交。清除本地修订后查看当前结果。</p>
          <details v-if="staleEdits" class="extraction-origin"><summary>查看保留的本地修订</summary><pre>{{ values }}</pre><p>{{ comment }}</p></details>
          <label v-if="detail.status === 'COMPLETED'" class="extraction-value">核对说明（可选）<textarea v-model="comment" rows="2" maxlength="2000" :disabled="!canReview" /></label>
          <div v-if="detail.status === 'COMPLETED'" class="extraction-actions"><button type="button" class="primary" :disabled="!canConfirm || !selected.length" @click="review('CONFIRM')">保存勾选字段的确认值</button><button type="button" class="secondary" :disabled="!canReview" @click="review('DISMISS')">放弃本次结果</button></div>
          <p v-if="detail.review">{{ detail.review.actor }} · {{ date(detail.review.at) }}<template v-if="detail.review.comment"> · {{ detail.review.comment }}</template></p>
        </template>
      </template>
      <div v-if="dirty" class="extraction-unsaved"><p>有未保存的复核内容，仅保留在当前页面会话。刷新或关闭浏览器会丢失本地修订。</p><button type="button" class="quiet" :disabled="locked" @click="discardEdits">清除本地修订</button></div>
    </section>
  </section>
</template>

<style scoped>
.invoice-extraction{margin-top:30px;padding-top:26px;border-top:1px solid var(--line);font-size:12px;line-height:1.8;min-width:0}.extraction-heading,.extraction-toolbar{display:flex;align-items:center;justify-content:space-between;gap:16px;flex-wrap:wrap}.extraction-heading h3{font-size:19px;margin:0 0 6px}.invoice-extraction p{overflow-wrap:anywhere}.extraction-heading p{margin:0;color:var(--muted)}.extraction-tag{color:var(--deep);background:var(--soft);padding:5px 10px;border-radius:6px;white-space:nowrap}.extraction-toolbar h4{font-size:14px;margin:0}.extraction-source{background:var(--paper);border-radius:10px;padding:20px;margin:20px 0}.extraction-destination{font-weight:600;color:var(--deep)}.extraction-consent,.extraction-choice{display:flex;align-items:flex-start;gap:8px;margin:16px 0;cursor:pointer}.extraction-consent input,.extraction-choice input{margin-top:4px;flex-shrink:0;width:16px;height:16px;accent-color:var(--deep)}.extraction-error{color:var(--red)}.extraction-notice{color:var(--deep);background:var(--soft);padding:12px;border-radius:8px}.extraction-warning,.extraction-unsaved{background:#fff9ef;padding:12px;border-radius:8px}.extraction-runs{list-style:none;padding:0;display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px;margin:14px 0 20px}.extraction-runs button{width:100%;text-align:left;padding:12px;border:1px solid var(--line);border-radius:8px;background:white}.extraction-runs button[aria-pressed=true]{border-color:var(--deep);background:var(--soft)}.extraction-runs span{display:block;color:var(--muted);font-size:11px;margin-top:4px}.extraction-pagination,.extraction-actions{display:flex;align-items:center;gap:12px;flex-wrap:wrap;margin:14px 0}.extraction-pagination{justify-content:space-between}.extraction-origin{padding:12px 0;color:var(--muted)}summary{cursor:pointer}.extraction-origin dl{display:grid;grid-template-columns:120px minmax(0,1fr);gap:8px}.extraction-origin dd{margin:0;overflow-wrap:anywhere;font-family:'DM Mono',monospace}.extraction-origin pre{white-space:pre-wrap;overflow-wrap:anywhere}.extraction-comparison{border:1px solid var(--line);border-radius:10px;overflow:hidden;margin:18px 0}.extraction-field{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1fr)}.extraction-field+.extraction-field{border-top:1px solid var(--line)}.extraction-original,.extraction-human{padding:18px;min-width:0}.extraction-original{background:var(--paper);border-right:1px solid var(--line)}.extraction-original h5{margin:0 0 10px;font-size:13px}.extraction-original>p,.extraction-human>p{font-family:'DM Mono',monospace;font-size:14px;margin:4px 0 10px;white-space:pre-wrap}.extraction-original small,.extraction-human>small{color:var(--muted);font-size:11px}.extraction-original details{margin-top:12px}.extraction-original blockquote{margin:6px 0;border-left:2px solid var(--teal);padding-left:10px;white-space:pre-wrap;overflow-wrap:anywhere}.extraction-original code{display:block;font-size:10px;overflow-wrap:anywhere}.extraction-choice{margin:0 0 12px}.extraction-value{display:block;color:var(--muted);margin-top:10px}.extraction-value input,.extraction-value textarea{width:100%;display:block;padding:10px;border:1px solid var(--line);border-radius:6px;margin-top:6px;background:#fff;color:var(--ink);font-size:13px;min-width:0}.extraction-value input{font-family:'DM Mono',monospace}.extraction-value :disabled{background:var(--paper);color:var(--muted)}.invoice-extraction :is(input,textarea,button,summary):focus-visible{outline:3px solid var(--teal);outline-offset:3px}.extraction-unsaved{display:flex;justify-content:space-between;gap:12px;align-items:center;margin-top:14px}.extraction-unsaved p{margin:0}.extraction-unsaved button{flex-shrink:0}@media(max-width:700px){.extraction-field{grid-template-columns:1fr}.extraction-original{border-right:0;border-bottom:1px dashed var(--line)}.extraction-runs{grid-template-columns:1fr}.extraction-source{padding:14px}.extraction-origin dl{grid-template-columns:1fr;gap:4px}.extraction-origin dd{margin-bottom:8px}.extraction-unsaved{align-items:flex-start;flex-direction:column}.extraction-actions button{width:100%}}
</style>
