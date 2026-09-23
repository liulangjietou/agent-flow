<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import { api } from '../api'
import { ApplicationSearchQuery, type ApplicationSearchFilters } from '../applicationSearch'
import { ApplicationExportQuery, type ApplicationExportFilters } from '../applicationExport'

const props = defineProps<{ scopeKey: string; administrator: boolean; userId: string; refreshVersion: number; locked: boolean }>()
const emit = defineEmits<{ open: [id: string]; create: [] }>()
const emptyFilters = () => ({ q: '', status: '', processKey: '', version: '', applicant: '', from: '', to: '' })
const fields = reactive(emptyFilters())
const query = reactive(new ApplicationSearchQuery((filters, signal) => props.administrator ? api.searchApplications(filters, signal) : api.searchVisibleApplications(filters, signal)))
const exporter = reactive(new ApplicationExportQuery((filters, signal) => api.exportApplications(filters, signal)))
const appliedFilters = ref<ApplicationExportFilters>({}), downloadUrl = ref('')
const submitted = ref(''), validation = ref('')
const stateLabels: Record<string, string> = { DRAFT: '草稿', IN_APPROVAL: '审批中', RETURNED: '已退回', WITHDRAWN: '已撤回', APPROVED: '已批准', REJECTED: '已驳回', CANCELLED: '已作废', REVOKED: '已撤销' }
const snapshot = () => JSON.stringify(fields)
const changed = computed(() => submitted.value !== snapshot())
const dateLabel = (value: string) => new Date(value).toLocaleString('zh-CN', { hour12: false })

function refresh() {
  exporter.clear()
  validation.value = ''
  const key = fields.processKey.trim(), version = fields.version.trim()
  if (version && (!key || !/^[1-9][0-9]*$/.test(version) || Number(version) > 2147483647)) validation.value = '请填写流程标识，并使用有效的正整数版本。'
  if (fields.from && fields.to && fields.from > fields.to) validation.value = '创建结束日期不能早于开始日期。'
  if (validation.value) { query.clear(); return }
  const filters: ApplicationSearchFilters = { q: fields.q.trim(), status: fields.status, processKey: key, applicant: fields.applicant.trim() }
  if (version) filters.definitionVersion = Number(version)
  if (fields.from) filters.from = fields.from
  if (fields.to) filters.to = fields.to
  submitted.value = snapshot()
  appliedFilters.value = { ...filters }
  void query.load(props.scopeKey, filters)
}
function reset() { Object.assign(fields, emptyFilters()); refresh() }
function releaseDownload() { if (downloadUrl.value) URL.revokeObjectURL(downloadUrl.value); downloadUrl.value = '' }
watch(fields, () => exporter.clear(), { deep: true, flush: 'sync' })
watch(() => exporter.file, file => { releaseDownload(); if (file) downloadUrl.value = URL.createObjectURL(file) }, { flush: 'sync' })
watch(() => fields.processKey, value => { if (!value.trim()) fields.version = '' })
watch(() => [props.scopeKey, props.administrator], reset, { immediate: true, flush: 'sync' })
watch(() => props.refreshVersion, refresh)
onUnmounted(() => { query.clear(); exporter.clear(); releaseDownload() })
</script>

