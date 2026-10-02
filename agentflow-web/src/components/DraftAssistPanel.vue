<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import { api } from '../api'
import FormFields from './FormFields.vue'
import { rawValueLabel, ownValue } from '../formSchema'
import { draftError, draftSelections, draftStatuses, proposalField, proposalLabel, rememberDraftRun, focusedDraftRun,
  type DraftAssistInput, type DraftAssistPage, type DraftAssistDetail, type ReviewDraftInput } from '../draftAssist'
import type { AssistReference, AssistSource } from '../assistRuns'

const props = defineProps<{ applicationId: string; scopeKey: string; version: number; editable: boolean; locked: boolean; applicationDirty: boolean }>()
const emit = defineEmits<{ saved: []; busy: [value: boolean]; dirty: [value: boolean] }>()
const options = ref<DraftAssistInput | null>(null), page = ref<DraftAssistPage | null>(null), detail = ref<DraftAssistDetail | null>(null)
const selectedId = ref(''), brief = ref(''), sourceIds = ref<string[]>([]), selected = ref<string[]>([])
const values = ref<Record<string, unknown>>({}), comment = ref(''), notice = ref(''), error = ref(''), sending = ref(false)
const errors = reactive({ input: '', list: '', detail: '' }), loading = reactive({ input: false, list: false, detail: false })
type Resource = keyof typeof loading
const requests: Record<Resource, { generation: number; controller: AbortController | null }> = {
  input: { generation: 0, controller: null }, list: { generation: 0, controller: null }, detail: { generation: 0, controller: null }
}
const initialValues = ref('{}')
const READ_TIMEOUT_MS = 12_000
let active = true
const context = computed(() => JSON.stringify([props.scopeKey, props.applicationId]))
const reviewDirty = computed(() => selected.value.length > 0 || comment.value.length > 0 || JSON.stringify(values.value) !== initialValues.value)
const dirty = computed(() => !!brief.value || sourceIds.value.length > 0 || reviewDirty.value)
const locked = computed(() => props.locked || props.applicationDirty || sending.value)
const canGenerate = computed(() => !locked.value && !reviewDirty.value && props.editable && !loading.input && options.value?.enabled
  && options.value.applicationVersion === props.version && !!brief.value.trim() && brief.value.length <= 8000 && sourceIds.value.length <= 63
  && sourceIds.value.every(id => options.value!.sources.some(source => source.reference.sourceId === id)))
const canReview = computed(() => !locked.value && !brief.value && !sourceIds.value.length && !loading.detail && detail.value?.status === 'COMPLETED')
const current = computed(() => !!detail.value?.canAdopt && props.editable && detail.value.applicationVersion === props.version)
const canNavigate = computed(() => !sending.value && !props.locked && !reviewDirty.value)
const failureLabels = { MODEL_UNAVAILABLE: '模型服务不可用，请核对配置后重新明确发起。', MODEL_TIMEOUT: '执行已超时，系统没有自动重新发送内容。',
  INVALID_MODEL_OUTPUT: '模型返回内容未通过字段或来源校验，未修改申请。', INPUT_UNAVAILABLE: '发送前申请已变化或不可编辑，未发送本次内容。' }
