<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import { api } from '../api'
import { priorControlLabel } from '../expensePriorControl'
import { expenseUnits, type CategoryVersionSummary, type PolicyVersionSummary, type PolicyActivation, type ConfigurationHistory,
  type ExpenseCategories, type ExpensePolicyDefinition } from '../expenseConfiguration'
import { ConfigurationRead } from '../expenseConfigurationRead'
import ExpensePolicySummary from './ExpensePolicySummary.vue'

const props = defineProps<{ scopeKey: string; mode: 'categories' | 'policies' | 'activations'; refreshVersion: number; policyKey?: string; policyId?: string; draftRevision?: number }>()
const emit = defineEmits<{ unavailable: [status: number] }>()
interface Entry { version: number; title: string; by: string; at: string; comment: string; policyKey?: string; policyVersion?: number }
interface Detail { title: string; by: string; at: string; comment: string; categories?: ExpenseCategories; definition?: ExpensePolicyDefinition; sourceCategory?: number; sourceDraft?: number }
const list = reactive(new ConfigurationRead<ConfigurationHistory<Entry>>()), detail = reactive(new ConfigurationRead<Detail>())
const entries = ref<Entry[]>([]), nextVersion = ref<number | null>(null), draftNumber = ref<number | string>(1)
const modeLabel = computed(() => props.mode === 'categories' ? '类别修订历史' : props.mode === 'policies' ? '制度发布历史' : '制度生效记录')
const when = (value: string) => new Date(value).toLocaleString('zh-CN', { hour12: false })
function clear() { list.clear(); detail.clear(); entries.value = []; nextVersion.value = null }
function failure(status: number) { if ([401, 403, 404].includes(status)) { entries.value = []; nextVersion.value = null; detail.clear() }; if ([401, 403].includes(status)) emit('unavailable', status) }
/** 失败保留翻页边界；只有完整读取成功后才接纳新游标。 */
async function load(more = false) {
  if (!props.scopeKey || list.loading || props.mode === 'policies' && !props.policyKey) return
  const cursor = more ? nextVersion.value ?? undefined : undefined
  if (!more) { entries.value = []; nextVersion.value = null; detail.clear() }
  const value = await list.load(async signal => {
    if (props.mode === 'categories') {
      const page = await api.expenseCategoryVersions(cursor, signal)
      return { ...page, items: page.items.map((item: CategoryVersionSummary) => ({ version: item.version, title: `类别修订 ${item.version} · ${item.categoryCount} 个类别`, by: item.updatedBy, at: item.updatedAt, comment: item.comment })) }
    }
    if (props.mode === 'policies') {
      const page = await api.expensePolicyVersions(props.policyKey!, cursor, signal)
      return { ...page, items: page.items.map((item: PolicyVersionSummary) => ({ version: item.version, title: `${item.name} v${item.version} · 草稿 ${item.draftRevision} / 类别 ${item.categoryRevision}`, by: item.publishedBy, at: item.publishedAt, comment: item.comment, policyKey: props.policyKey, policyVersion: item.version })) }
    }
    const page = await api.expensePolicyActivations(cursor, signal)
    return { ...page, items: page.items.map((item: PolicyActivation) => ({ version: item.revision, title: `生效修订 ${item.revision} · ${item.key} v${item.policyVersion}`, by: item.activatedBy, at: item.activatedAt, comment: item.comment, policyKey: item.key, policyVersion: item.policyVersion })) }
  })
  if (!value) { failure(list.status); return }
  entries.value = more ? [...entries.value, ...value.items] : value.items; nextVersion.value = value.nextBeforeVersion
}
async function open(entry: Entry) {
  const mode = props.mode
  const value = await detail.load(async signal => {
    if (mode === 'categories') {
      const revision = await api.expenseCategoryVersion(entry.version, signal)
      return { title: '类别修订 ' + revision.catalog.version, by: revision.updatedBy, at: revision.updatedAt, comment: revision.comment, categories: revision.catalog }
    }
    const policy = await api.expensePolicyVersion(entry.policyKey!, entry.policyVersion!, signal)
    return { title: policy.definition.name + ' v' + policy.version, by: policy.publishedBy, at: policy.publishedAt, comment: policy.comment,
      definition: policy.definition, sourceCategory: policy.categoryRevision, sourceDraft: policy.draftRevision }
  })
  if (!value) failure(detail.status)
}
async function openDraft() {
  const number = Number(draftNumber.value)
  if (!props.policyKey || !props.policyId || !Number.isSafeInteger(number) || number < 1 || number > (props.draftRevision ?? 0)) return
  const key = props.policyKey, id = props.policyId
  const value = await detail.load(async signal => {
    const revision = await api.expensePolicyDraftVersion(key, id, number, signal)
    return { title: `${key} · 草稿修订 ${revision.revision}`, by: revision.updatedBy, at: revision.updatedAt, comment: revision.comment, definition: revision.definition }
  })
  if (!value) failure(detail.status)
}
watch(() => [props.scopeKey, props.mode, props.policyKey, props.policyId, props.refreshVersion], () => { clear(); draftNumber.value = props.draftRevision ?? 1; void load() }, { immediate: true, flush: 'sync' })
onUnmounted(clear)
</script>
<template>
  <section class="config-history" :aria-label="modeLabel">
    <div class="history-heading"><h3>{{ modeLabel }}</h3><button class="secondary" type="button" :disabled="list.loading" @click="load()">刷新历史</button></div>
    <p v-if="list.error" class="history-error" role="alert">{{ list.error }}</p><p v-if="list.loading" role="status">正在读取历史…</p>
    <p v-if="!list.loading && !list.error && !entries.length" class="history-help">暂无记录。</p>
    <ol><li v-for="entry in entries" :key="entry.version"><button type="button" :disabled="detail.loading" @click="open(entry)"><strong>{{ entry.title }}</strong><span>{{ entry.by }} · {{ when(entry.at) }}</span><span>{{ entry.comment }}</span><span>查看不可变正文 →</span></button></li></ol>
    <button v-if="nextVersion" class="secondary" type="button" :disabled="list.loading" @click="load(true)">更早记录</button>
    <form v-if="mode === 'policies' && policyId" class="draft-history" @submit.prevent="openDraft"><label>历史草稿修订<input v-model="draftNumber" type="number" min="1" :max="draftRevision" step="1" required /></label><button class="secondary" type="submit" :disabled="detail.loading">查看草稿修订</button><small>可查 1 至 {{ draftRevision }}，含尚未发布的修改</small></form>
    <p v-if="detail.error" class="history-error" role="alert">{{ detail.error }}</p><p v-if="detail.loading" role="status">正在读取历史正文…</p>
    <section v-if="detail.value" class="history-detail" aria-label="历史版本正文"><div class="history-heading"><h4>{{ detail.value.title }}</h4><button class="quiet" type="button" @click="detail.clear()">关闭正文</button></div><p>{{ detail.value.by }} · {{ when(detail.value.at) }}</p><p class="history-comment">{{ detail.value.comment }}</p><p v-if="detail.value.sourceCategory">来源：草稿修订 {{ detail.value.sourceDraft }}，类别修订 {{ detail.value.sourceCategory }}</p>
      <div v-if="detail.value.categories" class="category-history"><article v-for="category in detail.value.categories.categories" :key="category.code"><strong>{{ category.name }}</strong><span>{{ category.code }} · {{ category.active ? '启用' : '停用' }}</span><span>允许单位：{{ category.units.map(unit => expenseUnits[unit]).join('、') }}</span><span>事前额度：{{ priorControlLabel(category.priorControl) }}</span></article></div>
      <ExpensePolicySummary v-if="detail.value.definition" :definition="detail.value.definition" />
    </section>
  </section>
