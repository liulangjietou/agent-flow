<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import { api } from '../api'
import { priorControlModes, readPriorControl, type PriorControlMode } from '../expensePriorControl'
import { expenseUnits, configurationError, readPolicyDefinition, type ExpenseConfigurationCurrent, type ExpensePolicyDraft, type PolicyDirectory, type PolicySummary, type PolicyPublishInput } from '../expenseConfiguration'
import { categoryInput, policyInput, categoriesChanged, configurationDrafts, copyConfiguration, samePolicyDraft, type CategoryEdit, type PolicyEdit } from '../expenseConfigurationDrafts'
import { ConfigurationRead } from '../expenseConfigurationRead'
import ExpensePolicyRules from './ExpensePolicyRules.vue'
import ExpensePolicySummary from './ExpensePolicySummary.vue'
import ExpenseConfigurationHistory from './ExpenseConfigurationHistory.vue'

const props = defineProps<{ scopeKey: string; refreshVersion: number; locked: boolean }>()
const stateRead = reactive(new ConfigurationRead<ExpenseConfigurationCurrent>())
const directoryRead = reactive(new ConfigurationRead<PolicyDirectory>())
const draftRead = reactive(new ConfigurationRead<ExpensePolicyDraft>())
const publicationRead = reactive(new ConfigurationRead<{ current: ExpenseConfigurationCurrent; draft: ExpensePolicyDraft }>())
const section = ref<'categories' | 'policies' | 'activations'>('categories')
const categories = ref<CategoryEdit | null>(null), policy = ref<PolicyEdit | null>(null)
const directory = ref<PolicySummary[]>([]), nextKey = ref<string | null>(null), selectedKey = ref<string | null>(null)
const saving = ref(false), denied = ref(false), error = ref(''), notice = ref(''), discard = ref<'categories' | 'policy' | null>(null)
const publishComment = ref(''), publishConfirmed = ref(false), history = ref(false), historyRefresh = ref(0)
const current = computed(() => stateRead.value)
const busy = computed(() => props.locked || saving.value || stateRead.loading || publicationRead.loading)
const blocked = computed(() => busy.value || denied.value || !current.value)
const categoryStale = computed(() => !!categories.value && !!current.value && categories.value.baseline.version !== current.value.categories.version)
const policyStale = computed(() => !!policy.value?.baseline && !!draftRead.value && policy.value.baseline.revision !== draftRead.value.revision)
const categoryDirty = computed(() => !!categories.value && categoriesChanged(categories.value))
const publishReview = computed(() => publicationRead.value)
let active = true, epoch = 0
function cancelPublication() { publicationRead.clear(); publishComment.value = ''; publishConfirmed.value = false }
function stopReads() { stateRead.clear(); directoryRead.clear(); draftRead.clear(); cancelPublication() }
function guardRead(status: number) {
  if (![401, 403].includes(status)) return
  denied.value = true; stopReads(); categories.value = null; policy.value = null; directory.value = []; nextKey.value = null; history.value = false
  error.value = '当前会话无法读取财务配置，请恢复原账号或重新核对权限。'
}
/** 刷新取得新事实，未保存文本仍保留旧基线；不自动把旧编辑套到新版本。 */
async function load() {
  if (!active || !props.scopeKey || saving.value) return
  cancelPublication(); const currentEpoch = epoch
  const value = await stateRead.load(signal => api.expenseConfiguration(signal))
  if (!active || currentEpoch !== epoch) return
  if (!value) { guardRead(stateRead.status); return }
  denied.value = false
  categories.value = configurationDrafts.category(props.scopeKey) ?? { baseline: copyConfiguration(value.categories), categories: copyConfiguration(value.categories.categories), comment: '' }
  const editing = policy.value
  // 新建请求已由全局原键恢复确认时，内存草稿被清理，页面应读取真实保存修订。
  if (editing?.baseline || editing?.key && !configurationDrafts.policy(props.scopeKey, '')) await selectPolicy(editing.key, true)
  await loadDirectory()
}
async function loadDirectory(more = false) {
  if (directoryRead.loading || denied.value || !current.value) return
  const cursor = more ? nextKey.value ?? undefined : undefined, currentEpoch = epoch
  if (!more) { directory.value = []; nextKey.value = null }
  const value = await directoryRead.load(signal => api.expensePolicies(cursor, signal))
  if (!active || currentEpoch !== epoch) return
  if (!value) { guardRead(directoryRead.status); return }
  directory.value = more ? [...directory.value, ...value.items] : value.items; nextKey.value = value.nextAfterKey
}
async function selectPolicy(key: string, refreshing = false) {
  if (!refreshing && blocked.value || denied.value || saving.value) return
  cancelPublication(); discard.value = null; selectedKey.value = key; history.value = false; policy.value = null
  const value = await draftRead.load(signal => api.expensePolicyDraft(key, signal))
  if (!value) { guardRead(draftRead.status); return }
  policy.value = configurationDrafts.policy(props.scopeKey, key) ?? { key, baseline: copyConfiguration(value), definition: copyConfiguration(value.definition), comment: '' }
}
function newPolicy() {
  if (blocked.value) return
  cancelPublication(); draftRead.clear(); selectedKey.value = ''; history.value = false; discard.value = null
  policy.value = configurationDrafts.policy(props.scopeKey, '') ?? { key: '', baseline: null, definition: { name: '', rules: [] }, comment: '' }
}
function discardChanges() {
  if (blocked.value || !discard.value) return
  if (discard.value === 'categories' && current.value) {
    configurationDrafts.discardCategories(props.scopeKey)
    categories.value = { baseline: copyConfiguration(current.value.categories), categories: copyConfiguration(current.value.categories.categories), comment: '' }
  } else if (discard.value === 'policy' && policy.value) {
    configurationDrafts.discardPolicy(props.scopeKey, policy.value.baseline ? policy.value.key : '')
    policy.value = draftRead.value ? { key: draftRead.value.key, baseline: copyConfiguration(draftRead.value), definition: copyConfiguration(draftRead.value.definition), comment: '' } : null
  }
  discard.value = null; cancelPublication(); error.value = ''; notice.value = '已放弃本地修改，使用刚读取的版本重新编辑。'
}
function addCategory() { if (!blocked.value && !categoryStale.value && categories.value && categories.value.categories.length < 2000) categories.value.categories.push({ code: '', name: '', units: [], active: true }) }
function changePriorMode(index: number, event: Event) {
  if (blocked.value || categoryStale.value || !categories.value) return
  const item = categories.value.categories[index], mode = (event.target as HTMLSelectElement).value
  if (!item) return
  if (mode === 'LEGACY') delete item.priorControl
  else if (Object.prototype.hasOwnProperty.call(priorControlModes, mode)) item.priorControl = { mode: mode as PriorControlMode, ...(mode === 'TOLERANCE' ? { toleranceFraction: null } : {}) }
}
function changeTolerance(index: number, event: Event) {
  if (blocked.value || categoryStale.value || !categories.value) return
  const control = categories.value.categories[index]?.priorControl, raw = (event.target as HTMLInputElement).value
  if (control?.mode === 'TOLERANCE') control.toleranceFraction = raw.trim() ? (/^(?:0|[1-9][0-9]*)(?:\.[0-9]{1,4})?$/.test(raw) ? Number(raw.replace('.', '').padEnd((raw.split('.')[0]?.length ?? 0) + 4, '0')) / 1_000_000 : Number(raw) / 100) : null
}
function removeCategory(index: number) {
  if (!blocked.value && categories.value && index >= categories.value.baseline.categories.length) categories.value.categories.splice(index, 1)
}
function handleWriteFailure(cause: unknown) { error.value = configurationError(cause); guardRead((cause as { status?: number }).status ?? 0); cancelPublication() }
async function saveCategories() {
  if (blocked.value || categoryStale.value || !categories.value || !categories.value.comment.trim()) return
  try { categories.value.categories.forEach(item => { if (item.priorControl) readPriorControl(item.priorControl) }) }
  catch { error.value = '请明确填写 0–100% 的容差，最多四位小数；其他模式不填写比例。'; return }
  const input = categoryInput(categories.value), scope = props.scopeKey, identity = epoch
  saving.value = true; error.value = ''; notice.value = ''
  try {
    await api.saveExpenseCategories(input)
    configurationDrafts.acknowledge(scope, '/admin/expense-categories', JSON.stringify(input))
    if (!active || epoch !== identity) return
    categories.value = null; historyRefresh.value++; notice.value = '类别新修订已保存。启用平台制度后，旧预检需重新运行。'
  } catch (cause) { if (active && epoch === identity) handleWriteFailure(cause) }
  finally { if (active && epoch === identity) { saving.value = false; await load() } }
}
async function savePolicy() {
  if (blocked.value || draftRead.loading || policyStale.value || !policy.value || !policy.value.comment.trim()) return
  const edit = policy.value
  if (!/^[a-z][a-z0-9-]{0,63}$/.test(edit.key)) { error.value = '制度标识应以小写字母开头，仅使用小写字母、数字和连字符，最多 64 字。'; return }
  try { readPolicyDefinition(edit.definition) } catch { error.value = '请核对制度名称、规则标识、匹配条件和约束；相同匹配条件不能重复。'; return }
  const input = policyInput(edit), scope = props.scopeKey, identity = epoch, path = '/admin/expense-policies/' + encodeURIComponent(edit.key) + '/draft'
  saving.value = true; error.value = ''; notice.value = ''; cancelPublication()
  try {
    const result = await api.saveExpensePolicyDraft(edit.key, input)
    configurationDrafts.acknowledge(scope, path, JSON.stringify(input))
    if (!edit.baseline) configurationDrafts.discardPolicy(scope, '')
    if (!active || epoch !== identity) return
    policy.value = { key: result.key, baseline: copyConfiguration(result), definition: copyConfiguration(result.definition), comment: '' }
    selectedKey.value = result.key; draftRead.value = result; historyRefresh.value++; notice.value = '草稿已保存；当前生效制度未改变。'
  } catch (cause) { if (active && epoch === identity) handleWriteFailure(cause) }
  finally { if (active && epoch === identity) { saving.value = false; await loadDirectory() } }
}
/** 发布确认重新读取两个来源，展示真正将发布的保存版本和将被替换的制度。 */
async function preparePublication() {
  if (blocked.value || draftRead.loading || !policy.value?.baseline) return
  const edit = policy.value, key = edit.key
  error.value = ''; notice.value = ''; publishComment.value = ''; publishConfirmed.value = false
  const value = await publicationRead.load(async signal => {
    const [current, draft] = await Promise.all([api.expenseConfiguration(signal), api.expensePolicyDraft(key, signal)])
    return { current, draft }
  })
  if (!value) { guardRead(publicationRead.status); return }
  stateRead.value = value.current; draftRead.value = value.draft
  if (!samePolicyDraft(edit, value.draft)) { cancelPublication(); error.value = '草稿有未保存内容或版本已变化。请保存或核对最新草稿后再发布。'; return }
  if (value.draft.publishedDraftRevision === value.draft.revision) { cancelPublication(); error.value = '此草稿修订已发布；修改并保存新修订后才能再次发布。'; return }
  const enabled = value.current.categories.categories.filter(item => item.active).map(item => item.code)
  if (!value.draft.definition.rules.length || !enabled.length || value.draft.definition.rules.some(rule => rule.match.categoryCodes.some(code => !enabled.includes(code)))) {
    cancelPublication(); error.value = '请先配置启用类别及完整规则，规则不能引用未启用的类别。'
  }
}
async function publish() {
  const review = publishReview.value
  if (blocked.value || !review || !publishConfirmed.value || !publishComment.value.trim() || !policy.value || !samePolicyDraft(policy.value, review.draft)) return
  const input: PolicyPublishInput = { expectedDraftRevision: review.draft.revision, expectedCategoryRevision: review.current.categories.version,
    expectedActiveRevision: review.current.activeRevision, comment: publishComment.value.trim() }
  const identity = epoch; saving.value = true; error.value = ''; notice.value = ''
  try {
    await api.publishExpensePolicy(review.draft.key, input)
    if (!active || epoch !== identity) return
    historyRefresh.value++; cancelPublication(); notice.value = '新制度已发布并生效。旧提交轮次保留原版本，新提交需重新预检。'
  } catch (cause) { if (active && epoch === identity) handleWriteFailure(cause) }
  finally { if (active && epoch === identity) { saving.value = false; await load() } }
}
watch(categories, value => { if (value) configurationDrafts.putCategories(props.scopeKey, value); if (!saving.value) cancelPublication() }, { deep: true, flush: 'sync' })
watch(policy, value => {
  if (value) configurationDrafts.putPolicy(props.scopeKey, value)
  if (!saving.value) cancelPublication()
}, { deep: true, flush: 'sync' })
watch(() => props.scopeKey, () => {
  epoch++; stopReads(); categories.value = null; policy.value = null; selectedKey.value = null; directory.value = []; nextKey.value = null
  saving.value = false; denied.value = false; error.value = ''; notice.value = ''; history.value = false; discard.value = null
  if (props.scopeKey) void load()
}, { immediate: true, flush: 'sync' })
watch(() => props.refreshVersion, () => { historyRefresh.value++; void load() })
onUnmounted(() => { active = false; epoch++; stopReads() })
</script>