const label = (id: string) => detail.value ? proposalLabel(detail.value.targetSchema, id) : ''
const singleSchema = (id: string) => { const field = detail.value && proposalField(detail.value.targetSchema, id); return { schemaVersion: detail.value?.targetSchema.schemaVersion ?? 1, fields: field ? [field] : [] } }
const fieldValue = (id: string) => ({ [id.slice(5)]: ownValue(values.value, id) })
function editValue(id: string, payload: Record<string, unknown>) { values.value = { ...values.value, [id]: ownValue(payload, id.slice(5)) } }
const time = (value: string) => new Date(value).toLocaleString('zh-CN')
function sourceText(source: AssistSource) { try { return rawValueLabel(JSON.parse(source.content)) } catch { return source.content } }
function evidence(reference: AssistReference) { return detail.value?.sources.find(source => source.reference.sourceId === reference.sourceId && source.reference.contentDigest === reference.contentDigest) }
function resetReview() {
  selected.value = []; comment.value = ''
  values.value = Object.fromEntries((detail.value?.suggestion?.proposals ?? []).map(p => [p.targetId, JSON.parse(JSON.stringify(p.value))]))
  initialValues.value = JSON.stringify(values.value)
}
function clearInput() { brief.value = ''; sourceIds.value = []; resetReview(); error.value = ''; notice.value = '' }
function abortReads() {
  for (const name of Object.keys(requests) as Resource[]) { requests[name].generation++; requests[name].controller?.abort(); loading[name] = false; errors[name] = '' }
}
function reset() {
  abortReads(); options.value = null; page.value = null; detail.value = null; selectedId.value = ''; clearInput(); sending.value = false
}
/** 每类读取独立失效；请求超时会解除等待，账号切换后迟到结果不能回填。 */
async function read<T>(name: Resource, fetch: (signal: AbortSignal) => Promise<T>, apply: (value: T) => void) {
  if (!active) return
  const slot = requests[name], generation = ++slot.generation, original = context.value
  slot.controller?.abort(); const controller = new AbortController(); slot.controller = controller
  loading[name] = true; errors[name] = ''; let timedOut = false
  const valid = () => active && original === context.value && generation === slot.generation
  let timer: ReturnType<typeof setTimeout> | undefined
  const timeout = new Promise<never>((_, reject) => {
    timer = setTimeout(() => {
      timedOut = true; controller.abort()
      if (valid()) { loading[name] = false; errors[name] = '读取超时，请刷新重试。' }
      reject(new Error('读取超时，请刷新重试。'))
    }, READ_TIMEOUT_MS)
  })
  try { const value = await Promise.race([fetch(controller.signal), timeout]); if (valid() && !timedOut) apply(value) }
  catch (cause) {
    if (valid() && !timedOut) {
      const status = (cause as { status?: number })?.status
      if (status === 401 || status === 403) reset()
      errors[name] = draftError(cause)
    }
  } finally { clearTimeout(timer); if (valid()) loading[name] = false }
}
function loadInput() {
  options.value = null; sourceIds.value = []
  if (!props.editable) { requests.input.generation++; requests.input.controller?.abort(); loading.input = false; return Promise.resolve() }
  return read('input', signal => api.draftAssistInput(props.applicationId, signal), value => { options.value = value })
}
async function loadPage(number = 0, afterWrite = false) {
  if (!afterWrite && !canNavigate.value || number < 0 || number > 10000) return
  page.value = null
  await read('list', signal => api.draftAssistRuns(props.applicationId, number, signal), value => { page.value = value })
}
async function loadDetail(id: string, afterWrite = false) {
  if (!active || !afterWrite && !canNavigate.value) return
  selectedId.value = id; detail.value = null; resetReview()
  rememberDraftRun(props.scopeKey, props.applicationId, id)
  await read('detail', signal => api.draftAssistRun(props.applicationId, id, signal), value => {
    detail.value = value; resetReview()
    const item = page.value?.items.find(item => item.id === value.id)
    if (item) { item.status = value.status; item.version = value.version }
  })
}
/** 清空输入只影响未发送或未保存的本地修订，不删除模型记录。 */
function discardEdits() { if (!sending.value && !props.locked) clearInput() }
async function generate() {
  if (!canGenerate.value || !options.value?.targetDigest) return
  const original = context.value
  sending.value = true; error.value = ''; notice.value = ''
  try {
    const receipt = await api.generateDraftAssist(props.applicationId, { expectedVersion: props.version, targetDigest: options.value.targetDigest, brief: brief.value, sourceIds: [...sourceIds.value] })
    if (!active || original !== context.value) return
    brief.value = ''; sourceIds.value = []
    rememberDraftRun(props.scopeKey, props.applicationId, receipt.id)
    notice.value = '已排队生成。申请内容保持不变，可刷新记录查看结果。'
    await loadPage(0, true)
    if (!active || original !== context.value) return
    await loadDetail(receipt.id, true)
  } catch (cause) { if (active && original === context.value) error.value = draftError(cause) }
  finally { if (active && original === context.value) sending.value = false }
}
async function review(action: 'ADOPT' | 'DISMISS') {
  if (!canReview.value || !detail.value || action === 'ADOPT' && !current.value) return
  const original = context.value, id = detail.value.id
  let body: ReviewDraftInput
  try {
    if (comment.value.length > 2000) throw new Error('核对说明最多 2,000 字符。')
    body = { expectedRunVersion: detail.value.version, action, comment: comment.value,
      ...(action === 'ADOPT' ? { expectedApplicationVersion: props.version, selected: draftSelections(detail.value, selected.value, values.value) } : {}) }
  } catch (cause) { error.value = draftError(cause); return }
  sending.value = true; error.value = ''; notice.value = ''
  try {
    await api.reviewDraftAssist(props.applicationId, id, body)
    if (!active || original !== context.value) return
    resetReview()
    notice.value = action === 'ADOPT' ? '勾选字段已保存到草稿，尚未提交审批。' : '已记录未采纳，申请内容保持不变。'
    await loadPage(page.value?.page ?? 0, true)
    if (!active || original !== context.value) return
    await loadDetail(id, true)
    if (active && original === context.value && action === 'ADOPT') emit('saved')
  } catch (cause) { if (active && original === context.value) error.value = draftError(cause) }
  finally { if (active && original === context.value) sending.value = false }
}
watch(context, () => {
  reset(); if (!props.scopeKey || !props.applicationId) return
  const original = context.value
  void loadInput(); void loadPage().then(() => {
    if (!active || original !== context.value) return
    const id = focusedDraftRun(props.scopeKey, props.applicationId); if (id) void loadDetail(id)
  })
}, { immediate: true, flush: 'sync' })
watch([() => props.version, () => props.editable], () => { void loadInput() }, { flush: 'sync' })
watch(sending, value => emit('busy', value), { flush: 'sync' })
watch(dirty, value => emit('dirty', value), { immediate: true, flush: 'sync' })
onUnmounted(() => { active = false; abortReads(); emit('busy', false); emit('dirty', false) })
</script>

