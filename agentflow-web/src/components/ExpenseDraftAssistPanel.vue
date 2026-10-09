<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import { api } from '../api'
import type { ExpenseDetail } from '../expenses'
import { expenseUnits, type FinanceCatalog } from '../expenseDraft'
import { isDefinitiveWriteFailure } from '../pendingWrites'
import { expenseAssistError, expenseAssistFailures, expenseAssistMatches, expenseAssistStatuses, focusedExpenseAssist,
  rememberExpenseAssist, subscribeExpenseAssistRecovery, type ExpenseAssistDetail, type ExpenseAssistLeg, type ExpenseAssistPage,
  type ExpenseAssistPart, type ExpenseAssistPreview, type ExpenseAssistRequest, type ExpenseAssistSelection } from '../expenseDraftAssist'

const props = defineProps<{ scopeKey: string; report: ExpenseDetail; catalog: FinanceCatalog; locked: boolean; applicationDirty: boolean; handlingDraft?: { taskId: string; brief: string; runId?: string } }>()
const emit = defineEmits<{ busy: [value: boolean]; dirty: [value: boolean]; fill: [value: ExpenseAssistDetail] }>()
const blankLeg = (id: number): ExpenseAssistLeg => ({ id, startsOn: '', endsOn: '', cityCode: '', purpose: '' })
const brief = ref(''), itinerary = ref<ExpenseAssistLeg[]>([blankLeg(1)])
const categories = ref<string[]>([]), centers = ref<string[]>([]), projects = ref<string[]>([])
const preview = ref<ExpenseAssistPreview | null>(null), previewRequest = ref<ExpenseAssistRequest | null>(null), consent = ref(false)
const page = ref<ExpenseAssistPage | null>(null), detail = ref<ExpenseAssistDetail | null>(null), selectedId = ref('')
const choices = ref<Record<string, ExpenseAssistPart[]>>({}), comment = ref(''), sending = ref(false), unknown = ref(false), denied = ref(false)
const error = ref(''), notice = ref(''), now = ref(Date.now())
const loading = reactive({ preview: false, list: false, detail: false }), errors = reactive({ preview: '', list: '', detail: '' })
type Resource = keyof typeof loading
const requests: Record<Resource, { generation: number; controller: AbortController | null }> = {
  preview: { generation: 0, controller: null }, list: { generation: 0, controller: null }, detail: { generation: 0, controller: null }
}
const context = computed(() => JSON.stringify([props.scopeKey, props.report.id, props.report.applicationVersion, props.report.financialVersion, props.report.editable]))
const localCenters = computed(() => props.catalog.costCenters.filter(c => c.legalEntityId === props.report.content.legalEntityId))
const localProjects = computed(() => props.catalog.projects.filter(p => p.legalEntityId === props.report.content.legalEntityId))
const draftDirty = computed(() => !!brief.value || !!categories.value.length || !!centers.value.length || !!projects.value.length
  || itinerary.value.some(l => !!l.startsOn || !!l.endsOn || !!l.cityCode || !!l.purpose))
const reviewDirty = computed(() => Object.values(choices.value).some(parts => parts.length > 0) || !!comment.value)
const busy = computed(() => sending.value || unknown.value || Object.values(loading).some(Boolean))
const locked = computed(() => props.locked || props.applicationDirty || busy.value || denied.value)
const inputCurrent = computed(() => props.report.editable && Date.parse(props.catalog.validUntil) > now.value)
const selected = computed<ExpenseAssistSelection[]>(() => (detail.value?.suggestion?.lines ?? []).filter(l => choices.value[l.id]?.length)
  .map(l => ({ proposalId: l.id, parts: [...choices.value[l.id]] })))
const fitsDraft = computed(() => props.report.content.lines.length + selected.value.length <= 200)
const validSelection = computed(() => !!selected.value.length && selected.value.every(s => s.parts.includes('ITINERARY')))
const current = computed(() => !!detail.value && expenseAssistMatches(detail.value.input, props.report, props.catalog, now.value))
const canGenerate = computed(() => !locked.value && !reviewDirty.value && !!preview.value && !!previewRequest.value && consent.value
  && expenseAssistMatches(preview.value.input, props.report, props.catalog, now.value))