<template>
  <section class="expense-configuration content" aria-label="费用制度管理">
    <div class="config-heading"><div><p class="eyebrow">费用配置</p><h2>费用类别与制度</h2><p>保存草稿后核对并发布；历史版本保留原始内容和修改理由。</p></div><button class="secondary" type="button" :disabled="busy" @click="load">重新读取</button></div>
    <p v-if="error" class="config-error" role="alert">{{ error }}</p><p v-if="stateRead.error" class="config-error" role="alert">{{ stateRead.error }}</p>
    <p v-if="notice" class="config-notice" role="status">{{ notice }}</p><p v-if="stateRead.loading" role="status">正在读取当前配置…</p>
    <template v-if="current && !denied">
      <div class="config-current" aria-label="当前生效配置"><span>类别修订 <b>{{ current.categories.version }}</b></span><span>生效修订 <b>{{ current.activeRevision }}</b></span><span v-if="current.activePolicy">{{ current.activePolicy.definition.name }} · {{ current.activePolicy.key }} v{{ current.activePolicy.version }}</span><span v-else>平台制度尚未启用，仍使用原企业制度源</span></div>
      <nav class="config-tabs" aria-label="费用配置页面"><button v-for="item in [{ id: 'categories', label: '费用类别' }, { id: 'policies', label: '费用制度' }, { id: 'activations', label: '生效记录' }]" :key="item.id" type="button" :class="{ active: section === item.id }" :aria-current="section === item.id ? 'page' : undefined" :disabled="saving || publicationRead.loading" @click="section = item.id as typeof section; history = false; cancelPublication()">{{ item.label }}</button></nav>
      <template v-if="section === 'categories' && categories">
        <div class="config-toolbar"><h3>类别目录 · 编辑基于修订 {{ categories.baseline.version }}</h3><button type="button" class="secondary" :disabled="blocked" @click="history = !history">{{ history ? '关闭类别历史' : '查看类别历史' }}</button></div>
        <ExpenseConfigurationHistory v-if="history" :scope-key="scopeKey" mode="categories" :refresh-version="historyRefresh" @unavailable="guardRead" />
        <p class="config-help">已有代码保持不变；不再使用的类别请停用。单位和启用状态以此目录与员工企业授权的交集为准。</p>
        <p v-if="categoryStale" class="config-conflict" role="alert">类别目录已更新到修订 {{ current.categories.version }}。本地仍基于 {{ categories.baseline.version }}，请核对后重新编辑。</p>
        <form @submit.prevent="saveCategories">
          <fieldset :disabled="blocked || categoryStale"><legend class="sr-only">编辑类别目录</legend>
            <div v-for="(item, index) in categories.categories" :key="index" class="category-row">
              <label>类别代码<input v-model.trim="item.code" required maxlength="64" :readonly="index < categories.baseline.categories.length" /></label>
              <label>类别名称<input v-model.trim="item.name" required maxlength="128" /></label>
              <fieldset class="unit-options"><legend>允许单位（至少一项）</legend><label v-for="(label, code) in expenseUnits" :key="code"><input v-model="item.units" type="checkbox" :value="code" />{{ label }}</label></fieldset>
              <div class="prior-category"><label>事前额度控制<select :value="item.priorControl?.mode ?? 'LEGACY'" @change="changePriorMode(index, $event)"><option value="LEGACY">保留历史硬上限</option><option v-for="(label, mode) in priorControlModes" :key="mode" :value="mode">{{ label }}</option></select></label>
                <label v-if="item.priorControl?.mode === 'TOLERANCE'">容差百分比（%）<input type="number" min="0" max="100" step="0.0001" required :value="item.priorControl.toleranceFraction == null ? '' : Number((item.priorControl.toleranceFraction * 100).toFixed(4))" @input="changeTolerance(index, $event)" /></label>
                <small v-if="item.priorControl?.mode === 'TOLERANCE'">累计超过批准金额 ×（1＋容差）时，每行填写说明并追加独立审批。</small>
                <small v-else-if="item.priorControl?.mode === 'NONE'">不按批准金额阻断，仍记录全部占用和核销。</small>
                <small v-else>累计使用不得超过原批准的硬上限。</small>
              </div>
              <label class="config-check"><input v-model="item.active" type="checkbox" />启用</label><button v-if="index >= categories.baseline.categories.length" type="button" class="quiet" :aria-label="'移除新增类别 ' + (index + 1)" @click="removeCategory(index)">移除新增项</button>
            </div>
            <p v-if="!categories.categories.length" class="config-help">尚未配置类别。请按企业实际目录添加。</p>
            <button type="button" class="secondary" :disabled="categories.categories.length >= 2000" @click="addCategory">添加类别</button>
            <label class="config-reason">修改理由<textarea v-model="categories.comment" required maxlength="2000" rows="2" /></label>
            <button class="primary" type="submit" :disabled="!categoryDirty || !categories.comment.trim() || categories.categories.some(item => !item.units.length)">{{ saving ? '正在保存…' : '保存类别新修订' }}</button>
          </fieldset>
        </form>
        <button class="quiet" type="button" :disabled="blocked || !categoryDirty && !categoryStale" @click="discard = 'categories'">放弃本地修改并采用刚读取的目录</button>
      </template>
      <template v-else-if="section === 'policies'">
        <div class="config-toolbar"><h3>制度草稿</h3><button type="button" class="secondary" :disabled="blocked" @click="newPolicy">新建制度草稿</button></div>
        <p v-if="directoryRead.error" class="config-error" role="alert">{{ directoryRead.error }} <button type="button" :disabled="directoryRead.loading" @click="loadDirectory()">重新读取目录</button></p>
        <div class="policy-directory"><button v-for="item in directory" :key="item.key" type="button" :class="{ active: selectedKey === item.key }" :disabled="blocked" @click="selectPolicy(item.key)"><strong>{{ item.name }}</strong><span>{{ item.key }} · 草稿 {{ item.revision }} · {{ item.publishedVersion ? '已发布 v' + item.publishedVersion : '未发布' }}</span></button></div>
        <button v-if="nextKey" class="secondary" type="button" :disabled="blocked || directoryRead.loading" @click="loadDirectory(true)">更多制度</button>
        <p v-if="!directory.length && !directoryRead.loading && !directoryRead.error" class="config-help">尚无制度草稿。新建并保存草稿后才能发布。</p>
        <p v-if="draftRead.loading" role="status">正在读取制度草稿…</p><p v-if="draftRead.error" class="config-error" role="alert">{{ draftRead.error }}</p>
        <template v-if="policy">
          <div class="config-toolbar"><h3>{{ policy.baseline ? '编辑草稿 · 修订 ' + policy.baseline.revision : '新建制度草稿' }}</h3><button v-if="policy.baseline" type="button" class="secondary" :disabled="blocked" @click="history = !history">{{ history ? '关闭制度历史' : '查看制度历史' }}</button></div>
          <ExpenseConfigurationHistory v-if="history && policy.baseline" :scope-key="scopeKey" mode="policies" :policy-key="policy.key" :policy-id="policy.baseline.id" :draft-revision="draftRead.value?.revision ?? policy.baseline.revision" :refresh-version="historyRefresh" @unavailable="guardRead" />
          <p v-if="policyStale" class="config-conflict" role="alert">草稿已更新到修订 {{ draftRead.value?.revision }}。本地旧修订不能直接覆盖。</p>
          <form @submit.prevent="savePolicy"><fieldset :disabled="blocked || draftRead.loading || policyStale || !!publishReview"><legend class="sr-only">编辑费用制度</legend>
            <div class="config-fields"><label>制度标识<input v-model.trim="policy.key" required maxlength="64" pattern="[a-z][a-z0-9-]{0,63}" :readonly="!!policy.baseline" /><small>保存后不变；小写字母、数字和连字符</small></label><label>制度名称<input v-model.trim="policy.definition.name" required maxlength="128" /></label></div>
            <ExpensePolicyRules :definition="policy.definition" :disabled="blocked || draftRead.loading || policyStale || !!publishReview" />
            <label class="config-reason">草稿修改理由<textarea v-model="policy.comment" required maxlength="2000" rows="2" /></label>
            <button class="primary" type="submit" :disabled="!policy.comment.trim()">保存草稿修订</button>
          </fieldset></form>
          <div class="config-toolbar"><button type="button" class="quiet" :disabled="blocked" @click="discard = 'policy'">放弃本地修改并采用刚读取的草稿</button><button type="button" class="primary" :disabled="blocked || draftRead.loading || !policy.baseline || policyStale || !!publishReview" @click="preparePublication">核对并发布</button></div>
          <p v-if="publicationRead.error" class="config-error" role="alert">{{ publicationRead.error }}</p>
          <section v-if="publishReview" class="publish-review" aria-label="发布确认">
            <h3>确认发布并切换生效制度</h3>
            <p>即将发布 <strong>{{ publishReview.draft.definition.name }}</strong>（{{ publishReview.draft.key }}）。{{ publishReview.current.activePolicy ? '将替换当前 ' + publishReview.current.activePolicy.definition.name + ' v' + publishReview.current.activePolicy.version : '这是本租户首次启用平台制度' }}。</p>
            <dl class="publish-versions"><div><dt>草稿修订</dt><dd>{{ publishReview.draft.revision }}</dd></div><div><dt>类别修订</dt><dd>{{ publishReview.current.categories.version }}</dd></div><div><dt>当前生效修订</dt><dd>{{ publishReview.current.activeRevision }}</dd></div></dl>
            <details><summary>核对本次发布采用的已保存类别目录</summary><p v-for="item in publishReview.current.categories.categories" :key="item.code" class="config-help">{{ item.name }}（{{ item.code }}）· {{ item.active ? '启用' : '停用' }} · {{ item.units.map(unit => expenseUnits[unit]).join('、') }}</p></details>
            <ExpensePolicySummary :definition="publishReview.draft.definition" />
            <form @submit.prevent="publish"><fieldset :disabled="blocked"><legend class="sr-only">确认制度发布</legend><label class="config-reason">发布理由<textarea v-model="publishComment" required maxlength="2000" rows="2" /></label><label class="config-check"><input v-model="publishConfirmed" type="checkbox" required />已核对规则、类别和上述版本，确认发布后替换当前完整制度集</label><p class="config-help">新提交需重新预检，已提交轮次继续保留原制度。职级和城市等级等事实由企业源提供。</p><div class="config-toolbar"><button type="button" class="secondary" @click="cancelPublication">取消发布</button><button type="submit" class="primary" :disabled="!publishConfirmed || !publishComment.trim()">确认发布</button></div></fieldset></form>
          </section>
        </template>
      </template>
      <ExpenseConfigurationHistory v-else-if="section === 'activations'" :scope-key="scopeKey" mode="activations" :refresh-version="historyRefresh" @unavailable="guardRead" />
      <div v-if="discard" class="config-conflict" role="alert"><p>确认放弃本地未保存修改？将采用刚读取的版本，已保存历史不受影响。</p><button type="button" class="secondary" :disabled="blocked" @click="discard = null">保留修改</button> <button type="button" class="secondary" :disabled="blocked" @click="discardChanges">确认放弃并重新编辑</button></div>
    </template>
  </section>