<template>
  <section class="draft-assist" aria-label="草稿助手">
    <header><div><h3>草稿助手</h3><p>整理已有事实，逐项核对后保存到草稿。</p></div><span class="draft-tag">人工确认后保存</span></header>
    <p v-if="applicationDirty" class="draft-warning">申请有未保存的修改，请先使用上方“保存修改”，再生成或采纳建议。</p>
    <p v-if="error" class="draft-error" role="alert">{{ error }}</p><p v-if="notice" class="draft-notice" role="status">{{ notice }}</p>
    <section v-if="editable" class="draft-input" aria-label="选择生成内容">
      <div class="draft-toolbar"><h4>1. 说明需要整理的内容</h4><button type="button" class="quiet" :disabled="loading.input || locked" @click="loadInput">刷新发送目录</button></div>
      <p>本次说明会发送给下方模型。已有字段只发送你勾选的内容；敏感字段、附件和含受限列的明细不参与。</p>
      <p v-if="loading.input" role="status">正在读取可发送内容…</p><p v-if="errors.input" class="draft-error" role="alert">{{ errors.input }}</p>
      <template v-if="options">
        <p v-if="options.enabled" class="draft-destination">发送至 {{ options.providerId }} · {{ options.model }} · {{ options.destination }}</p>
        <p v-else role="status">{{ draftError({ code: options.unavailableCode }) }}</p>
        <label class="draft-label">本次生成要求<textarea v-model="brief" rows="3" maxlength="8000" :disabled="locked || reviewDirty || !options.enabled" placeholder="例如：根据我勾选的说明，整理申请用途；缺乏依据的日期或金额请留空。" /></label>
        <fieldset :disabled="locked || reviewDirty || !options.enabled"><legend>可选已有内容 · 已选 {{ sourceIds.length }} 项</legend>
          <label v-for="source in options.sources" :key="source.reference.sourceId" class="draft-source"><input v-model="sourceIds" type="checkbox" :value="source.reference.sourceId" :aria-label="`发送${source.label}`" /><span><strong>{{ source.label }}</strong><span>{{ sourceText(source) }}</span></span></label>
        </fieldset>
        <button type="button" class="secondary" :disabled="!canGenerate" @click="generate">{{ sending ? '正在保存请求…' : '发送所选内容并生成建议' }}</button>
      </template>
    </section>
    <section class="draft-records" aria-label="草稿建议记录">
      <div class="draft-toolbar"><h4>{{ editable ? '2. 核对生成结果' : '历史草稿建议' }}</h4><button type="button" class="quiet" :disabled="!canNavigate || loading.list" @click="loadPage(page?.page ?? 0)">刷新记录</button></div>
      <p v-if="loading.list" role="status">正在读取建议记录…</p><p v-if="errors.list" class="draft-error" role="alert">{{ errors.list }}</p>
      <template v-if="page">
        <p v-if="!page.items.length">暂无建议记录。</p>
        <ul v-else class="draft-run-list"><li v-for="run in page.items" :key="run.id"><button type="button" :disabled="!canNavigate" :aria-pressed="selectedId === run.id" @click="loadDetail(run.id)"><strong>{{ draftStatuses[run.status] }}</strong><span>申请版本 {{ run.applicationVersion }} · {{ time(run.createdAt) }}</span></button></li></ul>
        <div v-if="page.total > page.pageSize || page.page > 0" class="draft-toolbar"><span>第 {{ page.page + 1 }} 页 · 共 {{ page.total }} 条</span><div><button type="button" :disabled="!canNavigate || loading.list || page.page === 0" @click="loadPage(page.page - 1)">上一页</button><button type="button" :disabled="!canNavigate || loading.list || page.page >= 10000 || (page.page + 1) * page.pageSize >= page.total" @click="loadPage(page.page + 1)">下一页</button></div></div>
      </template>
      <p v-if="loading.detail" role="status">正在读取原始建议与来源…</p><p v-if="errors.detail" class="draft-error" role="alert">{{ errors.detail }}</p>
      <button v-if="selectedId" type="button" class="quiet" :disabled="!canNavigate || loading.detail" @click="loadDetail(selectedId)">刷新当前建议</button>
      <article v-if="detail" class="draft-detail" aria-label="人工核对草稿建议">
        <p><strong>{{ draftStatuses[detail.status] }}</strong> · {{ time(detail.createdAt) }}</p>
        <p v-if="['QUEUED', 'RUNNING'].includes(detail.status)">生成尚未完成，稍后可刷新当前建议。申请没有被自动修改或提交。</p>
        <p v-if="detail.failure" class="draft-warning">{{ failureLabels[detail.failure] }}</p>
        <template v-if="detail.suggestion">
          <p>{{ detail.suggestion.providerId }} · {{ detail.suggestion.modelVersion }}</p>
          <p v-if="detail.status === 'COMPLETED' && !current" class="draft-warning">申请版本或状态已变化，本次建议不能采纳；仍可查看或记录未采纳。</p>
          <p v-if="detail.status === 'COMPLETED' && (brief || sourceIds.length)" class="draft-warning">有尚未发送的生成内容，请先发送或清空，再核对历史建议。</p>
          <p v-if="detail.status === 'COMPLETED'">先核对模型原值及来源，再勾选要保存的字段。可以修改勾选值；未勾选字段保持原样。</p>
          <section v-for="proposal in detail.suggestion.proposals" :key="proposal.targetId" class="draft-proposal">
            <label v-if="detail.status === 'COMPLETED'" class="draft-choice"><input v-model="selected" type="checkbox" :value="proposal.targetId" :disabled="!canReview || !current" :aria-label="`采纳${label(proposal.targetId)}`" /><strong>保存{{ label(proposal.targetId) }}</strong></label>
            <h5 v-else>{{ label(proposal.targetId) }}</h5>
            <div class="draft-original"><small>模型原值</small><pre>{{ rawValueLabel(proposal.value) }}</pre></div>
            <template v-if="detail.status === 'COMPLETED' && selected.includes(proposal.targetId)">
              <label v-if="proposal.targetId === 'application:title'" class="draft-label">人工核对的申请标题<input v-model="values[proposal.targetId]" maxlength="256" :disabled="!canReview || !current" /></label>
              <FormFields v-else :schema="singleSchema(proposal.targetId)" :model-value="fieldValue(proposal.targetId)" :disabled="!canReview || !current" @update:model-value="editValue(proposal.targetId, $event)" />
            </template>
            <details><summary>查看来源（{{ proposal.evidence.length }} 项）</summary><ul class="draft-evidence"><li v-for="reference in proposal.evidence" :key="reference.sourceId"><strong>{{ evidence(reference)?.label }}</strong><pre>{{ evidence(reference) ? sourceText(evidence(reference)!) : '来源不可读取' }}</pre></li></ul></details>
          </section>
          <template v-if="detail.status === 'COMPLETED'">
            <label class="draft-label">核对说明（选填）<textarea v-model="comment" maxlength="2000" rows="2" :disabled="!canReview" /></label>
            <div class="draft-buttons"><button type="button" class="primary" :disabled="!canReview || !current || !selected.length" @click="review('ADOPT')">保存勾选字段到草稿</button><button type="button" class="secondary" :disabled="!canReview" @click="review('DISMISS')">记录未采纳</button></div>
          </template>
          <section v-if="detail.review" class="draft-review" aria-label="人工确认记录"><h4>人工确认记录</h4><p>{{ detail.review.actor }} · {{ time(detail.review.at) }}</p><p v-if="detail.review.comment">{{ detail.review.comment }}</p><p v-if="detail.review.appliedApplicationVersion">已保存至申请版本 {{ detail.review.appliedApplicationVersion }}。</p><dl v-if="detail.review.selected"><template v-for="value in detail.review.selected" :key="value.targetId"><dt>{{ label(value.targetId) }}</dt><dd><pre>{{ rawValueLabel(value.value) }}</pre></dd></template></dl></section>
        </template>
      </article>
    </section>
    <div v-if="dirty" class="draft-footer"><span>有尚未发送的说明或尚未保存的核对内容。</span><button type="button" class="quiet" :disabled="sending || props.locked" @click="discardEdits">清空本地助手输入</button></div>
  </section>
