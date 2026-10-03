<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import { api } from '../api'
import { DefinitionSelection } from '../definitionSelection'
import type { DefinitionCatalogItem } from '../definitionCatalog'
import { expenseError } from '../expenses'
import type { BudgetAdjustmentDetail } from '../budgetAdjustment'
import { emptyBudgetAdjustment, budgetAdjustmentContent, budgetAdjustmentDefinition, budgetAdjustmentDrafts, budgetAdjustmentIssues, budgetAdjustmentTypes, type BudgetAdjustmentDraftState } from '../budgetAdjustment'
import type { FinanceCatalog } from '../expenseDraft'
import DefinitionPicker from './DefinitionPicker.vue'
import BudgetAdjustmentSubmission from './BudgetAdjustmentSubmission.vue'

const props = defineProps<{ scopeKey: string; initial?: BudgetAdjustmentDetail; locked?: boolean }>()
const emit = defineEmits<{ close: []; submitted: [applicationId: string]; busy: [value: boolean] }>()
const state = ref<BudgetAdjustmentDraftState>({ detail: null, receipt: null, content: emptyBudgetAdjustment(), businessNo: '', definition: null, baseline: JSON.stringify(emptyBudgetAdjustment()), pending: null, requiresRefresh: false })
const catalog = ref<FinanceCatalog | null>(null), loading = ref(false), saving = ref(false), childBusy = ref(false), error = ref(''), notice = ref(''), discard = ref(false)
const selection = reactive(new DefinitionSelection(api.searchDefinitions, api.getDefinition))
const sessionKey = computed(() => props.initial?.id ?? '')
const dirty = computed(() => JSON.stringify(state.value.content) !== state.value.baseline || !state.value.detail && !!state.value.businessNo.trim())
const blocked = computed(() => props.locked || saving.value || childBusy.value || state.value.requiresRefresh)
const entity = computed(() => catalog.value?.legalEntities.find(value => value.id === state.value.content.legalEntityId))
let epoch = 0, controller: AbortController | null = null, leaving = false
function preserve() { budgetAdjustmentDrafts.put(props.scopeKey, sessionKey.value, state.value) }
function stop() { epoch++; controller?.abort(); controller = null; selection.clear() }
function initialize() {
  leaving = false
  stop(); loading.value = false; saving.value = false; childBusy.value = false; discard.value = false; error.value = ''; notice.value = ''; catalog.value = null
  const restored = budgetAdjustmentDrafts.get(props.scopeKey, sessionKey.value)
  const detail = props.initial ? JSON.parse(JSON.stringify(props.initial)) as BudgetAdjustmentDetail : null
  state.value = restored ?? { detail, receipt: null, content: detail?.content ?? emptyBudgetAdjustment(), businessNo: detail?.businessNo ?? '', definition: null,
    baseline: JSON.stringify(detail?.content ?? emptyBudgetAdjustment()), pending: null, requiresRefresh: false }
  if (restored) notice.value = '已恢复本页暂存的填报内容，请核对后继续。'
  if (props.scopeKey) void loadCatalog()
}
async function loadCatalog() {
  if (loading.value || saving.value || childBusy.value) return
  controller?.abort(); const version = ++epoch, request = new AbortController(); controller = request
  loading.value = true; catalog.value = null; error.value = ''
  const timeout = setTimeout(() => { if (version === epoch) { epoch++; request.abort(); loading.value = false; error.value = '财务目录读取超时，请重试。' } }, 12_000)
  try {
    const value = await api.financeCatalog(request.signal)
    if (version !== epoch) return
    if (!(Date.parse(value.validUntil) > Date.now())) throw new Error('Expired catalog')
    catalog.value = value
  } catch (cause) { if (version === epoch) error.value = '当前无法读取本人财务目录，请检查财务连接后重试。' }
  finally { clearTimeout(timeout); if (version === epoch) { loading.value = false; controller = null } }
}
async function selectDefinition(item: DefinitionCatalogItem) {
  if (blocked.value || state.value.detail) return
  state.value.definition = null; error.value = ''
  const version = epoch
  const definition = await selection.load(props.scopeKey, item.id, { startEnabledOnly: true })
  if (version !== epoch || !definition) return
  if (!budgetAdjustmentDefinition(definition)) { error.value = '所选版本不支持预算调整，请选择包含预算调整明细并经过人工审批的预算调整流程。'; return }
  state.value.definition = definition
}
function changeEntity() {
  state.value.content.amount = { value: '', currency: entity.value?.baseCurrency ?? '' }
  state.value.content.sourceBudgetReference = state.value.content.type === 'INCREASE' ? null : ''
  state.value.content.targetBudgetReference = state.value.content.type === 'DECREASE' ? null : ''
}
function changeType() {
  state.value.content.sourceBudgetReference = state.value.content.type === 'INCREASE' ? null : state.value.content.sourceBudgetReference ?? ''
  state.value.content.targetBudgetReference = state.value.content.type === 'DECREASE' ? null : state.value.content.targetBudgetReference ?? ''
}
async function save() {
  if (blocked.value || loading.value || selection.loading || !catalog.value) return
  error.value = ''; notice.value = ''; discard.value = false
  const detail = state.value.detail, definition = state.value.definition
  if (!detail && (!definition || !budgetAdjustmentDefinition(definition) || !state.value.businessNo.trim())) { error.value = '请填写预算调整单编号并选择可发起的预算调整流程。'; return }
  let content
  try { content = budgetAdjustmentContent(state.value.content, catalog.value) }
  catch (cause) { error.value = (cause as Error).message; return }
  const body = detail ? { applicationVersion: detail.applicationVersion, requestVersion: detail.requestVersion, content }
    : { businessNo: state.value.businessNo.trim(), processKey: definition!.key, definitionVersion: definition!.version, content }
  const path = detail ? `/budget-adjustments/${encodeURIComponent(detail.id)}/revise` : '/budget-adjustments'
  const version = epoch, scope = props.scopeKey
  state.value.pending = { path, body: JSON.stringify(body) }; preserve(); saving.value = true
  try {
    const result = detail ? await api.reviseBudgetAdjustment(detail.id, body as Parameters<typeof api.reviseBudgetAdjustment>[1]) : await api.createBudgetAdjustment(body as Parameters<typeof api.createBudgetAdjustment>[0])
    if (version !== epoch) return
    if (!budgetAdjustmentDrafts.acknowledge(scope, path, JSON.stringify(body), result)) throw new Error('BudgetAdjustment save receipt mismatch')
    notice.value = '预算调整已保存，正在核对服务器当前版本。'
  } catch (cause) {
    if (version !== epoch) return
    error.value = budgetAdjustmentIssues[(cause as { code?: string }).code ?? ''] ?? expenseError(cause); state.value.requiresRefresh = true
    const status = (cause as { status?: number }).status
    if (status && status >= 400 && status < 500 && status !== 401) state.value.pending = null
  } finally { if (version === epoch) { saving.value = false; preserve() } }
  if (version === epoch && state.value.receipt && !state.value.pending) await reloadSaved()
}
function close() {
  if (saving.value || childBusy.value || props.locked) return
  if (dirty.value || state.value.pending) { discard.value = true; return }
  leave()
}
function leave() { if (!saving.value && !childBusy.value && !props.locked) { leaving = true; budgetAdjustmentDrafts.clear(props.scopeKey, sessionKey.value); emit('close') } }
function submitted(applicationId: string) { leaving = true; budgetAdjustmentDrafts.clear(props.scopeKey, sessionKey.value); emit('submitted', applicationId) }
/** 冲突后明确放弃本地内容并读取服务器版本，不自动用新版本再次写入。 */
async function reloadSaved() {
  const saved = state.value.receipt ?? state.value.detail
  if (loading.value || saving.value || childBusy.value || props.locked || state.value.pending || !saved) return
  controller?.abort(); const version = ++epoch, id = saved.id, applicationId = saved.applicationId, request = new AbortController()
  controller = request; saving.value = true; error.value = ''
  const timeout = setTimeout(() => { if (version === epoch) { epoch++; request.abort(); saving.value = false; error.value = '服务器版本读取超时，请重试。' } }, 12_000)
  try {
    const detail = await api.budgetAdjustment(id, undefined, request.signal)
    if (version !== epoch) return
    if (detail.id !== id || detail.applicationId !== applicationId || !detail.editable) { error.value = '当前单据不能继续编辑，请返回预算调整详情核对。'; return }
    state.value = { ...state.value, detail, receipt: null, businessNo: detail.businessNo, content: detail.content, baseline: JSON.stringify(detail.content), pending: null, requiresRefresh: false }
    discard.value = false; notice.value = '已载入保存的预算调整内容。选择本次任职后，可核对原预算台账并提交。'
  } catch (cause) { if (version === epoch) error.value = expenseError(cause) }
  finally { clearTimeout(timeout); if (version === epoch) { saving.value = false; controller = null } }
}
const unsubscribe = budgetAdjustmentDrafts.subscribe((scope, key) => {
  if (scope !== props.scopeKey || key !== sessionKey.value) return
  const restored = budgetAdjustmentDrafts.get(scope, key)
  if (restored) { state.value = restored; error.value = ''; notice.value = '原保存结果已确认，请核对内容后继续。' }
})
watch(() => JSON.stringify([props.scopeKey, props.initial?.id]), initialize, { immediate: true, flush: 'sync' })
watch(state, preserve, { deep: true, flush: 'sync' })
watch(() => [saving.value, childBusy.value, dirty.value, !!state.value.pending], () => emit('busy', saving.value || childBusy.value || dirty.value || !!state.value.pending), { immediate: true, flush: 'sync' })
onUnmounted(() => { if (!leaving) preserve(); stop(); unsubscribe(); emit('busy', false) })
</script>

