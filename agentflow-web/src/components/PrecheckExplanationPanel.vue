<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import { api } from '../api'
import { precheckStatuses } from '../expenseDraft'
import { isDefinitiveWriteFailure } from '../pendingWrites'
import { explanationError, explanationFailures, explanationSelection, explanationSourceLabel, explanationStatuses,
  focusedExplanation, rememberExplanation, subscribeExplanationRecovery,
  type ExplanationInput, type ExplanationPage, type ExplanationDetail, type ExplanationReview } from '../precheckExplanation'
import type { AssistReference } from '../assistRuns'

const props = defineProps<{ reportId: string; scopeKey: string; applicationVersion: number; financialVersion: number
  editable: boolean; locked: boolean; applicationDirty: boolean; refreshVersion?: number }>()
const emit = defineEmits<{ busy: [value: boolean]; dirty: [value: boolean] }>()
const options = ref<ExplanationInput | null>(null), page = ref<ExplanationPage | null>(null), detail = ref<ExplanationDetail | null>(null)
const sourceIds = ref<string[]>([]), selected = ref<string[]>([]), selectedId = ref(''), comment = ref('')
const error = ref(''), notice = ref(''), sending = ref(false), unknown = ref(false), denied = ref(false), now = ref(Date.now())
const loading = reactive({ input: false, list: false, detail: false }), errors = reactive({ input: '', list: '', detail: '' })
type Resource = keyof typeof loading
const requests: Record<Resource, { generation: number; controller: AbortController | null }> = {
  input: { generation: 0, controller: null }, list: { generation: 0, controller: null }, detail: { generation: 0, controller: null }
}
const context = computed(() => JSON.stringify([props.scopeKey, props.reportId]))
const reviewDirty = computed(() => !!selected.value.length || !!comment.value)
const dirty = computed(() => !!sourceIds.value.length || reviewDirty.value)
const locked = computed(() => props.locked || props.applicationDirty || sending.value || unknown.value || denied.value)
const matches = (value: { applicationVersion: number; financialVersion: number }) => value.applicationVersion === props.applicationVersion && value.financialVersion === props.financialVersion
const fresh = (until: string | null) => !!until && Date.parse(until) > now.value
const selectionError = computed(() => {
  if (!options.value) return ''
  try { explanationSelection(options.value, sourceIds.value); return '' } catch (cause) { return explanationError(cause) }
})
const inputCurrent = computed(() => props.editable && !!options.value?.enabled && matches(options.value) && fresh(options.value.validUntil))
const canGenerate = computed(() => !locked.value && !loading.input && !reviewDirty.value && inputCurrent.value && !selectionError.value)
const canReview = computed(() => !locked.value && !sourceIds.value.length && !loading.detail && detail.value?.status === 'COMPLETED')
const current = computed(() => !!detail.value?.canAdopt && inputCurrent.value && options.value?.precheckId === detail.value.precheckId
  && matches(detail.value) && fresh(detail.value.validUntil))
const unavailable = computed(() => detail.value?.unavailableCode ?? (!fresh(detail.value?.validUntil ?? null) ? 'FACTS_EXPIRED'
  : options.value && options.value.precheckId !== detail.value?.precheckId ? 'PRECHECK_SUPERSEDED' : options.value?.unavailableCode ?? 'CONTEXT_CHANGED'))
