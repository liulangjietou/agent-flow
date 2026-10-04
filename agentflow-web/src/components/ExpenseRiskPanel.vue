<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import { api } from '../api'
import { isDefinitiveWriteFailure } from '../pendingWrites'
import type { ExpenseDetail } from '../expenses'
import type { AssistReference } from '../assistRuns'
import { focusedRisk, rememberRisk, riskError, riskFailures, riskKinds, riskSelection, riskStatuses, subscribeRiskRecovery,
  type RiskInput, type RiskRequest, type RiskPage, type RiskDetail, type RiskReview } from '../expenseRisk'
import ExpenseRiskScope from './ExpenseRiskScope.vue'

const props = defineProps<{ report: ExpenseDetail; taskId?: string; scopeKey: string; locked: boolean }>()
const emit = defineEmits<{ busy: [value: boolean]; dirty: [value: boolean] }>()
const options = ref<RiskInput | null>(null), prepared = ref<RiskRequest | null>(null)
const page = ref<RiskPage | null>(null), detail = ref<RiskDetail | null>(null), selectedId = ref('')
const sourceIds = ref<string[]>([]), selected = ref<string[]>([]), comment = ref('')
const error = ref(''), notice = ref(''), sending = ref(false), unknown = ref(false), denied = ref(false)
const scopeDirty = ref(false), scopeBusy = ref(false), resetVersion = ref(0)
const loading = reactive({ input: false, list: false, detail: false }), errors = reactive({ input: '', list: '', detail: '' })
type Resource = keyof typeof loading
const requests: Record<Resource, { generation: number; controller: AbortController | null }> = {
  input: { generation: 0, controller: null }, list: { generation: 0, controller: null }, detail: { generation: 0, controller: null }
}
const context = computed(() => JSON.stringify([props.scopeKey, props.report.id, props.report.roundNo, props.taskId ?? '']))
const reviewDirty = computed(() => selected.value.length > 0 || !!comment.value)
const dirty = computed(() => scopeDirty.value || sourceIds.value.length > 0 || reviewDirty.value)
const locked = computed(() => props.locked || sending.value || unknown.value || denied.value)
const selectionError = computed(() => {
  if (!options.value?.enabled) return ''
  try { riskSelection(options.value, sourceIds.value); return '' } catch (cause) { return riskError(cause) }
})
const canGenerate = computed(() => !locked.value && !scopeBusy.value && !loading.input && !reviewDirty.value && !!props.taskId
  && !!options.value?.enabled && !!prepared.value && prepared.value.taskId === props.taskId && !selectionError.value)
const canReview = computed(() => !locked.value && !scopeBusy.value && !scopeDirty.value && !sourceIds.value.length && !loading.detail && !!detail.value?.reviewable)
const canNavigate = computed(() => !locked.value && !dirty.value && !scopeBusy.value)
const busy = computed(() => sending.value || scopeBusy.value || Object.values(loading).some(Boolean))
const timeLabel = (value: string) => new Date(value).toLocaleString('zh-CN')
const evidence = (reference: AssistReference) => detail.value?.sources.find(s => s.reference.sourceId === reference.sourceId && s.reference.contentDigest === reference.contentDigest)
let active = true, session = 0