</template>

<style scoped>
.prior-category{display:grid;gap:8px;grid-column:1/-1}.prior-category label{display:grid;gap:6px;font-size:12px;max-width:450px}.prior-category select{font:inherit;padding:9px;border:1px solid var(--line);border-radius:5px;background:var(--surface,#fff);color:inherit}.expense-configuration{max-width:1180px}.config-heading,.config-toolbar{display:flex;align-items:center;justify-content:space-between;gap:16px;flex-wrap:wrap}.config-heading{align-items:flex-start;margin-bottom:20px}.config-heading h2{font-size:24px;margin:4px 0}.config-heading p{color:var(--muted);font-size:12px}.eyebrow{letter-spacing:2px}.config-current{display:flex;gap:18px;flex-wrap:wrap;background:#edf7f3;border:1px solid #d2e5dc;padding:15px;border-radius:8px;font-size:12px}.config-tabs{display:flex;gap:8px;margin:20px 0;border-bottom:1px solid var(--line);padding-bottom:12px}.config-tabs button{padding:8px 16px;background:transparent;border:1px solid var(--line);border-radius:6px;font:inherit;font-size:13px}.config-tabs button.active{background:var(--deep);color:white;border-color:var(--deep)}h3{font-size:16px;margin:16px 0}.config-help,small{color:var(--muted);font-size:12px;line-height:1.8}.config-error,.config-conflict,.config-notice{padding:12px 14px;border-radius:7px;font-size:13px;overflow-wrap:anywhere}.config-error{background:#fff1ed;color:var(--red)}.config-conflict{background:#fff8e8;border:1px solid #ebd7a8}.config-notice{background:#edf7f3;color:var(--deep)}form>fieldset{border:0;padding:0;margin:0;min-width:0}.category-row{display:grid;grid-template-columns:minmax(100px,1fr) minmax(120px,1.3fr) minmax(260px,2fr) auto;gap:12px;align-items:start;border:1px solid var(--line);padding:14px;margin:12px 0;border-radius:8px}.category-row>label,.config-fields>label,.config-reason{display:grid;gap:6px;font-size:12px}.category-row input:not([type=checkbox]),.config-fields input,.config-reason textarea{width:100%;box-sizing:border-box;border:1px solid var(--line);padding:9px;border-radius:5px;background:var(--surface,#fff);font:inherit;color:inherit}.category-row input[readonly]{background:#f1f4f3}.unit-options{border:0;margin:0;padding:0;font-size:12px}.unit-options legend{margin-bottom:8px}.unit-options label{display:inline-flex;align-items:center;gap:5px;margin:0 10px 8px 0}.config-check{display:flex!important;align-items:center;gap:8px;font-size:12px;margin:8px 0}.category-row .config-check{margin-top:22px}.config-reason{margin:20px 0 14px}.config-reason textarea{resize:vertical}.config-fields{display:grid;grid-template-columns:1fr 1fr;gap:16px;margin:16px 0}.policy-directory{display:grid;grid-template-columns:repeat(auto-fill,minmax(230px,1fr));gap:10px}.policy-directory button{padding:14px;text-align:left;background:var(--surface,#fff);border:1px solid var(--line);border-radius:7px;overflow-wrap:anywhere}.policy-directory button.active{border-color:var(--teal);background:#edf7f3}.policy-directory strong,.policy-directory span{display:block}.policy-directory span{font-size:12px;color:var(--muted);margin-top:6px}.publish-review{border:1px solid #b6d4c9;border-radius:8px;padding:20px;background:#f7fbf9;margin:20px 0}.publish-review>p{font-size:13px;line-height:1.8;overflow-wrap:anywhere}.publish-versions{display:flex;gap:30px;font-size:13px;flex-wrap:wrap}.publish-versions dt{color:var(--muted)}.publish-versions dd{margin:6px 0;font-weight:600}button:disabled{opacity:.5;cursor:not-allowed}button:focus-visible,input:focus-visible,textarea:focus-visible,select:focus-visible{outline:2px solid var(--teal);outline-offset:3px}.sr-only{position:absolute;width:1px;height:1px;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap}@media(max-width:900px){.category-row{grid-template-columns:1fr 1fr}.unit-options{grid-column:1/-1}.category-row .config-check{margin-top:0}}@media(max-width:650px){.config-heading,.config-toolbar{align-items:stretch}.category-row,.config-fields{grid-template-columns:minmax(0,1fr)}.config-tabs{gap:5px}.config-tabs button{padding:8px 10px;flex:1}.publish-review{padding:14px}.config-current{gap:9px}.config-toolbar>button{flex:1}}
</style>
