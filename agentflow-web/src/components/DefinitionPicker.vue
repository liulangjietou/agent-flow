<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import { api } from '../api'
import { DefinitionCatalogQuery, type DefinitionCatalogItem } from '../definitionCatalog'

const props = defineProps<{ scopeKey: string; label: string; selectedId?: string; selectedLabel?: string;
  publishedOnly?: boolean; startEnabledOnly?: boolean; processKey?: string; locked?: boolean; refreshVersion?: number }>()
const MAX_DEFINITION_VERSION = 2_147_483_647
const emit = defineEmits<{ select: [item: DefinitionCatalogItem] }>()
const open = ref(false), text = ref(''), key = ref(''), version = ref(''), status = ref('')
const query = reactive(new DefinitionCatalogQuery(api.searchDefinitions))
const validation = ref(''), submitted = ref('')
const signature = computed(() => JSON.stringify([text.value, key.value, version.value, status.value]))
function search() {
  validation.value = ''
  const exactKey = props.processKey ?? key.value.trim(), number = version.value.trim()
  if (number && (!exactKey || !/^[1-9][0-9]*$/.test(number) || Number(number) > MAX_DEFINITION_VERSION || status.value === 'DRAFT')) {
    validation.value = '发布版本须为正整数，并配合准确流程标识使用。'; query.clear(); return
  }
  submitted.value = signature.value
  void query.load(props.scopeKey, { q: text.value.trim(), processKey: exactKey,
    status: props.publishedOnly || props.startEnabledOnly ? 'PUBLISHED' : status.value,
    ...(props.startEnabledOnly ? { startEnabled: true } : {}), ...(number ? { version: Number(number) } : {}) })
}
function toggle() { open.value = !open.value; if (open.value) search(); else query.clear() }
function choose(item: DefinitionCatalogItem) { if (!props.locked) { open.value = false; query.clear(); emit('select', item) } }
watch(() => [props.scopeKey, props.processKey, props.publishedOnly, props.startEnabledOnly], () => {
  open.value = false; text.value = ''; key.value = ''; version.value = ''; status.value = ''; validation.value = ''; query.clear()
}, { flush: 'sync' })
watch([key, status], () => { if (!(props.processKey ?? key.value).trim() || status.value === 'DRAFT') version.value = '' })
watch(() => props.refreshVersion, () => { if (open.value) search() })
onUnmounted(() => query.clear())
</script>

<template>
  <section class="definition-picker" role="group" :aria-label="label">
    <div class="picker-heading"><div><span>{{ label }}</span><strong>{{ selectedLabel || '尚未选择' }}</strong></div><button type="button" class="secondary" :disabled="locked" :aria-expanded="open" @click="toggle">{{ open ? '收起' : '选择' }}{{ label }}</button></div>
    <div v-if="open" class="picker-panel">
      <div class="picker-filters" @keydown.enter.prevent="search">
        <label>名称或标识<input v-model="text" type="search" maxlength="100" :aria-label="label + '搜索'" placeholder="输入名称或标识" /></label>
        <label v-if="!publishedOnly">状态<select v-model="status" :aria-label="label + '状态'"><option value="">全部状态</option><option value="DRAFT">草稿</option><option value="PUBLISHED">已发布</option></select></label>
        <label v-if="!processKey">准确流程标识<input v-model="key" maxlength="128" :aria-label="label + '准确标识'" placeholder="选填，精确匹配" /></label>
        <label>发布版本<input v-model="version" inputmode="numeric" maxlength="10" :aria-label="label + '版本'" :disabled="!(processKey || key.trim()) || status === 'DRAFT'" placeholder="全部版本" /></label>
        <button type="button" class="primary" :disabled="query.loading" @click="search">查询{{ label }}</button>
      </div>
      <p v-if="startEnabledOnly" class="picker-help">仅显示允许新发起的已发布版本。</p>
      <p v-if="processKey" class="picker-help">仅显示流程 {{ processKey }} 的已发布版本。</p>
      <p v-if="validation || query.error" class="picker-error" role="alert">{{ validation || query.error }}<button v-if="query.error" type="button" class="quiet" @click="query.loaded ? query.more() : search()">重试读取</button></p>
      <p v-if="query.loaded && submitted !== signature" class="picker-help" role="status">筛选已修改，查询后生效；当前仍为上次结果。</p>
      <p v-if="query.loading && !query.loaded" role="status" class="picker-help">正在读取流程…</p>
      <p v-else-if="query.loaded && !query.items.length" class="picker-help">没有匹配的流程版本，请调整筛选条件。</p>
      <ul v-if="query.items.length" class="picker-results"><li v-for="item in query.items" :key="item.id"><div><strong>{{ item.name }}</strong><small>{{ item.key }} · {{ item.status === 'PUBLISHED' ? '已发布 v' + item.version + (item.startEnabled ? '' : ' · 已停用') : '草稿' }}{{ selectedId === item.id ? ' · 当前选择' : '' }}</small></div><button type="button" class="secondary" :disabled="locked" :aria-label="`选择 ${item.name} ${item.key} ${item.status === 'PUBLISHED' ? 'v' + item.version : '草稿'}`" @click="choose(item)">选择</button></li></ul>
      <div class="picker-footer"><span v-if="query.loaded">已加载 {{ query.items.length }} 项 · 按创建时间倒序</span><button v-if="query.nextCursor" type="button" class="secondary" :disabled="query.loading" @click="query.more()">{{ query.loading ? '正在加载…' : '加载更多版本' }}</button></div>
    </div>
  </section>
</template>

<style scoped>
.definition-picker{min-width:0;width:100%}.picker-heading{display:flex;align-items:center;justify-content:space-between;gap:12px}.picker-heading>div{min-width:0}.picker-heading span,.picker-heading strong{display:block;overflow-wrap:anywhere}.picker-heading span{font-size:11px;color:var(--muted);margin-bottom:7px}.picker-heading strong{font-size:12px;font-weight:500}.picker-heading>button{font-size:11px;flex-shrink:0}.picker-panel{border:1px solid var(--line);border-radius:9px;padding:15px;margin-top:12px;background:var(--paper)}.picker-filters{display:flex;flex-wrap:wrap;gap:12px;align-items:end}.picker-filters label{display:grid;gap:7px;flex:1 1 140px;min-width:0;font-size:11px;color:var(--muted)}.picker-filters input,.picker-filters select{width:100%;min-width:0;padding:9px;border:1px solid var(--line);border-radius:6px;background:white;color:var(--ink)}.picker-filters button{font-size:11px}.picker-results{list-style:none;padding:0;margin:12px 0;max-height:330px;overflow:auto}.picker-results li{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:12px 0;border-bottom:1px solid var(--line)}.picker-results li>div{min-width:0}.picker-results strong,.picker-results small{display:block;overflow-wrap:anywhere}.picker-results strong{font-size:12px;font-weight:500}.picker-results small{margin-top:6px;font-size:10px;color:var(--muted)}.picker-results button{font-size:11px;flex-shrink:0}.picker-help,.picker-footer{font-size:11px;color:var(--muted);line-height:1.8}.picker-footer{display:flex;justify-content:space-between;gap:12px;align-items:center}.picker-footer button{font-size:11px}.picker-error{font-size:12px;color:var(--red);line-height:1.8}.picker-error button{margin-left:8px}@media(max-width:620px){.picker-heading{flex-wrap:wrap}.picker-filters label{flex-basis:100%}.picker-panel{padding:12px}}
</style>
