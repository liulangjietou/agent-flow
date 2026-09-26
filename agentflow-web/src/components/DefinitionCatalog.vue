<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import { api } from '../api'
import { DefinitionCatalogQuery, type DefinitionCatalogFilters } from '../definitionCatalog'

const props = defineProps<{ scopeKey: string; manageDefinitions: boolean; currentId: string; locked: boolean; refreshVersion: number }>()
const emit = defineEmits<{ open: [id: string]; close: [] }>()
const empty = () => ({ q: '', status: '', availability: '', processKey: '', version: '' })
const fields = reactive(empty())
const query = reactive(new DefinitionCatalogQuery(api.searchDefinitions))
const submitted = ref(''), validation = ref('')
const changed = computed(() => submitted.value !== JSON.stringify(fields))
const time = (value: string) => new Date(value).toLocaleString('zh-CN', { hour12: false })

function search() {
  validation.value = ''
  const key = fields.processKey.trim(), version = fields.version.trim()
  if (version && (!key || !/^[1-9][0-9]*$/.test(version) || Number(version) > 2147483647 || fields.status === 'DRAFT')) {
    validation.value = '版本筛选需填写准确的流程标识，并选择已发布或全部状态。版本须为正整数。'
    query.clear(); return
  }
  const filters: DefinitionCatalogFilters = { q: fields.q.trim(), status: fields.status, processKey: key, ...(fields.status === 'PUBLISHED' && fields.availability ? { startEnabled: fields.availability === 'true' } : {}) }
  if (version) filters.version = Number(version)
  submitted.value = JSON.stringify(fields)
  void query.load(props.scopeKey, filters)
}
function reset() { Object.assign(fields, empty()); search() }
watch(() => [fields.processKey, fields.status], () => { if (!fields.processKey.trim() || fields.status === 'DRAFT') fields.version = '' })
watch(() => [props.scopeKey, props.manageDefinitions], reset, { immediate: true, flush: 'sync' })
watch(() => props.refreshVersion, search)
onUnmounted(() => query.clear())
</script>

<template>
  <section class="definition-catalog panel" aria-label="流程目录" :aria-busy="query.loading">
    <div class="catalog-heading"><div><p class="eyebrow">PROCESS LIBRARY</p><h3>流程目录</h3><p>找到草稿继续编辑，或打开已发布版本核对配置。</p></div><button class="quiet" aria-label="关闭流程目录" @click="emit('close')">×</button></div>
    <form class="catalog-filters" @submit.prevent="search">
      <label class="catalog-keyword">名称或标识<input v-model="fields.q" type="search" maxlength="100" placeholder="搜索请假、合同或流程标识" /></label>
      <label>流程状态<select v-model="fields.status"><option value="">{{ manageDefinitions ? '全部状态' : '已发布版本' }}</option><option v-if="manageDefinitions" value="DRAFT">草稿</option><option value="PUBLISHED">已发布</option></select></label>
      <label>准确流程标识<input v-model="fields.processKey" maxlength="128" placeholder="查看同一流程的所有版本" /></label>
      <label v-if="fields.status === 'PUBLISHED'">发起状态<select v-model="fields.availability"><option value="">全部</option><option value="true">允许新发起</option><option value="false">已停用</option></select></label>
      <label>发布版本<input v-model="fields.version" inputmode="numeric" maxlength="10" placeholder="全部版本" :disabled="!fields.processKey.trim() || fields.status === 'DRAFT'" /></label>
      <div class="catalog-actions"><button class="primary" :disabled="query.loading">查询流程</button><button type="button" class="secondary" @click="reset">重置筛选</button></div>
    </form>
    <p v-if="validation" class="catalog-error" role="alert">{{ validation }}</p>
    <p v-if="changed && query.loaded" class="catalog-pending" role="status">筛选已修改，查询后生效；下方仍为上次结果。</p>
    <div class="catalog-summary"><span>按创建时间倒序 · 保存草稿不会改变顺序</span><span v-if="query.loaded">已加载 {{ query.items.length }} 个版本或草稿</span></div>
    <div v-if="query.error" class="catalog-error" role="alert"><p>{{ query.error }}</p><button class="secondary" :disabled="query.loading" @click="query.loaded ? query.more() : search()">重试读取</button></div>
    <p v-if="query.loading && !query.loaded" class="catalog-empty" role="status">正在读取流程目录…</p>
    <div v-else-if="query.loaded && !query.items.length" class="catalog-empty"><strong>没有匹配的流程</strong><p>减少筛选条件，或关闭目录后新建流程。</p></div>
    <div v-if="query.items.length" class="catalog-table-wrap">
      <table><caption class="catalog-sr-only">当前租户流程目录</caption><thead><tr><th scope="col">流程</th><th scope="col">状态 / 版本</th><th scope="col">最近修改</th><th scope="col">操作</th></tr></thead>
        <tbody><tr v-for="item in query.items" :key="item.id" :class="{ current: item.id === currentId }">
          <td data-label="流程"><strong>{{ item.name }}</strong><code>{{ item.key }}</code></td>
          <td data-label="状态 / 版本"><span class="catalog-status" :class="item.status.toLowerCase()">{{ item.status === 'PUBLISHED' ? `已发布 v${item.version}${item.startEnabled ? '' : ' · 已停用'}` : '草稿' }}</span><small v-if="item.id === currentId">当前打开</small></td>
          <td data-label="最近修改"><time :datetime="item.updatedAt">{{ time(item.updatedAt) }}</time><small>创建于 {{ time(item.createdAt) }}</small></td>
          <td data-label="操作"><button class="secondary" :disabled="locked" :aria-label="`${item.status === 'DRAFT' ? '编辑草稿' : '查看版本'} ${item.name} ${item.key}${item.version ? ' v' + item.version : ''}`" @click="emit('open', item.id)">{{ item.status === 'DRAFT' ? '编辑草稿' : '查看版本' }} ↗</button></td>
        </tr></tbody>
      </table>
    </div>
    <div class="catalog-footer"><button v-if="query.nextCursor" class="secondary" :disabled="query.loading" @click="query.more()">{{ query.loading ? '正在加载…' : '加载更多流程' }}</button><span v-else-if="query.loaded && query.items.length">已显示本次查询的全部结果</span><small>打开时读取最新配置；当前未保存修改仍会先确认。</small></div>
  </section>