const canReview = computed(() => !locked.value && !draftDirty.value && detail.value?.status === 'COMPLETED')
const canFill = computed(() => !locked.value && !draftDirty.value && !reviewDirty.value && detail.value?.status === 'CONFIRMED' && current.value
  && props.report.content.lines.length + (detail.value.review?.selected.length ?? 0) <= 200)
const canNavigate = computed(() => !locked.value && !draftDirty.value && !reviewDirty.value)
const timeLabel = (value: string) => new Date(value).toLocaleString('zh-CN')
const legFor = (id: number) => detail.value?.input.itinerary.find(l => l.id === id)
const categoryName = (code: string) => detail.value?.input.options.categories.find(c => c.code === code)?.name ?? code
const centerName = (code: string) => detail.value?.input.options.costCenters.find(c => c.code === code)?.name ?? code
const projectName = (code: string) => detail.value?.input.options.projects.find(p => p.code === code)?.name ?? code
let active = true, session = 0, expiry: ReturnType<typeof setTimeout> | undefined
function clearPreview() { preview.value = null; previewRequest.value = null; consent.value = false; requests.preview.generation++; requests.preview.controller?.abort(); loading.preview = false }
function clearDraft() { brief.value = ''; itinerary.value = [blankLeg(1)]; categories.value = []; centers.value = []; projects.value = []; clearPreview() }
function clearReview() { choices.value = {}; comment.value = '' }
function abortReads() { for (const name of Object.keys(requests) as Resource[]) { requests[name].generation++; requests[name].controller?.abort(); loading[name] = false; errors[name] = '' } }
function reset() { session++; abortReads(); clearDraft(); clearReview(); page.value = null; detail.value = null; selectedId.value = ''; sending.value = false; unknown.value = false; denied.value = false; error.value = ''; notice.value = '' }
/** 来源读取按身份、单据版本和请求代次隔离；失权清空正文，迟到响应不回填。 */
async function read<T>(name: Resource, fetch: (signal: AbortSignal) => Promise<T>, apply: (value: T) => void): Promise<boolean> {
  if (!active || denied.value) return false
  const slot = requests[name], generation = ++slot.generation, original = context.value
  slot.controller?.abort(); const controller = new AbortController(); slot.controller = controller
  loading[name] = true; errors[name] = ''; let timer: ReturnType<typeof setTimeout> | undefined, timedOut = false
  const valid = () => active && original === context.value && generation === slot.generation
  const timeout = new Promise<never>((_, reject) => { timer = setTimeout(() => { timedOut = true; controller.abort(); reject(new Error('读取超时，请刷新重试。')) }, 12_000) })
  try { const value = await Promise.race([fetch(controller.signal), timeout]); if (valid() && !timedOut) { now.value = Date.now(); apply(value); return true } }
  catch (cause) { if (valid()) {
    if ([401, 403, 404].includes((cause as { status?: number })?.status ?? 0)) { reset(); denied.value = true; error.value = '当前账号不能读取这些建议，请重新打开单据核对权限。' }
    else errors[name] = expenseAssistError(cause)
  } } finally { clearTimeout(timer); if (valid()) loading[name] = false }
  return false
}
function addLeg() {
  if (locked.value || itinerary.value.length >= 20) return
  const id = Array.from({ length: 20 }, (_, i) => i + 1).find(id => !itinerary.value.some(l => l.id === id))!
  itinerary.value.push(blankLeg(id))
}
async function showPreview() {
  now.value = Date.now()
  if (locked.value || reviewDirty.value || !inputCurrent.value) return
  if (!brief.value.trim() || brief.value.length > 8000 || !categories.value.length || !centers.value.length
      || [categories.value, centers.value, projects.value].some(v => v.length > 50)
      || itinerary.value.some(l => !l.startsOn || !l.endsOn || l.endsOn < l.startsOn || !l.cityCode || !l.purpose.trim() || l.purpose.length > 2000)) {
    error.value = '请填写要求和完整行程，选择费用类别及成本中心；每类目录最多选择 50 项。'; return
  }
  const body: ExpenseAssistRequest = { applicationVersion: props.report.applicationVersion, financialVersion: props.report.financialVersion,
    brief: brief.value, itinerary: itinerary.value.map(l => ({ ...l })), catalog: { categoryCodes: [...categories.value], costCenterCodes: [...centers.value], projectCodes: [...projects.value] } }
  clearPreview(); error.value = ''; notice.value = ''
  await read('preview', signal => api.expenseAssistPreview(props.report.id, body, signal), value => { preview.value = value; previewRequest.value = body })
}
async function loadPage(number = 0, afterWrite = false) {
  if (!afterWrite && !canNavigate.value || number < 0 || number > 10000) return
  page.value = null; await read('list', signal => api.expenseAssistRuns(props.report.id, number, signal), value => { page.value = value })
}
async function loadDetail(id: string, afterWrite = false) {
  if (!afterWrite && !canNavigate.value) return
  detail.value = null; selectedId.value = id; clearReview(); rememberExpenseAssist(props.scopeKey, props.report.id, id)
  await read('detail', signal => api.expenseAssistRun(props.report.id, id, signal), value => { detail.value = value; choices.value = Object.fromEntries((value.suggestion?.lines ?? []).map(l => [l.id, []])) })
}
function failed(cause: unknown) { error.value = expenseAssistError(cause); unknown.value = !isDefinitiveWriteFailure(cause) }
/** 发送原预览内容和摘要，不把重新填写的内容混进旧确认。 */
async function generate() {
  now.value = Date.now(); if (!canGenerate.value || !preview.value || !previewRequest.value) return
  const original = context.value, generation = session, p = preview.value, body = previewRequest.value
  sending.value = true; error.value = ''; notice.value = ''
  try {
    const result = await api.generateExpenseAssist(props.report.id, { input: body, validUntil: p.input.validUntil, targetDigest: p.targetDigest, consentDigest: p.consentDigest, ...(props.handlingDraft ? { handlingTaskId: props.handlingDraft.taskId } : {}) })
    if (!active || original !== context.value || generation !== session) return
    clearDraft(); notice.value = '建议已排队，请刷新原记录查看生成结果。'
    await loadPage(0, true)
    if (active && original === context.value && generation === session) await loadDetail(result.id, true)
  } catch (cause) { if (active && original === context.value && generation === session) failed(cause) }
  finally { if (active && original === context.value && generation === session) sending.value = false }
}
/** 先持久保存逐项确认；读取确认记录后，仍由用户明确发起填入动作。 */
async function review(confirm: boolean) {
  now.value = Date.now()
  if (!canReview.value || !detail.value || confirm && (!detail.value.canConfirm || !current.value || !validSelection.value || !fitsDraft.value)) return
  if (comment.value.length > 2000) { error.value = '说明最多 2,000 字符。'; return }
  const original = context.value, generation = session, run = detail.value
  sending.value = true; error.value = ''; notice.value = ''
  try {
    if (confirm) await api.confirmExpenseAssist(props.report.id, run.id, { expectedRunVersion: run.version, applicationVersion: run.input.applicationVersion,
      financialVersion: run.input.financialVersion, selected: selected.value, comment: comment.value })
    else await api.dismissExpenseAssist(props.report.id, run.id, { expectedRunVersion: run.version, comment: comment.value })
    if (!active || original !== context.value || generation !== session) return
    clearReview(); notice.value = confirm ? '已记录逐项确认。核对后点击“填入费用草稿”，仍需填写金额并保存。' : '已放弃建议，原始来源保留。'
    await loadPage(page.value?.page ?? 0, true)
    if (active && original === context.value && generation === session) await loadDetail(run.id, true)
  } catch (cause) { if (active && original === context.value && generation === session) failed(cause) }
  finally { if (active && original === context.value && generation === session) sending.value = false }
}
/** 填入前再读取本人记录；请求恢复本身不自动触发此动作。 */
async function fill() {
  now.value = Date.now(); if (!canFill.value || !detail.value) return
  const id = detail.value.id
  const success = await read('detail', signal => api.expenseAssistRun(props.report.id, id, signal), value => { detail.value = value })
  if (success && canFill.value && detail.value) emit('fill', detail.value)
}
const unsubscribe = subscribeExpenseAssistRecovery((scope, reportId, result) => {
  if (!active || scope !== props.scopeKey || reportId !== props.report.id || denied.value) return
  unknown.value = false; clearDraft(); clearReview(); notice.value = '原操作已确认，正在读取同一条建议；请核对后再填入。'
  void loadPage(0, true); void loadDetail(result.id, true)
})
watch(context, () => {
  reset(); if (!props.scopeKey) return
  void loadPage(0, true); const id = focusedExpenseAssist(props.scopeKey, props.report.id); if (id) void loadDetail(id, true)
}, { immediate: true, flush: 'sync' })
/** 办理传递本人已确认的票据候选，仍由本人核对原草稿预览和目录。 */
watch(() => props.handlingDraft, value => {
  if (!value) return
  if (value.runId) { clearDraft(); void loadDetail(value.runId, true); return }
  if (!draftDirty.value && !reviewDirty.value) { brief.value = value.brief; clearPreview() }
}, { immediate: true })
watch(() => [brief.value, itinerary.value, categories.value, centers.value, projects.value, props.catalog], clearPreview, { deep: true, flush: 'sync' })
watch(() => [props.catalog.validUntil, preview.value?.input.validUntil, detail.value?.input.validUntil, now.value], () => {
  clearTimeout(expiry)
  const deadlines = [props.catalog.validUntil, preview.value?.input.validUntil, detail.value?.input.validUntil].filter((v): v is string => !!v).map(Date.parse).filter(v => v > now.value)
  if (deadlines.length) expiry = setTimeout(() => { now.value = Date.now() }, Math.max(1, Math.min(Math.min(...deadlines) - Date.now(), 2_147_483_647)))
}, { flush: 'sync' })
watch(busy, value => emit('busy', value), { immediate: true, flush: 'sync' })
watch(() => draftDirty.value || reviewDirty.value, value => emit('dirty', value), { immediate: true, flush: 'sync' })
onUnmounted(() => { active = false; abortReads(); clearTimeout(expiry); unsubscribe(); emit('busy', false); emit('dirty', false) })
</script>