function clearReview() { selected.value = []; comment.value = '' }
function invalidateInput() { requests.input.generation++; requests.input.controller?.abort(); loading.input = false; options.value = null; prepared.value = null; sourceIds.value = []; errors.input = '' }
function clearDraft() { invalidateInput(); clearReview(); resetVersion.value++; scopeDirty.value = false; error.value = ''; notice.value = '' }
function abortReads() { for (const name of Object.keys(requests) as Resource[]) { requests[name].generation++; requests[name].controller?.abort(); loading[name] = false; errors[name] = '' } }
function reset() { session++; abortReads(); clearDraft(); detail.value = null; page.value = null; selectedId.value = ''; sending.value = false; unknown.value = false; denied.value = false }
/** 各读取独立计时和撤销，失权时清空整块风险正文，旧账号迟到响应不能回填。 */
async function read<T>(name: Resource, fetch: (signal: AbortSignal) => Promise<T>, apply: (value: T) => void) {
  if (!active || denied.value) return
  const slot = requests[name], generation = ++slot.generation, original = context.value
  slot.controller?.abort(); const controller = new AbortController(); slot.controller = controller; loading[name] = true; errors[name] = ''
  let timer: ReturnType<typeof setTimeout> | undefined
  const valid = () => active && original === context.value && generation === slot.generation
  try {
    const value = await Promise.race([fetch(controller.signal), new Promise<never>((_, reject) => { timer = setTimeout(() => {
      controller.abort(); reject(new Error('风险记录读取超时，请重试。'))
    }, 12_000) })])
    if (valid()) apply(value)
  } catch (cause) {
    if (valid()) {
      if ([401, 403, 404].includes((cause as { status?: number })?.status ?? 0)) { reset(); denied.value = true; error.value = '当前账号不能完整读取这份风险记录，请重新打开单据核对权限。' }
      else errors[name] = riskError(cause)
    }
  } finally { clearTimeout(timer); if (valid()) loading[name] = false }
}
/** 预览是只读请求；更换范围后必须重新取得目录并逐项确认。 */
async function preview(request: RiskRequest) {
  if (locked.value || reviewDirty.value || request.taskId !== props.taskId) return
  invalidateInput(); const original = JSON.parse(JSON.stringify(request)) as RiskRequest
  await read('input', signal => api.expenseRiskInput(props.report.id, original, signal), value => { options.value = value; prepared.value = original })
}
async function loadPage(number = 0, afterWrite = false) {
  if (!afterWrite && !canNavigate.value || number < 0 || number > 10000) return
  page.value = null
  await read('list', signal => api.expenseRiskRuns(props.report.id, props.report.roundNo, number, signal), value => { page.value = value })
}
async function loadDetail(id: string, afterWrite = false) {
  if (!afterWrite && !canNavigate.value) return
  detail.value = null; selectedId.value = id; clearReview(); rememberRisk(props.scopeKey, props.report.id, props.report.roundNo, id)
  await read('detail', signal => api.expenseRiskRun(props.report.id, id, props.report.roundNo, signal), value => { detail.value = value })
}
function failed(cause: unknown) { error.value = riskError(cause); unknown.value = !isDefinitiveWriteFailure(cause) }
async function generate() {
  if (!canGenerate.value || !options.value || !prepared.value) return
  const original = context.value, generation = session, input = options.value
  sending.value = true; error.value = ''; notice.value = ''
  try {
    const receipt = await api.generateExpenseRisk(props.report.id, { ...prepared.value, inputDigest: input.inputDigest!, targetDigest: input.targetDigest!, sourceIds: riskSelection(input, sourceIds.value) })
    if (!active || original !== context.value || generation !== session) return
    clearDraft(); rememberRisk(props.scopeKey, props.report.id, props.report.roundNo, receipt.id); notice.value = '解释请求已排队，可刷新本条记录查看。'
    await loadPage(0, true)
    if (active && original === context.value && generation === session) await loadDetail(receipt.id, true)
  } catch (cause) { if (active && original === context.value && generation === session) failed(cause) }
  finally { if (active && original === context.value && generation === session) sending.value = false }
}
/** 记录对模型原文的人工意见；决定权和所有原来源版本由服务端再次核对。 */
async function review(action: 'ADOPT' | 'DISMISS') {
  if (!canReview.value || !detail.value || action === 'ADOPT' && !detail.value.adoptable) return
  if (comment.value.length > 2000) { error.value = '复核说明最多 2,000 字符。'; return }
  if (action === 'ADOPT' && (!selected.value.length || new Set(selected.value).size !== selected.value.length
      || !selected.value.every(id => detail.value!.concerns.some(c => c.sourceId === id)))) { error.value = '请明确勾选本次要采纳的解释。'; return }
  const original = context.value, generation = session, id = detail.value.id
  const body: RiskReview = { expectedRunVersion: detail.value.version, action, comment: comment.value, ...(action === 'ADOPT' ? { selectedConcernIds: [...selected.value] } : {}) }
  sending.value = true; error.value = ''; notice.value = ''
  try {
    await api.reviewExpenseRisk(props.report.id, id, body)
    if (!active || original !== context.value || generation !== session) return
    clearReview(); notice.value = action === 'ADOPT' ? '已记录采纳，请根据原业务依据独立作出审批决定。' : '已记录放弃，原解释和来源保留。'
    await loadPage(page.value?.page ?? 0, true)
    if (active && original === context.value && generation === session) await loadDetail(id, true)
  } catch (cause) { if (active && original === context.value && generation === session) failed(cause) }
  finally { if (active && original === context.value && generation === session) sending.value = false }
}
const unsubscribe = subscribeRiskRecovery((scope, reportId, roundNo, receipt) => {
  if (!active || scope !== props.scopeKey || reportId !== props.report.id || denied.value
      || (roundNo === null ? receipt.id !== selectedId.value : roundNo !== props.report.roundNo)) return
  unknown.value = false; clearDraft(); notice.value = '原风险操作已确认，正在读取同一条记录。'
  void loadPage(0, true); void loadDetail(receipt.id, true)
})
watch(context, () => { reset(); if (!props.scopeKey) return; void loadPage(); const id = focusedRisk(props.scopeKey, props.report.id, props.report.roundNo); if (id) void loadDetail(id) }, { immediate: true, flush: 'sync' })
watch(() => [props.report.applicationVersion, props.report.financialVersion], () => {
  invalidateInput(); clearReview(); resetVersion.value++
  if (selectedId.value) { detail.value = null; void loadDetail(selectedId.value, true) }
}, { flush: 'sync' })
watch(busy, value => emit('busy', value), { flush: 'sync' })
watch(dirty, value => emit('dirty', value), { immediate: true, flush: 'sync' })
onUnmounted(() => { active = false; abortReads(); unsubscribe(); emit('busy', false); emit('dirty', false) })
</script>