<template>
  <section class="expense-editor" aria-label="预算调整申请填报">
    <div class="editor-heading"><div><p class="eyebrow">BUDGET ADJUSTMENT</p><h3>{{ state.detail ? '编辑预算调整申请与提交' : '填写新的预算调整申请单' }}</h3><p>{{ state.detail ? `${state.businessNo} · 保存后沿用原流程版本` : '选择追加、调减或调拨，保存后核对原预算额度、占用与已用金额。' }}</p></div><button type="button" class="secondary" :disabled="saving || childBusy || locked" @click="close">返回</button></div>
    <p v-if="notice" class="editor-notice" role="status">{{ notice }}</p>
    <p v-if="error || selection.error" class="editor-error" role="alert">{{ error || selection.error }}</p>
    <div v-if="discard" class="discard-confirmation" role="group" aria-label="处理未保存的预算调整内容"><p>本地内容尚未保存。返回会放弃这些修改；已经保存的单据和待恢复请求仍保留。</p><button type="button" class="secondary" @click="discard = false">继续填写</button><button type="button" class="return" :disabled="saving || childBusy || locked" @click="leave">放弃本地修改并返回</button></div>
    <div v-if="state.requiresRefresh && !state.pending" class="editor-notice"><p>请先核对服务器当前版本。读取会替换本地未保存内容。</p><button v-if="state.receipt || state.detail" type="button" class="secondary" :disabled="loading || saving || childBusy || locked" @click="reloadSaved">读取已保存的预算调整内容</button><button v-else type="button" class="secondary" :disabled="locked" @click="state.requiresRefresh = false">修正填报内容</button></div>
    <p v-if="loading" class="editor-help" role="status">正在读取本人可用的财务目录…</p>
    <button type="button" class="quiet catalog-refresh" :disabled="loading || saving || childBusy || locked" @click="loadCatalog">{{ catalog ? '刷新财务目录' : '重试财务目录' }}</button>
    <form v-if="catalog" novalidate @submit.prevent="save">
      <fieldset :disabled="blocked || loading || selection.loading">
        <div v-if="!state.detail" class="definition-choice"><label>预算调整单编号<input v-model="state.businessNo" maxlength="128" required placeholder="填写业务编号" /></label><DefinitionPicker label="预算调整流程" :scope-key="scopeKey" :selected-id="state.definition?.id" :selected-label="state.definition ? `${state.definition.name} · v${state.definition.version}` : undefined" start-enabled-only published-only :locked="blocked || selection.loading" @select="selectDefinition" /><p v-if="selection.loading" class="editor-help">正在核对所选版本…</p></div>
        <div class="editor-basics">
          <label class="title-field">预算调整申请标题<input v-model="state.content.title" maxlength="256" required /></label>
          <label>预算调整法人<select v-model="state.content.legalEntityId" required @change="changeEntity"><option value="" disabled>请选择法人</option><option v-if="state.content.legalEntityId && !entity" :value="state.content.legalEntityId">原法人当前不可用</option><option v-for="legalEntity in catalog.legalEntities" :key="legalEntity.id" :value="legalEntity.id">{{ legalEntity.name }} · {{ legalEntity.baseCurrency }}</option></select></label>
          <label>调整金额{{ entity ? `（${entity.baseCurrency}）` : '' }}<input v-model="state.content.amount.value" inputmode="decimal" maxlength="18" :disabled="!entity" placeholder="0.00" required /></label>
          <label>调整类型<select v-model="state.content.type" required @change="changeType"><option v-for="(label, value) in budgetAdjustmentTypes" :key="value" :value="value">{{ label }}</option></select></label>
          <label>调整日期<input v-model="state.content.accountingDate" type="date" required /></label>
          <label v-if="state.content.type !== 'INCREASE'">调出预算编号<input v-model="state.content.sourceBudgetReference" maxlength="128" :disabled="!entity" required placeholder="财务系统中的预算编号" /></label>
          <label v-if="state.content.type !== 'DECREASE'">{{ state.content.type === 'INCREASE' ? '追加预算编号' : '调入预算编号' }}<input v-model="state.content.targetBudgetReference" maxlength="128" :disabled="!entity" required placeholder="财务系统中的预算编号" /></label>
          <label class="title-field">调整原因<input v-model="state.content.purpose" maxlength="2000" required placeholder="说明本次预算调整的原因" /></label>
        </div>
        <p class="editor-help">修改法人后需重新填写预算编号和金额。预检会读取原额度、占用、已用金额及预算期间；调拨仅支持同法人、同币种、同期间的两个不同预算。</p>
        <div class="save-toolbar"><span>{{ dirty ? '有未保存的内容' : state.detail ? '当前预算调整内容已保存' : '请填写预算调整内容后保存' }}</span><button class="primary" :disabled="blocked || loading || selection.loading || !state.detail && !state.definition">{{ saving ? '正在保存…' : '保存预算调整草稿' }}</button></div>
      </fieldset>
    </form>
    <BudgetAdjustmentSubmission v-if="state.detail?.editable && !dirty && !state.requiresRefresh && entity" :detail="state.detail" :scope-key="scopeKey" :locked="!!locked || saving" @busy="childBusy = $event" @submitted="submitted" />
    <p v-else-if="state.detail && dirty" class="editor-help">请先保存当前修改，再执行预算调整预检或提交。</p>
  </section>