<template>
  <section class="expense-draft-assist" aria-label="报销填报建议">
    <header><div><h3>按行程生成费用草稿</h3><p>选择本次发送内容，逐项核对行程、类别和成本分摊。</p></div><span>人工确认</span></header>
    <p>助手只建议费用行和分摊比例。金额与分摊金额由你填写，补贴按现有制度计算。</p>
    <p v-if="applicationDirty" class="warning">费用有未保存修改，请先保存或放弃修改，再使用填报建议。</p>
    <p v-if="error" class="error" role="alert">{{ error }}</p><p v-if="notice" role="status">{{ notice }}</p>
    <p v-if="unknown" class="warning" role="status">写入结果尚未确认，请使用页面的“恢复原操作”，不要重新发起。</p>
    <section v-if="report.editable && !denied" aria-label="填写本次发送内容">
      <h4>1. 行程和可选目录</h4><p v-if="!inputCurrent" class="warning">财务目录已到期，请先清除本次输入，再刷新财务目录。</p>
      <fieldset :disabled="locked || reviewDirty || !inputCurrent"><legend>填写要求与行程</legend>
        <label>填报要求<textarea v-model="brief" maxlength="8000" rows="3" placeholder="说明本次费用用途和需要哪些建议" /></label>
        <div v-for="(leg, index) in itinerary" :key="leg.id" class="itinerary-row">
          <strong>行程 {{ leg.id }}</strong><div class="date-grid"><label>开始日期<input v-model="leg.startsOn" type="date" /></label><label>结束日期<input v-model="leg.endsOn" type="date" /></label>
            <label>城市<select v-model="leg.cityCode"><option value="" disabled>请选择城市</option><option v-for="city in catalog.cities" :key="city.code" :value="city.code">{{ city.name }}</option></select></label></div>
          <label>行程用途<textarea v-model="leg.purpose" maxlength="2000" rows="2" /></label><button type="button" class="quiet" :disabled="itinerary.length === 1" @click="itinerary.splice(index, 1)">移除此行程</button>
        </div>
        <button type="button" class="secondary" :disabled="itinerary.length >= 20" @click="addLeg">＋ 添加行程</button>
        <div class="catalog-grid"><fieldset><legend>允许推荐的费用类别</legend><label v-for="c in catalog.categories" :key="c.code" class="choice"><input v-model="categories" type="checkbox" :value="c.code" />{{ c.name }}</label></fieldset>
          <fieldset><legend>允许分摊的成本中心</legend><label v-for="c in localCenters" :key="c.code" class="choice"><input v-model="centers" type="checkbox" :value="c.code" />{{ c.name }}</label></fieldset>
          <fieldset><legend>可选项目</legend><label v-for="p in localProjects" :key="p.code" class="choice"><input v-model="projects" type="checkbox" :value="p.code" />{{ p.name }}</label><p v-if="!localProjects.length">当前法人没有可选项目。</p></fieldset></div>
      </fieldset>
      <div class="toolbar"><button type="button" class="secondary" :disabled="locked || reviewDirty || !inputCurrent" @click="showPreview">预览将发送的内容</button><button type="button" class="quiet" :disabled="locked" @click="clearDraft">清除本次输入与预览</button></div>
      <p v-if="loading.preview" role="status">正在核对本人可用的目录…</p><p v-if="errors.preview" class="error" role="alert">{{ errors.preview }}</p>
      <section v-if="preview" class="preview" aria-label="确认模型发送清单">
        <h4>2. 核对发送清单</h4><p>发送至 {{ preview.providerId }} · {{ preview.model }} · {{ preview.destination }}</p><p>本次依据有效至 {{ timeLabel(preview.input.validUntil) }}。</p>
        <details v-for="source in preview.input.sources" :key="source.reference.sourceId"><summary>{{ source.label }} · 查看原文</summary><pre>{{ source.content }}</pre></details>
        <label class="choice"><input v-model="consent" type="checkbox" :disabled="locked" />我已核对上述行程、目录和发送目的地</label>
        <button type="button" class="primary" :disabled="!canGenerate" @click="generate">发送所选内容并生成建议</button>
      </section>
    </section>
    <section aria-label="报销填报建议历史">
      <div class="toolbar"><h4>3. 原建议与确认记录</h4><button type="button" class="quiet" :disabled="!canNavigate" @click="loadPage(page?.page ?? 0)">刷新记录</button></div>
      <p v-if="loading.list" role="status">正在读取记录…</p><p v-if="errors.list" class="error" role="alert">{{ errors.list }}</p>
      <template v-if="page"><p v-if="!page.items.length">尚无填报建议。</p><ul><li v-for="row in page.items" :key="row.id"><button type="button" class="quiet" :disabled="!canNavigate" :aria-pressed="selectedId === row.id" @click="loadDetail(row.id)">{{ timeLabel(row.createdAt) }} · {{ expenseAssistStatuses[row.status] }}</button></li></ul>
        <div class="toolbar"><button type="button" class="quiet" :disabled="!canNavigate || page.page === 0" @click="loadPage(page.page - 1)">上一页</button><span>第 {{ page.page + 1 }} 页 · {{ page.total }} 条</span><button type="button" class="quiet" :disabled="!canNavigate || page.page >= 10000 || (page.page + 1) * page.pageSize >= page.total" @click="loadPage(page.page + 1)">下一页</button></div></template>
      <button v-if="selectedId" type="button" class="quiet" :disabled="!canNavigate" @click="loadDetail(selectedId)">刷新当前建议</button>
      <p v-if="loading.detail" role="status">正在读取原建议…</p><p v-if="errors.detail" class="error" role="alert">{{ errors.detail }}</p>
      <article v-if="detail"><h4>{{ expenseAssistStatuses[detail.status] }}</h4>
        <p v-if="detail.failure" class="warning">{{ expenseAssistFailures[detail.failure] }}</p>
        <p v-if="['COMPLETED', 'CONFIRMED'].includes(detail.status) && !current" class="warning">单据、目录或有效期已变化，当前不能确认或填入旧建议。</p>
        <p v-if="detail.suggestion && !detail.suggestion.lines.length">没有生成可靠建议，可放弃这条记录后自行填写。</p>
        <fieldset v-for="row in detail.suggestion?.lines ?? []" :key="row.id" :disabled="!canReview || !current || !detail.canConfirm" class="proposal">
          <legend>建议 {{ row.id }}</legend><p>{{ legFor(row.itineraryId)?.startsOn }} 至 {{ legFor(row.itineraryId)?.endsOn }} · {{ detail.input.options.cities.find(c => c.code === legFor(row.itineraryId)?.cityCode)?.name }}</p>
          <p>{{ row.description }}</p><p>类别：{{ categoryName(row.categoryCode) }} · 单位：{{ expenseUnits[row.unit] }}</p>
          <ul><li v-for="(allocation, index) in row.allocations" :key="index">{{ centerName(allocation.costCenter) }}<template v-if="allocation.projectCode"> / {{ projectName(allocation.projectCode) }}</template> · 建议 {{ allocation.percent }}%</li></ul>
          <template v-if="detail.status === 'COMPLETED'"><label class="choice"><input v-model="choices[row.id]" type="checkbox" value="ITINERARY" />新建此行：采用上述日期、城市和说明</label><label class="choice"><input v-model="choices[row.id]" type="checkbox" value="CATEGORY" />同时采用费用类别和单位</label><label class="choice"><input v-model="choices[row.id]" type="checkbox" value="ALLOCATION" />同时采用成本中心和项目，金额稍后填写</label></template>
          <p v-else-if="detail.review">已确认：{{ detail.review.selected.find(s => s.proposalId === row.id)?.parts.map(p => ({ ITINERARY: '行程', CATEGORY: '类别', ALLOCATION: '分摊对象' })[p]).join('、') || '未选择此行' }}</p>
        </fieldset>
        <details v-if="detail.suggestion"><summary>查看原始来源与逐行引用</summary><div v-for="source in detail.input.sources" :key="source.reference.sourceId"><strong>{{ source.label }}</strong><pre>{{ source.content }}</pre></div><p v-for="row in detail.suggestion.lines" :key="row.id">建议 {{ row.id }}：{{ row.evidence.map(r => detail!.input.sources.find(s => s.reference.sourceId === r.sourceId)?.label).join('、') }}</p></details>
        <template v-if="detail.status === 'COMPLETED'"><p v-if="!fitsDraft" class="warning">本单最多 200 行，当前还能新增 {{ Math.max(0, 200 - report.content.lines.length) }} 行，请减少本次选择。</p><p v-if="selected.length && !validSelection" class="warning">选择类别或分摊前，请同时明确勾选该建议的“新建此行”。</p><label>确认或放弃说明<textarea v-model="comment" :disabled="!canReview" maxlength="2000" rows="2" /></label>
          <div class="toolbar"><button type="button" class="primary" :disabled="!canReview || !current || !detail.canConfirm || !validSelection || !fitsDraft" @click="review(true)">记录逐项确认</button><button type="button" class="secondary" :disabled="!canReview" @click="review(false)">放弃此建议</button><button type="button" class="quiet" :disabled="locked" @click="clearReview">清除本次勾选与说明</button></div></template>
        <template v-if="detail.status === 'CONFIRMED'"><p>填入后仅追加已确认的费用行，金额与分摊金额留空；请回到费用行填写、核对补贴规则并保存。</p><button type="button" class="primary" :disabled="!canFill" @click="fill">填入费用草稿</button></template>
      </article>
    </section>
  </section>