const canNavigate = computed(() => !locked.value && !dirty.value)
const timeLabel = (value: string) => new Date(value).toLocaleString('zh-CN')
const evidence = (reference: AssistReference) => detail.value?.sources.find(s => s.reference.sourceId === reference.sourceId && s.reference.contentDigest === reference.contentDigest)
let active = true, session = 0, expiry: ReturnType<typeof setTimeout> | undefined
function clearReview() { selected.value = []; comment.value = '' }
function clearSelection() { sourceIds.value = []; clearReview(); error.value = ''; notice.value = '' }
function abortReads() {
  for (const name of Object.keys(requests) as Resource[]) { requests[name].generation++; requests[name].controller?.abort(); loading[name] = false; errors[name] = '' }
}
function reset() {
  session++; abortReads(); options.value = null; page.value = null; detail.value = null; selectedId.value = ''; clearSelection()
  sending.value = false; unknown.value = false; denied.value = false
}
/** 独立读取有超时和代次，失权时清空整个解释区域，迟到响应不能回填。 */
async function read<T>(name: Resource, fetch: (signal: AbortSignal) => Promise<T>, apply: (value: T) => void) {
  if (!active || denied.value) return
  const slot = requests[name], generation = ++slot.generation, original = context.value
  slot.controller?.abort(); const controller = new AbortController(); slot.controller = controller
  loading[name] = true; errors[name] = ''; let timedOut = false, timer: ReturnType<typeof setTimeout> | undefined
  const valid = () => active && original === context.value && generation === slot.generation
  const timeout = new Promise<never>((_, reject) => { timer = setTimeout(() => {
    timedOut = true; controller.abort()
    if (valid()) { loading[name] = false; errors[name] = '读取超时，请刷新重试。' }
    reject(new Error('读取超时，请刷新重试。'))
  }, 12_000) })
  try { const value = await Promise.race([fetch(controller.signal), timeout]); if (valid() && !timedOut) { now.value = Date.now(); apply(value) } }
  catch (cause) {
    if (valid() && !timedOut) {
      if ([401, 403, 404].includes((cause as { status?: number })?.status ?? 0)) { reset(); denied.value = true; error.value = '当前账号不能读取这份解释，请重新打开单据核对权限。' }
      else errors[name] = explanationError(cause)
    }
  } finally { clearTimeout(timer); if (valid()) loading[name] = false }
}
function loadInput() {
  options.value = null; sourceIds.value = []
  if (!props.editable) { requests.input.generation++; requests.input.controller?.abort(); loading.input = false; return Promise.resolve() }
  const reportId = props.reportId, applicationVersion = props.applicationVersion, financialVersion = props.financialVersion
  return read('input', async signal => {
    const available = await api.expensePrecheckOptions(reportId, signal)
    if (signal.aborted) throw new Error('Read cancelled')
    if (available.applicationVersion !== applicationVersion || available.financialVersion !== financialVersion) throw new Error('单据版本已变化，请重新打开费用单。')
    return available.latestPrecheckId ? api.precheckExplanationInput(reportId, available.latestPrecheckId, signal) : null
  }, value => { options.value = value })
}
async function loadPage(number = 0, afterWrite = false) {
  if (!afterWrite && !canNavigate.value || number < 0 || number > 10000) return
  page.value = null
  await read('list', signal => api.precheckExplanationRuns(props.reportId, number, signal), value => { page.value = value })
}
async function loadDetail(id: string, afterWrite = false) {
  if (!afterWrite && !canNavigate.value) return
  detail.value = null; selectedId.value = id; clearReview()
  rememberExplanation(props.scopeKey, props.reportId, id)
  await read('detail', signal => api.precheckExplanationRun(props.reportId, id, signal), value => { detail.value = value })
}
function writeFailed(cause: unknown) {
  error.value = explanationError(cause)
  unknown.value = !isDefinitiveWriteFailure(cause)
}
/** 发送的只有来源标识及固定版本，正文、金额和业务结论由服务器读取。 */
async function generate() {
  now.value = Date.now()
  if (!canGenerate.value || !options.value?.targetDigest) return
  const original = context.value, generation = session, input = options.value
  sending.value = true; error.value = ''; notice.value = ''
  try {
    const receipt = await api.generatePrecheckExplanation(props.reportId, { precheckId: input.precheckId,
      applicationVersion: input.applicationVersion, financialVersion: input.financialVersion,
      targetDigest: input.targetDigest!, sourceIds: explanationSelection(input, sourceIds.value) })
    if (!active || original !== context.value || generation !== session) return
    sourceIds.value = []; rememberExplanation(props.scopeKey, props.reportId, receipt.id)
    notice.value = '解释请求已排队，可刷新记录查看。原费用检查结论保持不变。'
    await loadPage(0, true)
    if (active && original === context.value && generation === session) await loadDetail(receipt.id, true)
  } catch (cause) { if (active && original === context.value && generation === session) writeFailed(cause) }
  finally { if (active && original === context.value && generation === session) sending.value = false }
}
/** 复核只选择原解释，不提供修改金额、预检结果或审批状态的入口。 */
async function review(action: 'ADOPT' | 'DISMISS') {
  now.value = Date.now()
  if (!canReview.value || !detail.value || action === 'ADOPT' && !current.value) return
  if (comment.value.length > 2000) { error.value = '复核说明最多 2,000 字符。'; return }
  if (action === 'ADOPT' && (!selected.value.length || new Set(selected.value).size !== selected.value.length
      || !selected.value.every(id => detail.value!.suggestion!.items.some(i => i.issueSourceId === id)))) { error.value = '请明确勾选本次解释中要采纳的问题。'; return }
  const original = context.value, generation = session, id = detail.value.id
  const body: ExplanationReview = { expectedRunVersion: detail.value.version, action, comment: comment.value,
    ...(action === 'ADOPT' ? { selectedIssueIds: [...selected.value] } : {}) }
  sending.value = true; error.value = ''; notice.value = ''
  try {
    await api.reviewPrecheckExplanation(props.reportId, id, body)
    if (!active || original !== context.value || generation !== session) return
    clearReview(); notice.value = action === 'ADOPT' ? '已记录采纳。请回到费用编辑页自行补正，保存后重新预检。' : '已记录放弃，原解释及来源保留。'
    await loadPage(page.value?.page ?? 0, true)
    if (active && original === context.value && generation === session) await loadDetail(id, true)
  } catch (cause) { if (active && original === context.value && generation === session) writeFailed(cause) }
  finally { if (active && original === context.value && generation === session) sending.value = false }
}
const unsubscribe = subscribeExplanationRecovery((scope, reportId, receipt) => {
  if (!active || scope !== props.scopeKey || reportId !== props.reportId || denied.value) return
  unknown.value = false; clearSelection(); notice.value = '原解释操作已确认，正在读取同一条记录。'
  void loadPage(0, true); void loadDetail(receipt.id, true)
})
watch(context, () => {
  reset(); if (!props.scopeKey || !props.reportId) return
  void loadInput(); void loadPage()
  const id = focusedExplanation(props.scopeKey, props.reportId); if (id) void loadDetail(id)
}, { immediate: true, flush: 'sync' })
watch(() => [props.applicationVersion, props.financialVersion, props.editable, props.refreshVersion], () => { void loadInput() }, { flush: 'sync' })
watch(() => [options.value?.validUntil, detail.value?.validUntil, now.value], () => {
  clearTimeout(expiry)
  const deadlines = [options.value?.validUntil, detail.value?.validUntil].filter((v): v is string => !!v).map(Date.parse).filter(v => v > now.value)
  if (deadlines.length) expiry = setTimeout(() => { now.value = Date.now() }, Math.max(1, Math.min(Math.min(...deadlines) - Date.now(), 2_147_483_647)))
}, { flush: 'sync' })
watch(sending, value => emit('busy', value), { flush: 'sync' })
watch(dirty, value => emit('dirty', value), { immediate: true, flush: 'sync' })
onUnmounted(() => { active = false; abortReads(); clearTimeout(expiry); unsubscribe(); emit('busy', false); emit('dirty', false) })
</script>