<template>
  <section class="content application-search" :aria-busy="query.loading">
    <div class="page-heading"><div><p class="eyebrow">APPLICATIONS / SEARCH</p><h2>申请记录</h2><p class="subhead">{{ administrator ? '检索当前租户的申请，查看进度、原始轮次和审批依据。' : '检索我发起或有权参与的申请，查看当前进度和办理记录。' }}</p></div><button class="primary" :disabled="locked" @click="emit('create')">＋ 发起申请</button></div>
    <form class="panel search-filters" @submit.prevent="refresh">
      <label class="search-text">标题或业务单号<input v-model="fields.q" type="search" maxlength="100" placeholder="输入关键词查询" /></label>
      <label>申请状态<select v-model="fields.status"><option value="">全部状态</option><option v-for="(label, value) in stateLabels" :key="value" :value="value">{{ label }}</option></select></label>
      <label>申请人账号<input v-model="fields.applicant" maxlength="128" placeholder="精确账号，如 alice" /></label>
      <label>流程标识<input v-model="fields.processKey" maxlength="128" placeholder="精确标识，如 leave-request" /></label>
      <label>流程版本<input v-model="fields.version" inputmode="numeric" maxlength="10" :disabled="!fields.processKey.trim()" placeholder="全部版本" /></label>
      <label>创建开始日期（UTC）<input v-model="fields.from" type="date" min="0001-01-01" max="9999-12-31" /></label>
      <label>创建结束日期（UTC）<input v-model="fields.to" type="date" min="0001-01-01" max="9999-12-31" /></label>
      <div class="search-actions"><button class="primary" :disabled="query.loading">查询申请</button><button type="button" class="secondary" @click="reset">重置筛选</button></div>
    </form>
    <p v-if="validation" class="search-error" role="alert">{{ validation }}</p>
    <p v-if="changed && query.loaded" class="search-pending" role="status">筛选已修改，下方仍是上次查询结果。点击“查询申请”后生效。</p>
    <div class="search-summary"><span>按创建时间倒序 · 日期筛选包含首尾两天 · 记录时间按当前设备时区显示</span><span v-if="query.loaded">已加载 {{ query.items.length }} 份申请</span></div>
    <div v-if="administrator" class="panel search-export" :aria-busy="exporter.loading">
      <div><strong>导出申请摘要</strong><p>按已查询条件导出全部匹配记录，每次最多 10,000 份。包含单号、状态和时间，不含表单正文与审批意见。</p></div>
      <button class="secondary" :disabled="!query.loaded || query.loading || !!query.error || changed || !!validation || exporter.loading" @click="exporter.generate(scopeKey, appliedFilters)">{{ exporter.loading ? '正在生成…' : '生成 Excel' }}</button>
      <button v-if="exporter.loading" class="secondary" @click="exporter.clear()">取消等待</button>
      <p v-if="exporter.error" class="search-error" role="alert">{{ exporter.error }}</p>
      <p v-if="downloadUrl" class="export-ready" role="status">文件已生成。<a :href="downloadUrl" download="agentflow-applications.xlsx">下载 Excel ↓</a> <span>生成后审批状态仍可能变化；筛选修改后需重新生成。</span></p>
    </div>
    <div v-if="query.error" class="panel search-failure" role="alert"><div><strong>{{ query.items.length ? '后续记录加载失败' : '暂时无法读取申请' }}</strong><p>{{ query.error }}</p></div><button class="secondary" :disabled="query.loading || changed" @click="query.items.length ? query.more() : refresh()">重试</button></div>
    <div v-if="!query.items.length && query.loading" class="panel search-empty" role="status">正在查询申请…</div>
    <div v-else-if="!query.items.length && query.loaded" class="panel search-empty"><h3>没有符合条件的申请</h3><p>调整筛选条件，或发起一份新申请。</p></div>
    <div v-if="query.items.length" class="panel search-results">
      <article v-for="item in query.items" :key="item.id" class="search-row">
        <div class="search-subject"><h3>{{ item.title }}</h3><p>{{ item.businessNo }}</p><small>{{ item.processKey }} · v{{ item.definitionVersion }} · 第 {{ item.roundNo }} 轮</small></div>
        <div class="search-person"><small>申请人</small><strong>{{ item.createdBy }}</strong><time>{{ dateLabel(item.createdAt) }}</time><small>创建时间</small></div>
        <div class="search-state"><span class="search-badge" :class="item.status.toLowerCase()">{{ stateLabels[item.status] ?? item.status }}</span><button class="secondary" :disabled="locked" @click="emit('open', item.id)">{{ item.createdBy === userId && ['DRAFT', 'RETURNED', 'WITHDRAWN'].includes(item.status) ? '查看并修改 ↗' : '查看详情 ↗' }}</button></div>
      </article>
    </div>
    <div v-if="query.items.length" class="search-pagination"><button v-if="query.nextCursor" class="secondary" :disabled="query.loading || changed" @click="query.more()">{{ query.loading ? '加载中…' : '加载更多申请' }}</button><span v-else>已加载全部匹配申请</span><small>审批状态可能继续变化，可重新查询获取最新结果。</small></div>
  </section>