</template>

<style scoped>
.expense-draft-assist{margin-top:24px;padding:22px;border:1px solid var(--line);border-radius:12px;background:var(--paper);font-size:12px;line-height:1.8;overflow-wrap:anywhere;min-width:0}.expense-draft-assist header,.toolbar{display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap}.expense-draft-assist h3{font-size:17px;margin:0}.expense-draft-assist h4{font-size:14px}.expense-draft-assist header>span{color:var(--deep);padding:4px 10px;background:#e4f5ef;border-radius:6px}.expense-draft-assist fieldset{min-width:0;border:1px solid var(--line);border-radius:8px;margin:12px 0;padding:14px}.expense-draft-assist label{display:grid;gap:6px;margin:10px 0}.expense-draft-assist .choice{display:flex;align-items:flex-start;gap:8px}.choice input{width:auto;flex-shrink:0;margin-top:5px}.expense-draft-assist input:not([type=checkbox]),.expense-draft-assist select,.expense-draft-assist textarea{width:100%;min-width:0;padding:9px;border:1px solid var(--line);border-radius:6px;background:white;color:var(--ink);font:inherit}.expense-draft-assist textarea{resize:vertical}.date-grid,.catalog-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px}.catalog-grid fieldset{max-height:240px;overflow:auto}.itinerary-row{padding:12px 0;border-bottom:1px solid var(--line)}.preview,.proposal{background:white;padding:14px;border-radius:8px}.expense-draft-assist pre{white-space:pre-wrap;word-break:break-word;font-size:11px;max-height:260px;overflow:auto;background:#f4f5f3;padding:12px}.expense-draft-assist details{margin:12px 0}.expense-draft-assist summary{cursor:pointer;color:var(--deep)}.warning{color:#805c27;background:#fff6df;padding:12px;border-radius:8px}.error{color:var(--red);background:#fff0ea;padding:12px;border-radius:8px}.expense-draft-assist li{margin:7px 0}@media(max-width:700px){.expense-draft-assist{padding:14px}.date-grid,.catalog-grid{grid-template-columns:1fr}}
</style>