<template>
  <section class="precheck-explanation" aria-label="预检解释与补正建议">
    <header><div><h3>预检解释与补正建议</h3><p>选择要解释的检查问题，核对来源后记录人工意见。</p></div><span class="explanation-tag">人工复核</span></header>
    <p>模型解释供补正参考，采纳只记录意见。金额、费用检查结论和审批状态由原业务流程确定。</p>
    <p v-if="applicationDirty" class="explanation-warning">费用有未保存修改，请先保存，再生成或采纳解释。</p>
    <p v-if="error" class="explanation-error" role="alert">{{ error }}</p><p v-if="notice" role="status">{{ notice }}</p>
    <p v-if="unknown" class="explanation-warning" role="status">写入结果尚未确认，请使用页面的“恢复原操作”，不要重新发起。</p>
    <section v-if="editable && !denied" aria-label="选择解释来源">
      <div class="explanation-toolbar"><h4>1. 选择本次发送内容</h4><button type="button" class="quiet" :disabled="locked || dirty || loading.input" @click="loadInput">刷新发送目录</button></div>
      <p v-if="loading.input" role="status">正在读取当前预检与可发送内容…</p><p v-else-if="!options && !errors.input">先保存费用并执行费用预检，再刷新这里的发送目录。</p>
      <p v-if="errors.input" class="explanation-error" role="alert">{{ errors.input }}</p>
      <template v-if="options">
        <p><strong>原检查：{{ precheckStatuses[options.result] }}</strong> · 第 {{ options.attempt }} 次<template v-if="options.checkedAt"> · {{ timeLabel(options.checkedAt) }}</template></p>
        <p v-if="!options.enabled" role="status">{{ explanationError({ code: options.unavailableCode }) }}</p>
        <template v-else>
          <p class="explanation-destination">发送至 {{ options.providerId }} · {{ options.model }} · {{ options.destination }}</p>
          <p v-if="!inputCurrent" class="explanation-warning">检查依据已过期或单据已变化，请重新预检并刷新目录。</p>
          <p>勾选原检查结论及要解释的问题，可附选费用行。最多 20 个问题、64 项来源；未勾选的内容不发送。</p>
          <fieldset :disabled="locked || reviewDirty || !inputCurrent"><legend>已选 {{ sourceIds.length }} 项来源</legend>
            <div v-for="source in options.sources" :key="source.reference.sourceId" class="explanation-source">
              <label><input v-model="sourceIds" type="checkbox" :value="source.reference.sourceId" />{{ explanationSourceLabel(source) }}</label>
              <details><summary>查看将发送的原文</summary><pre>{{ source.content }}</pre></details>
            </div>
          </fieldset>
          <p v-if="sourceIds.length && selectionError" class="explanation-warning">{{ selectionError }}</p>
          <button type="button" class="secondary" :disabled="!canGenerate" @click="generate">发送所选内容并生成解释</button>
        </template>
      </template>
    </section>
    <section aria-label="解释记录">
      <div class="explanation-toolbar"><h4>2. 读取原解释</h4><button type="button" class="quiet" :disabled="!canNavigate || loading.list" @click="loadPage(page?.page ?? 0)">刷新记录</button></div>
      <p v-if="loading.list" role="status">正在读取解释记录…</p><p v-if="errors.list" class="explanation-error" role="alert">{{ errors.list }}</p>
      <template v-if="page"><p v-if="!page.items.length">尚无解释记录。</p><ul class="explanation-list"><li v-for="row in page.items" :key="row.id"><button type="button" class="quiet" :disabled="!canNavigate" :aria-pressed="selectedId === row.id" @click="loadDetail(row.id)">{{ timeLabel(row.createdAt) }} · 第 {{ row.attempt }} 次预检 · {{ explanationStatuses[row.status] }}</button></li></ul>
        <div class="explanation-toolbar"><button type="button" class="quiet" :disabled="!canNavigate || loading.list || page.page === 0" @click="loadPage(page.page - 1)">上一页</button><span>第 {{ page.page + 1 }} 页 · {{ page.total }} 条</span><button type="button" class="quiet" :disabled="!canNavigate || loading.list || page.page >= 10000 || (page.page + 1) * page.pageSize >= page.total" @click="loadPage(page.page + 1)">下一页</button></div>
      </template>
      <button v-if="selectedId" type="button" class="quiet" :disabled="!canNavigate || loading.detail" @click="loadDetail(selectedId)">刷新本条解释</button>
      <p v-if="loading.detail" role="status">正在读取原解释与来源…</p><p v-if="errors.detail" class="explanation-error" role="alert">{{ errors.detail }}</p>
      <article v-if="detail" class="explanation-detail">
        <h4>{{ explanationStatuses[detail.status] }}</h4><p><strong>原检查结论：{{ precheckStatuses[detail.result] }}</strong> · 第 {{ detail.attempt }} 次</p>
        <p>检查于 {{ timeLabel(detail.checkedAt) }} · 依据有效至 {{ timeLabel(detail.validUntil) }}</p>
        <details><summary>本次实际发送的来源（{{ detail.sources.length }} 项）</summary><section v-for="source in detail.sources" :key="source.reference.sourceId"><strong>{{ explanationSourceLabel(source) }}</strong><pre>{{ source.content }}</pre><small>来源摘要 {{ source.reference.contentDigest }}</small></section></details>
        <p v-if="detail.failure" class="explanation-warning">{{ explanationFailures[detail.failure] }}</p>
        <template v-if="detail.suggestion">
          <p>生成模型：{{ detail.suggestion.providerId }} · {{ detail.suggestion.modelVersion }}</p>
          <p v-if="detail.status === 'COMPLETED' && !current" class="explanation-warning">这条解释当前不可采纳。{{ explanationError({ code: unavailable }) }}可保留查看或明确放弃。</p>
          <article v-for="item in detail.suggestion.items" :key="item.issueSourceId" class="explanation-item">
            <label v-if="detail.status === 'COMPLETED'"><input v-model="selected" type="checkbox" :value="item.issueSourceId" :disabled="!canReview || !current" />采纳这条解释</label>
            <p class="preserved">{{ item.explanation }}</p><ol v-if="item.corrections.length"><li v-for="(step, index) in item.corrections" :key="index" class="preserved">{{ step }}</li></ol>
            <details v-for="ref in item.evidence" :key="ref.sourceId"><summary>{{ evidence(ref) ? explanationSourceLabel(evidence(ref)!) : '原来源' }}</summary><pre>{{ evidence(ref)?.content }}</pre><small>来源摘要 {{ ref.contentDigest }}</small></details>
          </article>
          <template v-if="detail.status === 'COMPLETED'">
            <label class="explanation-comment">复核说明（可选）<textarea v-model="comment" rows="3" maxlength="2000" :disabled="!canReview" /></label>
            <div class="explanation-toolbar"><button type="button" class="secondary" :disabled="!canReview || !current || !selected.length" @click="review('ADOPT')">记录采纳所选解释</button><button type="button" class="quiet" :disabled="!canReview" @click="review('DISMISS')">放弃本条解释</button></div>
          </template>
        </template>
        <section v-if="detail.review" aria-label="人工复核记录"><p>{{ detail.review.actor }} · {{ timeLabel(detail.review.at) }} · {{ detail.status === 'ADOPTED' ? `采纳 ${detail.review.selectedIssueIds.length} 个问题` : '放弃本条解释' }}</p><p v-if="detail.review.comment" class="preserved">{{ detail.review.comment }}</p></section>
      </article>
    </section>
    <button v-if="dirty" type="button" class="quiet" :disabled="sending || unknown || props.locked" @click="clearSelection">清除本页未提交的选择和说明</button>
  </section>