</template>

<style scoped>
.definition-catalog{padding:22px;margin:18px 0;min-width:0}.catalog-heading{display:flex;align-items:start;justify-content:space-between;gap:16px}.catalog-heading h3{font-size:21px;margin:6px 0}.catalog-heading p:not(.eyebrow){font-size:12px;color:var(--muted);line-height:1.8}.catalog-heading>button{font-size:24px}.catalog-filters{display:grid;grid-template-columns:minmax(180px,2fr) minmax(110px,1fr) minmax(160px,1.4fr) 100px;gap:14px;margin:18px 0;padding:18px 0;border-block:1px solid var(--line)}.catalog-filters label{display:grid;gap:8px;min-width:0;font-size:12px;color:var(--muted)}.catalog-filters input,.catalog-filters select{width:100%;min-width:0;padding:10px;border:1px solid var(--line);border-radius:7px;background:var(--paper);color:var(--ink)}.catalog-actions{grid-column:1/-1;display:flex;gap:8px}.catalog-summary{display:flex;justify-content:space-between;gap:12px;flex-wrap:wrap;font-size:11px;color:var(--muted);margin:16px 0}.catalog-table-wrap{max-width:100%;overflow:auto}.catalog-table-wrap table{border-collapse:collapse;width:100%;font-size:12px;text-align:left}.catalog-table-wrap th{font-size:10px;color:var(--muted);font-weight:500;padding:12px;border-bottom:1px solid var(--line)}.catalog-table-wrap td{padding:16px 12px;border-bottom:1px solid var(--line);vertical-align:middle;overflow-wrap:anywhere}.catalog-table-wrap td:first-child{max-width:330px}.catalog-table-wrap strong{font-weight:600}.catalog-table-wrap code,.catalog-table-wrap small{display:block;margin-top:7px;font-size:10px;color:var(--muted);overflow-wrap:anywhere}.catalog-table-wrap .current{background:var(--soft)}.catalog-status{display:inline-block;font-size:11px;padding:5px 8px;border-radius:5px;background:#f8efdc;color:#856a35;white-space:nowrap}.catalog-status.published{background:var(--soft);color:var(--deep)}.catalog-table-wrap button{font-size:11px;white-space:nowrap}.catalog-error{font-size:12px;color:var(--red);line-height:1.8}.catalog-empty{padding:26px 0;font-size:13px;line-height:1.8;color:var(--muted)}.catalog-empty strong{color:var(--ink)}.catalog-pending{font-size:12px;color:#856a35}.catalog-footer{display:flex;flex-wrap:wrap;align-items:center;gap:14px;margin-top:18px;font-size:12px;color:var(--muted)}.catalog-footer small{margin-left:auto;font-size:11px}.catalog-sr-only{position:absolute;width:1px;height:1px;padding:0;margin:-1px;overflow:hidden;clip:rect(0,0,0,0)}
@media(max-width:1000px){.catalog-filters{grid-template-columns:repeat(2,minmax(0,1fr))}}@media(max-width:620px){.definition-catalog{padding:16px}.catalog-filters{grid-template-columns:minmax(0,1fr)}.catalog-table-wrap thead{display:none}.catalog-table-wrap table,.catalog-table-wrap tbody,.catalog-table-wrap tr,.catalog-table-wrap td{display:block;width:100%;max-width:none;box-sizing:border-box}.catalog-table-wrap tr{border:1px solid var(--line);border-radius:9px;margin:12px 0;padding:10px}.catalog-table-wrap td{border:0;padding:7px}.catalog-table-wrap td:first-child{max-width:none}.catalog-table-wrap td::before{content:attr(data-label);display:block;font-size:10px;color:var(--muted);margin-bottom:7px}.catalog-footer small{margin-left:0}}
</style>