<template>
  <section class="risk-panel" aria-label="费用风险解释与复核">
    <header><h3>费用风险解释与复核</h3><span>第 {{ report.roundNo }} 轮</span></header>
    <p>模型解释用于辅助人工核对。观察只覆盖你选择的单据和费用行；同日、非工作日或票号相邻本身不构成违规结论。</p>
    <p v-if="error" class="risk-error" role="alert">{{ error }}</p><p v-if="notice" role="status">{{ notice }}</p>
    <p v-if="unknown" class="risk-warning" role="status">写入结果尚未确认，请使用页面的“恢复原操作”。</p>
    <ExpenseRiskScope v-if="taskId && report.applicationStatus === 'IN_APPROVAL' && !denied" :report="report" :task-id="taskId" :scope-key="scopeKey" :reset-version="resetVersion"
      :locked="locked || loading.input || reviewDirty || !!sourceIds.length" @prepare="preview" @change="invalidateInput" @dirty="scopeDirty = $event" @busy="scopeBusy = $event" />
    <p v-if="loading.input" role="status">正在核对费用范围和可发送来源…</p><p v-if="errors.input" class="risk-error" role="alert">{{ errors.input }}</p>
    <section v-if="options" aria-label="逐项确认模型发送内容">
      <h4>2. 确认本次发送内容</h4>
      <p v-if="!options.enabled" class="risk-warning">{{ riskError({ code: options.unavailableCode }) }}</p>
      <template v-else>
        <p class="preserved">发送至 {{ options.destination }} · {{ options.providerId }} / {{ options.model }}</p>
        <p>勾选每份单据的费用事实、范围及查验覆盖，再选择要解释的观察。以下原文是本次可发送内容。</p>
        <fieldset :disabled="locked || loading.input || reviewDirty">
          <article v-for="source in options.sources" :key="source.reference.sourceId" class="risk-source"><label><input v-model="sourceIds" type="checkbox" :value="source.reference.sourceId" />{{ source.label }}</label><details><summary>查看发送原文</summary><pre>{{ source.content }}</pre></details></article>
        </fieldset>
        <p v-if="selectionError">{{ selectionError }}</p><button type="button" class="secondary" :disabled="!canGenerate" @click="generate">发送所选内容并生成解释</button>
      </template>
    </section>
    <section v-if="!denied" aria-label="原轮次风险解释记录">
      <div class="risk-toolbar"><h4>3. 核对原解释与来源</h4><button type="button" class="quiet" :disabled="!canNavigate || loading.list" @click="loadPage(page?.page ?? 0)">刷新记录</button></div>
      <p v-if="loading.list" role="status">正在读取本轮记录…</p><p v-if="errors.list" class="risk-error" role="alert">{{ errors.list }}</p>
      <template v-if="page"><p v-if="!page.items.length">本轮还没有风险解释记录。</p><ul class="risk-list"><li v-for="row in page.items" :key="row.id"><button type="button" class="quiet" :disabled="!canNavigate" :aria-pressed="row.id === selectedId" @click="loadDetail(row.id)">{{ timeLabel(row.createdAt) }} · {{ riskStatuses[row.status] }}</button></li></ul><div class="risk-toolbar"><button type="button" class="quiet" :disabled="!canNavigate || loading.list || page.page === 0" @click="loadPage(page.page - 1)">上一页</button><span>第 {{ page.page + 1 }} 页 · {{ page.total }} 条</span><button type="button" class="quiet" :disabled="!canNavigate || loading.list || page.page >= 10000 || (page.page + 1) * 20 >= page.total" @click="loadPage(page.page + 1)">下一页</button></div></template>
      <button v-if="selectedId" type="button" class="quiet" :disabled="!canNavigate || loading.detail" @click="loadDetail(selectedId)">刷新本条解释</button>
      <p v-if="loading.detail" role="status">正在重新核对原来源权限…</p><p v-if="errors.detail" class="risk-error" role="alert">{{ errors.detail }}</p>
      <article v-if="detail" class="risk-detail"><h4>{{ riskStatuses[detail.status] }}</h4>
        <details><summary>本次实际发送的来源（{{ detail.sources.length }} 项）</summary><section v-for="source in detail.sources" :key="source.reference.sourceId"><strong>{{ source.label }}</strong><pre>{{ source.content }}</pre></section></details>
        <p v-if="detail.failure" class="risk-warning">{{ riskFailures[detail.failure] }}</p>
        <template v-if="detail.suggestion"><p>生成模型：{{ detail.suggestion.providerId }} · {{ detail.suggestion.modelVersion }}</p>
          <p v-if="detail.status === 'COMPLETED' && !detail.adoptable" class="risk-warning">本条当前不可采纳。{{ riskError({ code: detail.unavailableCode }) }}<template v-if="detail.reviewable">仍可明确记录放弃。</template></p>
          <article v-for="item in detail.suggestion.items" :key="item.concernSourceId" class="risk-item"><h4>{{ riskKinds[item.kind] }}</h4><label v-if="detail.status === 'COMPLETED'"><input v-model="selected" type="checkbox" :value="item.concernSourceId" :disabled="!canReview || !detail.adoptable" />采纳这条解释</label><p class="preserved">{{ item.explanation }}</p><p class="preserved"><strong>依据局限：</strong>{{ item.limitations }}</p><ol><li v-for="(step, index) in item.checks" :key="index" class="preserved">{{ step }}</li></ol><details v-for="reference in item.evidence" :key="reference.sourceId"><summary>{{ evidence(reference)?.label ?? '原来源' }}</summary><pre>{{ evidence(reference)?.content }}</pre></details></article>
          <template v-if="detail.status === 'COMPLETED'"><label class="risk-comment">复核说明（可选）<textarea v-model="comment" rows="3" maxlength="2000" :disabled="!canReview" /></label><div class="risk-toolbar"><button type="button" class="secondary" :disabled="!canReview || !detail.adoptable || !selected.length" @click="review('ADOPT')">记录采纳所选解释</button><button type="button" class="quiet" :disabled="!canReview" @click="review('DISMISS')">放弃本条解释</button></div></template>
        </template>
        <section v-if="detail.review" aria-label="风险人工复核记录"><p>{{ detail.review.actor }} · {{ timeLabel(detail.review.at) }} · {{ detail.status === 'ADOPTED' ? `采纳 ${detail.review.selectedConcernIds.length} 条解释` : '放弃本条解释' }}</p><p class="preserved">{{ detail.review.comment }}</p></section>
      </article>
    </section>
    <button v-if="dirty" type="button" class="quiet" :disabled="sending || unknown || props.locked || scopeBusy" @click="clearDraft">清除本页未提交的选择和说明</button>
  </section>