</template>

<style scoped>
.search-export{display:flex;align-items:center;gap:16px;flex-wrap:wrap;padding:18px 22px;margin-bottom:20px}.search-export>div{flex:1;min-width:200px}.search-export strong{font-size:12px;color:var(--deep)}.search-export p{font-size:11px;line-height:1.8;margin:6px 0 0;color:var(--muted)}.search-export>p{flex-basis:100%}.search-export .search-error{color:var(--red)}.export-ready a{color:var(--deep);font-weight:600;text-underline-offset:3px}.export-ready span{margin-left:8px}
.search-filters{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:18px;padding:24px}.search-filters label{display:flex;flex-direction:column;gap:8px;color:var(--muted);font-size:11px;min-width:0}.search-filters .search-text{grid-column:span 2}.search-filters input,.search-filters select{width:100%;min-width:0;height:40px;border:1px solid var(--line);border-radius:7px;padding:8px 10px;background:white;font:inherit;color:var(--ink)}.search-filters input:disabled{background:#f3f5f4}.search-actions{display:flex;align-items:end;gap:10px;grid-column:span 2}.search-summary{display:flex;justify-content:space-between;gap:18px;color:var(--muted);font-size:11px;line-height:1.7;margin:20px 0}.search-pending{font-size:12px;padding:12px 16px;background:#fff5e1;color:#866324;border-radius:6px}.search-error{color:var(--red);font-size:12px}.search-row{display:grid;grid-template-columns:minmax(0,1fr) 180px 120px;gap:26px;align-items:center;padding:24px}.search-row+.search-row{border-top:1px solid var(--line)}.search-subject h3{font-size:15px;margin:0 0 8px;overflow-wrap:anywhere}.search-subject p{font-size:12px;margin:0 0 10px;color:var(--muted);overflow-wrap:anywhere}.search-subject small{color:var(--muted);font-size:11px;overflow-wrap:anywhere}.search-person,.search-state{display:flex;flex-direction:column;align-items:start;gap:8px;min-width:0}.search-person strong{font-size:12px;overflow-wrap:anywhere}.search-person small{font-size:10px;color:var(--muted)}.search-person time{font-size:11px;color:var(--muted)}.search-state{gap:14px;align-items:end}.search-state button{white-space:nowrap}.search-badge{border-radius:5px;padding:5px 8px;font-size:11px;background:#f0f3f2;color:#64746f}.search-badge.in_approval{background:#eef4ff;color:#456788}.search-badge.approved{background:var(--soft);color:var(--deep)}.search-badge.returned,.search-badge.withdrawn{background:#fff4df;color:#8b682e}.search-badge.rejected{background:#fff0ed;color:var(--red)}.search-empty{padding:48px 20px;text-align:center;color:var(--muted);font-size:13px}.search-empty h3{font-size:16px;color:var(--ink)}.search-pagination{text-align:center;padding:24px;font-size:11px;color:var(--muted)}.search-pagination small{display:block;margin-top:12px}.search-failure{display:flex;align-items:center;justify-content:space-between;gap:20px;padding:20px;color:var(--red);font-size:12px;margin-bottom:18px}.search-failure p{margin-bottom:0}
@media(max-width:1100px){.search-filters{grid-template-columns:repeat(2,minmax(0,1fr))}.search-row{grid-template-columns:minmax(0,1fr) 155px;gap:18px}.search-state{grid-column:2;flex-direction:row;align-items:center;flex-wrap:wrap}.search-subject{grid-row:span 2}}
@media(max-width:650px){.application-search .page-heading{align-items:start;flex-direction:column;gap:16px}.search-filters{padding:16px;gap:14px}.search-actions{flex-wrap:wrap}.search-summary{flex-direction:column;gap:5px}.search-row{display:flex;flex-wrap:wrap;padding:20px}.search-subject{width:100%}.search-person{flex:1}.search-state{align-self:end;align-items:end;flex-direction:column}.search-failure{align-items:start;flex-direction:column}}
</style>
