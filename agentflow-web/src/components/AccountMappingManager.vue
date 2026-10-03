<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import { api } from '../api'
import { mappingError, readMappingDefinition, type MappingCurrent, type MappingDirectory, type MappingDraft, type MappingScope, type MappingSummary } from '../accountMappings'
import { copyMapping, mappingDrafts, mappingInput, sameMappingDraft, type MappingEdit } from '../accountMappingDrafts'
import { type ExpenseCategories } from '../expenseConfiguration'
import { ConfigurationRead } from '../expenseConfigurationRead'
import AccountMappingEntries from './AccountMappingEntries.vue'
import AccountMappingHistory from './AccountMappingHistory.vue'

const props = defineProps<{ scopeKey: string; refreshVersion: number; locked: boolean }>()
const directoryRead = reactive(new ConfigurationRead<MappingDirectory>(mappingError))
const draftRead = reactive(new ConfigurationRead<MappingDraft>(mappingError)), stateRead = reactive(new ConfigurationRead<MappingCurrent>(mappingError))
const categoriesRead = reactive(new ConfigurationRead<ExpenseCategories>(mappingError))
const publicationRead = reactive(new ConfigurationRead<{ current: MappingCurrent; draft: MappingDraft; categories: ExpenseCategories }>(mappingError))
const filters = ref<MappingScope>({ legalEntityId: '', currency: '' }), appliedFilters = ref<Partial<MappingScope>>({})
const directory = ref<MappingSummary[]>([]), nextKey = ref<string | null>(null), edit = ref<MappingEdit | null>(null)
const saving = ref(false), denied = ref(false), discard = ref(false), history = ref<'versions' | 'activations' | null>(null), historyRefresh = ref(0)
const error = ref(''), notice = ref(''), publishComment = ref(''), publishConfirmed = ref(false)
const busy = computed(() => props.locked || saving.value || publicationRead.loading)
const blocked = computed(() => busy.value || denied.value)
const stale = computed(() => !!edit.value?.baseline && !!draftRead.value && edit.value.baseline.revision !== draftRead.value.revision)
const current = computed(() => stateRead.value), review = computed(() => publicationRead.value)
const editorLocked = computed(() => blocked.value || draftRead.loading || stale.value || !!review.value)
const selectedScope = computed(() => edit.value?.baseline?.definition ?? null)
let active = true, epoch = 0
function cancelPublication() { publicationRead.clear(); publishComment.value = ''; publishConfirmed.value = false }
function stopReads() { directoryRead.clear(); draftRead.clear(); stateRead.clear(); categoriesRead.clear(); cancelPublication() }
function guardRead(status: number) {
  if (![401, 403].includes(status)) return
  denied.value = true; stopReads(); edit.value = null; directory.value = []; nextKey.value = null; history.value = null
  error.value = '当前会话无法读取科目配置，请恢复原账号或重新核对权限。'
}
async function loadDirectory(more = false) {
  if (!active || denied.value || directoryRead.loading) return
  const cursor = more ? nextKey.value ?? undefined : undefined
  if (!more) { directory.value = []; nextKey.value = null }
  const value = await directoryRead.load(signal => api.accountMappings(appliedFilters.value, cursor, signal))
  if (!value) { guardRead(directoryRead.status); return }
  directory.value = more ? [...directory.value, ...value.items] : value.items; nextKey.value = value.nextAfterKey
}
async function filterDirectory() {
  if (blocked.value) return
  if (filters.value.legalEntityId && !/^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(filters.value.legalEntityId)
    || filters.value.currency && !/^[A-Z]{3}$/.test(filters.value.currency)) { error.value = '请输入完整法人编号和大写三位币种，或留空查询全部。'; return }
  directoryRead.clear(); appliedFilters.value = { ...filters.value }; error.value = ''; await loadDirectory()
}
/** 刷新事实保留本地原基线；全局恢复原请求成功后再读取真实保存版本。 */
async function load() {
  if (!active || !props.scopeKey || saving.value) return
  cancelPublication(); const identity = epoch
  const [categories] = await Promise.all([categoriesRead.load(signal => api.expenseCategories(signal)), loadDirectory()])
  if (!active || epoch !== identity) return
  if (!categories) { guardRead(categoriesRead.status); if (denied.value) return }
  const editing = edit.value
  if (editing?.baseline || editing?.key && !mappingDrafts.get(props.scopeKey, '')) await selectMapping(editing.key, true)
}
/** 原请求确认后的刷新只读，不能被尚未释放的全局编辑锁阻止。 */
async function selectMapping(key: string, refresh = false) {
  if (denied.value || saving.value || !refresh && (busy.value || draftRead.loading)) return
  cancelPublication(); stateRead.clear(); history.value = null; discard.value = false; error.value = ''
  if (!refresh) edit.value = null
  const draft = await draftRead.load(signal => api.accountMappingDraft(key, signal))
  if (!draft) { guardRead(draftRead.status); return }
  edit.value = mappingDrafts.get(props.scopeKey, key) ?? { key, baseline: copyMapping(draft), definition: copyMapping(draft.definition), comment: '' }
  const value = await stateRead.load(signal => api.accountMappingCurrent(draft.definition, signal))
  if (!value) guardRead(stateRead.status)
}
function newMapping() {
  if (blocked.value) return
  draftRead.clear(); stateRead.clear(); cancelPublication(); history.value = null; discard.value = false; error.value = ''; notice.value = ''
  edit.value = mappingDrafts.get(props.scopeKey, '') ?? { key: '', baseline: null, definition: { name: '', legalEntityId: '', currency: '', entries: [] }, comment: '' }
}
function discardChanges() {
  if (blocked.value || !edit.value) return
  mappingDrafts.discard(props.scopeKey, edit.value.baseline ? edit.value.key : '')
  const draft = draftRead.value
  edit.value = draft ? { key: draft.key, baseline: copyMapping(draft), definition: copyMapping(draft.definition), comment: '' } : null
  discard.value = false; cancelPublication(); error.value = ''; notice.value = '已放弃本地修改，采用刚读取的保存版本。'
}
function writeFailure(cause: unknown) { error.value = mappingError(cause); guardRead((cause as { status?: number }).status ?? 0); cancelPublication() }
async function saveDraft() {
  if (editorLocked.value || !edit.value || !edit.value.comment.trim()) return
  const editing = edit.value
  if (!/^[a-z][a-z0-9-]{0,63}$/.test(editing.key)) { error.value = '配置标识应以小写字母开头，仅含小写字母、数字和连字符，最多 64 字。'; return }
  try { readMappingDefinition(editing.definition) } catch { error.value = '请核对名称、法人、币种及每一页的科目；同用途与选择范围不能重复。'; return }
  const input = mappingInput(editing), scope = props.scopeKey, identity = epoch
  saving.value = true; cancelPublication(); error.value = ''; notice.value = ''
  try {
    const result = await api.saveAccountMappingDraft(editing.key, input)
    mappingDrafts.acknowledge(scope, '/admin/account-mappings/' + encodeURIComponent(editing.key) + '/draft', JSON.stringify(input))
    if (!active || epoch !== identity) return
    edit.value = { key: result.key, baseline: copyMapping(result), definition: copyMapping(result.definition), comment: '' }; draftRead.value = result
    historyRefresh.value++; notice.value = '草稿修订已保存，当前生效科目保持原版本。'
  } catch (cause) { if (active && epoch === identity) writeFailure(cause) }
  finally { if (active && epoch === identity) { saving.value = false; await load() } }
}
/** 同时读取三个来源，再确认类别修订一致；发布仍由服务端原子比较三个版本。 */
async function preparePublication() {
  if (blocked.value || draftRead.loading || !edit.value?.baseline) return
  const editing = edit.value
  error.value = ''; notice.value = ''; publishComment.value = ''; publishConfirmed.value = false
  const value = await publicationRead.load(async signal => {
    const [current, draft, categories] = await Promise.all([api.accountMappingCurrent(editing.baseline!.definition, signal), api.accountMappingDraft(editing.key, signal), api.expenseCategories(signal)])
    return { current, draft, categories }
  })
  if (!value) { guardRead(publicationRead.status); return }
  stateRead.value = value.current; draftRead.value = value.draft; categoriesRead.value = value.categories
  if (!sameMappingDraft(editing, value.draft)) { cancelPublication(); error.value = '草稿有未保存内容或修订已变化，请保存或核对新版本后再发布。'; return }
  if (value.categories.version !== value.current.categoryRevision) { cancelPublication(); error.value = '类别目录刚刚变化，请重新核对发布版本。'; return }
  if (value.draft.publishedDraftRevision === value.draft.revision) { cancelPublication(); error.value = '此草稿修订已发布，保存新的修改后才能再次发布。'; return }
  const enabled = new Set(value.categories.categories.filter(item => item.active).map(item => item.code))
  if (!value.draft.definition.entries.length || value.draft.definition.entries.some(entry => entry.key.role === 'EXPENSE' && !enabled.has(entry.key.selector))) {
    cancelPublication(); error.value = '发布至少需要一项科目，费用用途必须对应启用的费用类别。'
  }
}
async function publish() {
  const value = review.value
  if (blocked.value || !value || !publishConfirmed.value || !publishComment.value.trim() || !edit.value || !sameMappingDraft(edit.value, value.draft)) return
  const input = { expectedDraftRevision: value.draft.revision, expectedCategoryRevision: value.current.categoryRevision, expectedActiveRevision: value.current.activeRevision, comment: publishComment.value.trim() }
  const identity = epoch; saving.value = true; error.value = ''; notice.value = ''
  try {
    await api.publishAccountMapping(value.draft.key, input)
    if (!active || epoch !== identity) return
    historyRefresh.value++; cancelPublication(); notice.value = '新科目版本已生效；已登记的凭证继续使用原版本。'
  } catch (cause) { if (active && epoch === identity) writeFailure(cause) }
  finally { if (active && epoch === identity) { saving.value = false; await load() } }
}
watch(edit, value => { if (value) mappingDrafts.put(props.scopeKey, value); if (!saving.value) cancelPublication() }, { deep: true, flush: 'sync' })
watch(() => props.scopeKey, () => {
  epoch++; stopReads(); edit.value = null; directory.value = []; nextKey.value = null; filters.value = { legalEntityId: '', currency: '' }; appliedFilters.value = {}
  saving.value = false; denied.value = false; history.value = null; discard.value = false; error.value = ''; notice.value = ''
  if (props.scopeKey) void load()
}, { immediate: true, flush: 'sync' })
watch(() => props.refreshVersion, () => { historyRefresh.value++; void load() })
onUnmounted(() => { active = false; epoch++; stopReads() })
</script>
<template>
  <section class="mapping-manager" aria-label="科目映射配置">
    <header class="mapping-heading"><div><p class="mapping-eyebrow">财务配置 · ACCOUNT MAPPING</p><h1>科目映射</h1><p>按法人、币种管理完整科目版本，保留每次发布依据。</p></div><button type="button" class="secondary" :disabled="busy || directoryRead.loading" @click="load()">刷新配置</button></header>
    <p v-if="error" class="mapping-error" role="alert">{{ error }}</p><p v-if="notice" class="mapping-notice" role="status">{{ notice }}</p>
    <template v-if="!denied">
      <form class="mapping-filters" @submit.prevent="filterDirectory"><label>法人编号<input v-model.trim="filters.legalEntityId" placeholder="留空查询全部法人" :disabled="blocked" autocomplete="off" /></label><label>币种<input v-model.trim="filters.currency" placeholder="例如 CNY" maxlength="3" :disabled="blocked" /></label><button type="submit" class="secondary" :disabled="blocked">筛选</button><button type="button" class="primary" :disabled="blocked" @click="newMapping">新建配置</button></form>
      <p v-if="directoryRead.loading" role="status">正在读取配置目录…</p><p v-if="directoryRead.error" class="mapping-error" role="alert">{{ directoryRead.error }}</p>
      <div class="mapping-layout"><aside aria-label="配置目录"><p v-if="!directory.length && !directoryRead.loading && !directoryRead.error" class="mapping-help">当前范围暂无配置，可新建草稿。</p><button v-for="item in directory" :key="item.id" type="button" class="mapping-item" :class="{ selected: edit?.key === item.key }" :aria-pressed="edit?.key === item.key" :disabled="blocked || draftRead.loading" @click="selectMapping(item.key)"><strong>{{ item.name }}</strong><code>{{ item.key }} · {{ item.currency }}</code><small>法人 {{ item.legalEntityId }}</small><span>草稿 {{ item.revision }} · {{ item.publishedVersion ? '已发布 v' + item.publishedVersion : '未发布' }}</span></button><button v-if="nextKey" type="button" class="secondary" :disabled="blocked || directoryRead.loading" @click="loadDirectory(true)">更多配置</button></aside>
      <div class="mapping-editor">
        <p v-if="draftRead.loading" role="status">正在读取草稿…</p><p v-if="draftRead.error" class="mapping-error" role="alert">{{ draftRead.error }}</p>
        <p v-if="!edit && !draftRead.loading" class="mapping-empty">选择一份配置查看科目与历史，或新建法人、币种范围的草稿。</p>
        <template v-if="edit">
          <div class="mapping-subheading"><h2>{{ edit.baseline ? edit.baseline.definition.name : '新建科目配置' }}</h2><span>{{ edit.baseline ? '草稿修订 ' + edit.baseline.revision : '尚未保存' }}</span></div>
          <p v-if="stateRead.loading" role="status">正在读取本范围生效版本…</p><p v-if="stateRead.error" class="mapping-error" role="alert">{{ stateRead.error }}</p>
          <div v-if="current" class="mapping-current"><span>当前生效</span><strong>{{ current.activeMapping ? current.activeMapping.definition.name + ' v' + current.activeMapping.version : '尚未发布平台科目版本' }}</strong><small>生效修订 {{ current.activeRevision }} · 类别修订 {{ current.categoryRevision }}</small></div>
          <p v-if="stale" class="mapping-conflict" role="alert">草稿已更新到修订 {{ draftRead.value?.revision }}。本地修改仍以修订 {{ edit.baseline?.revision }} 为基线，不能直接覆盖。</p>
          <form @submit.prevent="saveDraft"><fieldset :disabled="editorLocked"><legend class="sr-only">编辑科目配置</legend>
            <div class="mapping-fields"><label>配置标识<input v-model.trim="edit.key" required maxlength="64" pattern="[a-z][a-z0-9-]{0,63}" :readonly="!!edit.baseline" /><small>保存后不变，小写字母、数字和连字符</small></label><label>配置名称<input v-model.trim="edit.definition.name" required maxlength="128" /></label><label>法人编号<input v-model.trim="edit.definition.legalEntityId" required :readonly="!!edit.baseline" autocomplete="off" /><small>填写企业财务主数据中的完整编号</small></label><label>币种<input v-model.trim="edit.definition.currency" required maxlength="3" pattern="[A-Z]{3}" placeholder="例如 CNY" :readonly="!!edit.baseline" /><small>法人和币种保存后固定</small></label></div>
            <p v-if="categoriesRead.error" class="mapping-error" role="alert">{{ categoriesRead.error }}</p>
            <AccountMappingEntries :definition="edit.definition" :disabled="editorLocked" :categories="categoriesRead.value?.categories" />
            <label class="mapping-reason">草稿修改理由<textarea v-model="edit.comment" required maxlength="2000" rows="2" /></label><button type="submit" class="primary" :disabled="!edit.comment.trim()">保存草稿修订</button>
          </fieldset></form>
          <div class="mapping-toolbar"><button type="button" class="quiet" :disabled="blocked || draftRead.loading" @click="discard = true">放弃本地修改</button><button type="button" class="primary" :disabled="blocked || draftRead.loading || !edit.baseline || stale || !!review" @click="preparePublication">核对并发布</button></div>
          <p v-if="publicationRead.error" class="mapping-error" role="alert">{{ publicationRead.error }}</p>
          <section v-if="review" class="mapping-review" aria-label="科目发布确认"><h3>确认发布并切换本范围科目</h3><p>{{ review.draft.definition.name }}（{{ review.draft.key }}）· {{ review.draft.definition.currency }}</p><p class="mapping-help">法人 {{ review.draft.definition.legalEntityId }}</p><p>{{ review.current.activeMapping ? '将替换 ' + review.current.activeMapping.definition.name + ' v' + review.current.activeMapping.version : '本范围首次启用平台科目版本' }}</p><dl><div><dt>草稿修订</dt><dd>{{ review.draft.revision }}</dd></div><div><dt>类别修订</dt><dd>{{ review.current.categoryRevision }}</dd></div><div><dt>生效修订</dt><dd>{{ review.current.activeRevision }}</dd></div></dl><AccountMappingEntries :definition="review.draft.definition" :categories="review.categories.categories" readonly /><form @submit.prevent="publish"><fieldset :disabled="blocked"><legend class="sr-only">确认科目发布</legend><label class="mapping-reason">发布理由<textarea v-model="publishComment" required maxlength="2000" rows="2" /></label><label class="mapping-check"><input v-model="publishConfirmed" type="checkbox" required />已核对全部科目和上述版本，确认替换本法人、币种的完整映射</label><p class="mapping-help">会计服务仍校验科目与期间。已登记凭证保留原版本；准备中的版本变化需要重新准备。</p><div class="mapping-toolbar"><button type="button" class="secondary" @click="cancelPublication">取消发布</button><button type="submit" class="primary" :disabled="!publishConfirmed || !publishComment.trim()">确认发布</button></div></fieldset></form></section>
          <div v-if="edit.baseline" class="mapping-toolbar"><button type="button" class="secondary" :aria-pressed="history === 'versions'" :disabled="blocked" @click="history = history === 'versions' ? null : 'versions'">发布与草稿历史</button><button type="button" class="secondary" :aria-pressed="history === 'activations'" :disabled="blocked" @click="history = history === 'activations' ? null : 'activations'">本范围生效记录</button></div>
          <AccountMappingHistory v-if="history && selectedScope && edit.baseline" :scope-key="scopeKey" :scope="selectedScope" :mode="history" :mapping-key="edit.key" :mapping-id="edit.baseline.id" :draft-revision="draftRead.value?.revision ?? edit.baseline.revision" :refresh-version="historyRefresh" @unavailable="guardRead" />
          <div v-if="discard" class="mapping-conflict" role="alert"><p>确认放弃本地未保存修改？将采用刚读取的草稿，保存历史继续保留。</p><button type="button" class="secondary" :disabled="blocked" @click="discard = false">保留修改</button> <button type="button" class="secondary" :disabled="blocked" @click="discardChanges">确认放弃</button></div>
        </template>
      </div></div>
    </template>
  </section>