</template>

<style scoped>
.expense-editor{background:white;border:1px solid var(--line);border-radius:16px;padding:28px;min-width:0}.editor-heading{display:flex;justify-content:space-between;gap:20px;align-items:start}.editor-heading h3{font-size:23px;margin:9px 0}.editor-heading p:not(.eyebrow){font-size:12px;color:var(--muted);line-height:1.8;overflow-wrap:anywhere}.editor-heading button{flex-shrink:0}.expense-editor fieldset{margin:0;padding:0;border:0;min-width:0}.definition-choice{padding:20px 0;border-bottom:1px solid var(--line);margin-bottom:20px}.expense-editor label{display:grid;gap:8px;font-size:12px;min-width:0;margin:14px 0}.expense-editor input,.expense-editor select,.expense-editor textarea{font:inherit;width:100%;min-width:0;padding:11px;border:1px solid var(--line);border-radius:7px;background:#fff;color:var(--ink)}.expense-editor textarea{resize:vertical;line-height:1.8}.editor-basics{display:grid;grid-template-columns:1fr 1fr;gap:0 18px}.title-field{grid-column:1/-1}.editor-help{font-size:12px;color:var(--muted);line-height:1.8}.catalog-refresh{font-size:12px;color:var(--deep);margin:8px 0}.editor-error,.editor-notice{font-size:12px;padding:14px;border-radius:9px;line-height:1.8;overflow-wrap:anywhere}.editor-error{color:var(--red);background:#fff1ed}.editor-notice{background:#edf9f5;color:var(--deep)}.save-toolbar{display:flex;justify-content:space-between;align-items:center;gap:16px;padding-top:20px;border-top:1px solid var(--line)}.save-toolbar span{font-size:12px;color:var(--muted)}.discard-confirmation{border:1px solid #ecc3b7;background:#fff6f1;padding:16px;font-size:12px;line-height:1.8;border-radius:10px}.discard-confirmation button{margin:4px 10px 4px 0}@media(max-width:650px){.expense-editor{padding:16px}.editor-basics{grid-template-columns:1fr}.editor-heading h3{font-size:20px}.save-toolbar{flex-wrap:wrap}}
</style>