</template>

<style scoped>
.precheck-explanation{margin:24px 0;padding:22px;border:1px solid var(--line);border-radius:12px;min-width:0;font-size:12px;line-height:1.8}.precheck-explanation header,.explanation-toolbar{display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap}.precheck-explanation h3{margin:0;font-size:17px}.precheck-explanation h4{font-size:13px}.explanation-tag{color:var(--deep);background:var(--soft);border-radius:6px;padding:4px 9px}.precheck-explanation fieldset{border:1px solid var(--line);border-radius:8px;min-width:0;margin:12px 0;padding:12px}.precheck-explanation label{display:flex;gap:8px;align-items:flex-start;overflow-wrap:anywhere}.precheck-explanation input[type=checkbox]{flex-shrink:0;width:auto;margin-top:5px}.explanation-source,.explanation-item{padding:12px 0;border-bottom:1px solid var(--line)}.precheck-explanation pre{white-space:pre-wrap;overflow-wrap:anywhere;font-size:11px;background:var(--paper);padding:12px;border-radius:6px;max-height:260px;overflow:auto}.precheck-explanation summary{cursor:pointer;color:var(--deep);padding:6px 0}.precheck-explanation summary:focus-visible{outline:3px solid var(--teal);outline-offset:2px}.explanation-warning{color:#7b5418;background:#fff8e8;padding:10px;border-radius:6px}.explanation-error{color:var(--red);background:#fff0ed;padding:10px;border-radius:6px}.explanation-destination,.precheck-explanation small{overflow-wrap:anywhere;color:var(--muted)}.explanation-list{list-style:none;padding:0}.explanation-list button{text-align:left;white-space:normal}.explanation-detail{background:var(--soft);padding:16px;border-radius:8px;margin-top:12px}.preserved{white-space:pre-wrap;overflow-wrap:anywhere}.precheck-explanation .explanation-comment{display:grid;margin:14px 0}.explanation-comment textarea{font:inherit;resize:vertical;min-width:0;width:100%;box-sizing:border-box}.precheck-explanation button{white-space:normal}@media(max-width:650px){.precheck-explanation{padding:14px}.explanation-detail{padding:12px}}
</style>