</template>
<style scoped>
.config-history{border:1px solid var(--line);border-radius:8px;padding:16px;margin:18px 0;background:var(--surface,#fff);font-size:13px}.history-heading{display:flex;justify-content:space-between;gap:14px;align-items:center}.history-heading h3,.history-heading h4{margin:4px 0;font-size:15px}.config-history ol{list-style:none;padding:0;margin:15px 0;display:grid;gap:9px}.config-history li button{border:1px solid var(--line);border-radius:6px;background:transparent;padding:12px;text-align:left;width:100%;color:inherit}.config-history li span{display:block;color:var(--muted);font-size:12px;margin-top:5px;overflow-wrap:anywhere}.config-history li strong{overflow-wrap:anywhere}.history-help,small{color:var(--muted);font-size:12px}.history-error{color:var(--red)}.history-detail{background:#f5f8f6;border:1px solid var(--line);border-radius:6px;padding:16px;margin-top:16px}.history-detail>p{font-size:12px;overflow-wrap:anywhere}.history-comment{white-space:pre-wrap}.draft-history{display:flex;flex-wrap:wrap;align-items:end;gap:12px;border-top:1px solid var(--line);padding-top:15px;margin-top:20px}.draft-history label{display:grid;gap:6px;font-size:12px}.draft-history input{padding:8px;border:1px solid var(--line);border-radius:5px;width:130px;font:inherit}.category-history{display:grid;grid-template-columns:repeat(auto-fill,minmax(200px,1fr));gap:10px}.category-history article{padding:12px;border:1px solid var(--line);background:var(--surface,#fff);border-radius:6px;overflow-wrap:anywhere}.category-history span{display:block;font-size:12px;color:var(--muted);margin-top:6px}button:disabled{opacity:.5;cursor:not-allowed}button:focus-visible,input:focus-visible{outline:2px solid var(--teal);outline-offset:3px}
</style>