</template>

<style scoped>
.draft-assist{margin:24px 0;border:1px solid var(--line);border-radius:14px;background:var(--paper);font-size:12px;line-height:1.8;overflow:hidden}.draft-assist>header{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:18px 20px;background:var(--soft)}.draft-assist h3{font-size:16px;margin:0}.draft-assist h4{font-size:13px;margin:0}.draft-assist h5{font-size:12px;margin:0 0 8px}.draft-assist header p{margin:3px 0 0;color:var(--muted)}.draft-tag{color:var(--deep);font-size:11px;white-space:nowrap}.draft-input,.draft-records{padding:18px 20px}.draft-records{border-top:1px solid var(--line)}.draft-toolbar,.draft-buttons,.draft-footer{display:flex;align-items:center;justify-content:space-between;gap:12px}.draft-toolbar>div,.draft-buttons{display:flex;gap:8px;flex-wrap:wrap}.draft-buttons{justify-content:flex-start}.draft-label{display:grid;gap:6px;margin:12px 0}.draft-label textarea,.draft-label input{width:100%;box-sizing:border-box;border:1px solid var(--line);border-radius:8px;padding:10px 12px;font:inherit;color:var(--ink);background:#fff}.draft-label textarea{resize:vertical}.draft-assist fieldset{padding:0;border:0;margin:16px 0}.draft-assist legend{padding:0;font-weight:600}.draft-source{display:flex;align-items:flex-start;gap:10px;padding:10px 0;border-bottom:1px solid var(--line)}.draft-source>span{display:grid;gap:3px;min-width:0}.draft-source>span>span{white-space:pre-wrap;overflow-wrap:anywhere;max-height:120px;overflow:auto;color:var(--muted)}.draft-source input,.draft-choice input{width:16px;height:16px;flex:none;margin:4px 0}.draft-choice{display:flex;gap:8px;align-items:flex-start;margin-bottom:10px}.draft-destination{overflow-wrap:anywhere;color:var(--deep)}.draft-run-list{list-style:none;margin:12px 0;padding:0;display:grid;gap:7px}.draft-run-list button{display:flex;align-items:center;justify-content:space-between;gap:8px;width:100%;text-align:left;padding:10px 12px;border:1px solid var(--line);border-radius:8px;background:var(--paper);color:var(--ink)}.draft-run-list button[aria-pressed="true"]{border-color:var(--deep);background:var(--soft)}.draft-run-list span{color:var(--muted);font-size:11px}.draft-proposal{padding:16px;margin:12px 0;border:1px solid var(--line);border-radius:10px;min-width:0}.draft-original{background:#f5f7f7;border-radius:6px;padding:8px 12px;margin-bottom:12px}.draft-original small{color:var(--muted)}.draft-assist pre{font:inherit;white-space:pre-wrap;overflow-wrap:anywhere;margin:4px 0;max-height:280px;overflow:auto}.draft-evidence{padding-left:18px}.draft-evidence li{margin:10px 0}.draft-assist summary{cursor:pointer;color:var(--deep)}.draft-review{border-top:1px solid var(--line);padding-top:14px}.draft-review dd{margin:0 0 12px}.draft-footer{padding:12px 20px;border-top:1px solid var(--line);color:var(--muted)}.draft-error{color:var(--red)}.draft-notice{color:var(--deep)}.draft-warning{color:var(--muted)}.draft-assist>p{padding:0 20px}.draft-assist button:focus-visible,.draft-assist input:focus-visible,.draft-assist textarea:focus-visible,.draft-assist summary:focus-visible{outline:3px solid rgba(33,173,159,.35);outline-offset:2px}@media(max-width:640px){.draft-assist>header,.draft-toolbar,.draft-footer{align-items:flex-start;flex-wrap:wrap}.draft-run-list button{align-items:flex-start;flex-direction:column}.draft-input,.draft-records,.draft-assist>header{padding:14px}.draft-proposal{padding:12px}.draft-buttons>*{flex:1}.draft-tag{white-space:normal}}
</style>