</template>

<style scoped>
.risk-panel { margin: 24px 0; padding: 22px; border: 1px solid var(--line); border-radius: 12px; min-width: 0; font-size: 12px; line-height: 1.8; }
.risk-panel header, .risk-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.risk-panel h3 { margin: 0; font-size: 17px; }.risk-panel h4 { font-size: 13px; }.risk-panel fieldset { border: 1px solid var(--line); border-radius: 8px; min-width: 0; }
.risk-panel label { display: flex; align-items: start; gap: 8px; }.risk-panel input[type=checkbox] { width: auto; flex-shrink: 0; }
.risk-source, .risk-item { padding: 12px 0; border-bottom: 1px solid var(--line); }.risk-panel pre { white-space: pre-wrap; overflow-wrap: anywhere; background: var(--paper); padding: 12px; max-height: 260px; overflow: auto; font-size: 11px; }
.risk-panel summary { cursor: pointer; color: var(--deep); padding: 6px 0; }.risk-panel summary:focus-visible { outline: 3px solid var(--teal); outline-offset: 2px; }
.risk-warning { color: #7b5418; background: #fff8e8; padding: 10px; border-radius: 6px; }.risk-error { color: var(--red); background: #fff0ed; padding: 10px; border-radius: 6px; }
.risk-detail { background: var(--soft); padding: 16px; border-radius: 8px; margin-top: 12px; }.risk-list { list-style: none; padding: 0; }.preserved { white-space: pre-wrap; overflow-wrap: anywhere; }
.risk-panel .risk-comment { display: grid; margin: 14px 0; }.risk-comment textarea { font: inherit; resize: vertical; width: 100%; box-sizing: border-box; }.risk-panel button { white-space: normal; }
@media (max-width: 650px) { .risk-panel { padding: 14px; }.risk-detail { padding: 12px; } }
</style>