</template>
<style scoped>
.mapping-manager{padding:28px;max-width:1380px;margin:auto}.mapping-heading,.mapping-subheading{display:flex;justify-content:space-between;align-items:center;gap:16px}.mapping-heading h1{font-size:28px;letter-spacing:-.6px;margin:5px 0 8px}.mapping-heading p{font-size:13px;color:var(--muted);margin:0}.mapping-heading .mapping-eyebrow{font-size:10px;letter-spacing:1.6px;color:#087a76}.mapping-filters{display:flex;align-items:end;gap:12px;padding:22px 0;border-bottom:1px solid var(--line)}.mapping-filters label:first-child{flex:1}.mapping-filters label:nth-child(2){max-width:110px}.mapping-filters label,.mapping-fields label,.mapping-reason{display:grid;gap:6px;font-size:12px}input,textarea{width:100%;min-width:0}.mapping-layout{display:grid;grid-template-columns:245px minmax(0,1fr);gap:26px;margin-top:22px}.mapping-item{display:grid;gap:5px;width:100%;text-align:left;padding:14px;margin-bottom:8px;border:1px solid var(--line);border-radius:10px;background:white;overflow-wrap:anywhere}.mapping-item.selected{border-color:#087a76;background:#e5f5f2}.mapping-item code{font-size:11px}.mapping-item small,.mapping-item span,.mapping-subheading>span,.mapping-fields small{font-size:11px;color:var(--muted)}.mapping-subheading{margin-bottom:16px}.mapping-subheading h2{font-size:19px;margin:0}.mapping-fields{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1fr);gap:16px}fieldset{padding:0;border:0;min-width:0}.mapping-current{display:grid;gap:6px;background:var(--paper);padding:15px;border-left:3px solid #087a76;border-radius:4px;margin-bottom:20px;font-size:13px}.mapping-current>span,.mapping-current small{font-size:11px;color:var(--muted)}.mapping-toolbar{display:flex;align-items:center;gap:10px;flex-wrap:wrap;margin:17px 0}.mapping-reason{margin:18px 0 12px}.mapping-check{display:flex;gap:9px;align-items:start;font-size:12px;line-height:1.65}.mapping-check input{width:auto;margin-top:4px}.mapping-help,.mapping-empty{font-size:12px;line-height:1.7;color:var(--muted);overflow-wrap:anywhere}.mapping-empty{padding:45px 25px;background:var(--paper);border:1px dashed var(--line);border-radius:12px}.mapping-review{padding:20px;background:#f0f8f5;border:1px solid #b5dcd3;border-radius:12px;font-size:13px}.mapping-review h3{margin-top:0}.mapping-review dl{display:grid;grid-template-columns:repeat(3,1fr);gap:10px}.mapping-review dl>div{padding:12px;background:white;border-radius:6px}.mapping-review dt{font-size:11px;color:var(--muted)}.mapping-review dd{margin:5px 0 0;font-size:22px}.mapping-error,.mapping-conflict{padding:12px;background:#fff5ef;color:#8e452b;border-radius:8px;font-size:13px;line-height:1.6}.mapping-notice{padding:12px;background:#e5f5f2;color:#087a76;border-radius:8px;font-size:13px}button:focus-visible,input:focus-visible,textarea:focus-visible{outline:2px solid #087a76;outline-offset:3px}@media(max-width:900px){.mapping-layout{grid-template-columns:200px minmax(0,1fr)}}@media(max-width:650px){.mapping-manager{padding:18px}.mapping-heading{align-items:start}.mapping-layout{grid-template-columns:minmax(0,1fr)}.mapping-filters{flex-wrap:wrap}.mapping-filters label:first-child{flex-basis:100%}.mapping-fields{grid-template-columns:minmax(0,1fr)}.mapping-review{padding:14px}.mapping-heading h1{font-size:24px}.mapping-item{padding:12px}}

.mapping-heading{height:auto;min-height:0;padding:0;margin:0;border:0;position:static;background:transparent}.mapping-fields{align-items:start}.mapping-fields label{align-content:start}.mapping-editor{padding:22px;background:#fff;border:1px solid var(--line);border-radius:12px}.mapping-manager input,.mapping-manager textarea{font:inherit;color:var(--ink);background:#fff;border:1px solid #cfdbd9;border-radius:7px;padding:10px 11px;min-height:40px;box-sizing:border-box}.mapping-manager input[readonly]{background:#f6f8f7;color:#52666e}.mapping-check input{min-height:0;padding:0;margin-top:4px;width:16px;height:16px}.mapping-manager .sr-only{position:absolute;width:1px;height:1px;padding:0;margin:-1px;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap;border:0}@media(max-width:650px){.mapping-editor{padding:15px}.mapping-heading{gap:8px}}
</style>
